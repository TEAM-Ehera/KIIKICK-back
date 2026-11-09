package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.global.exception.InvalidWorkoutPayloadException;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class WorkoutContextCodec {
    private final ObjectMapper json;
    private final Validator validator;

    public WorkoutContextCodec(ObjectMapper json, Validator validator) {
        this.json = json;
        this.validator = validator;
    }

    public void validate(WorkoutPayloadCreateRequest request) {
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw invalid(violations.stream().map(v -> v.getPropertyPath().toString()).sorted().findFirst().orElse("body"));
        }
        for (int index = 0; index < request.events().size(); index++) {
            var point = request.events().get(index).location();
            var values = new double[] {point.latitude(), point.longitude(), point.altitude(),
                    point.horizontalAccuracy(), point.speed(), point.course(), point.timestamp()};
            for (double value : values) {
                if (!Double.isFinite(value)) {
                    throw invalid("events[" + index + "].location");
                }
            }
            if (point.timestamp() < request.startDate() || point.timestamp() > request.endDate()
                    || (point.speed() < 0 && point.speed() != -1)
                    || (point.course() != -1 && (point.course() < 0 || point.course() >= 360))) {
                throw invalid("events[" + index + "].location");
            }
        }
    }

    public String events(WorkoutPayloadCreateRequest request) {
        return request.events().isEmpty() ? null : json.writeValueAsString(request.events());
    }

    public String field(WorkoutPayloadCreateRequest request) {
        // 경기장 수정·삭제 이후에도 운동 당시 보정 좌표와 원본 핀을 복원할 수 있어야 한다.
        return request.field() == null ? null : json.writeValueAsString(request.field());
    }

    private static InvalidWorkoutPayloadException invalid(String field) {
        return new InvalidWorkoutPayloadException(field + ": 유효하지 않은 운동 데이터입니다.", null);
    }
}
