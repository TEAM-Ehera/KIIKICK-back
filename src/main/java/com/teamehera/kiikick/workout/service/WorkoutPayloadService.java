package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.global.exception.UserNotFoundException;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.dto.HeartRateSampleRequest;
import com.teamehera.kiikick.workout.dto.TrackPointRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateResponse;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import com.teamehera.kiikick.global.exception.InvalidWorkoutPayloadException;
import com.teamehera.kiikick.global.exception.UserNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@Service
public class WorkoutPayloadService {

    private final UserRepository userRepository;
    private final WorkoutPayloadRepository workoutPayloadRepository;

    public WorkoutPayloadService(
            UserRepository userRepository,
            WorkoutPayloadRepository workoutPayloadRepository
    ) {
        this.userRepository = userRepository;
        this.workoutPayloadRepository = workoutPayloadRepository;
    }

    /*
     * 운동 본체와 GPS·심박수를 하나의 트랜잭션으로 저장한다.
     *
     * 처리 중 예외가 발생하면 이 메서드에서 실행한 INSERT가 모두 취소된다.
     */
    @Transactional
    public WorkoutPayloadCreateResponse create(
            UUID userId,
            WorkoutPayloadCreateRequest request
    ) {
        // 삭제되지 않은 사용자인지 확인한다.
        validateUserExists(userId);

        UUID workoutPayloadId = UUID.randomUUID();

        WorkoutPayload workoutPayload = new WorkoutPayload(
                workoutPayloadId,
                userId,
                convertDate(request.startDate()),
                convertDate(request.endDate()),
                request.totalCalories(),
                request.totalDistance()
        );

        List<TrackPoint> trackPoints = createTrackPoints(
                workoutPayloadId,
                request.points()
        );

        List<HeartRateSample> heartRateSamples =
                createHeartRateSamples(
                        workoutPayloadId,
                        request.heartRateSamples()
                );

        /*
         * 자식 테이블의 외래 키가 부모 운동을 참조하므로
         * workoutPayloads를 가장 먼저 저장해야 한다.
         */
        workoutPayloadRepository.saveWorkoutPayload(workoutPayload);
        workoutPayloadRepository.saveTrackPoints(trackPoints);
        workoutPayloadRepository.saveHeartRateSamples(heartRateSamples);

        return new WorkoutPayloadCreateResponse(workoutPayloadId);
    }

    /*
     * 사용자가 없거나 이미 삭제됐다면 기존 UserNotFoundException을 재사용한다.
     */
    private void validateUserExists(UUID userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    /*
     * GPS 요청 DTO 목록을 DB 저장용 Domain 목록으로 변환한다.
     */
    private List<TrackPoint> createTrackPoints(
            UUID workoutPayloadId,
            List<TrackPointRequest> requests
    ) {
        List<TrackPoint> trackPoints =
                new ArrayList<>(requests.size());

        for (int sampleSeq = 0;
             sampleSeq < requests.size();
             sampleSeq++) {

            TrackPointRequest request = requests.get(sampleSeq);

            trackPoints.add(
                    new TrackPoint(
                            workoutPayloadId,
                            sampleSeq,
                            request.latitude(),
                            request.longitude(),
                            request.altitude(),
                            request.horizontalAccuracy(),
                            request.speed(),
                            request.course(),
                            convertDate(request.timestamp())
                    )
            );
        }

        return trackPoints;
    }

    /*
     * 심박수 요청 DTO 목록을 DB 저장용 Domain 목록으로 변환한다.
     */
    private List<HeartRateSample> createHeartRateSamples(
            UUID workoutPayloadId,
            List<HeartRateSampleRequest> requests
    ) {
        List<HeartRateSample> heartRateSamples =
                new ArrayList<>(requests.size());

        for (int sampleSeq = 0;
             sampleSeq < requests.size();
             sampleSeq++) {

            HeartRateSampleRequest request = requests.get(sampleSeq);

            heartRateSamples.add(
                    new HeartRateSample(
                            workoutPayloadId,
                            sampleSeq,
                            convertDate(request.timestamp()),
                            request.bpm()
                    )
            );
        }

        return heartRateSamples;
    }

    /*
     * 날짜 변환 호출을 한곳에 모아 모든 날짜에 같은 규칙을 적용한다.
     */
    private LocalDateTime convertDate(Double appleEpochSeconds) {
        try {
            return AppleEpochTimeConverter.toUtcLocalDateTime(
                    appleEpochSeconds
            );
        } catch (IllegalArgumentException exception) {
            throw new InvalidWorkoutPayloadException(
                    "Apple epoch 날짜가 유효하지 않습니다.",
                    exception
            );
        }
    }

}