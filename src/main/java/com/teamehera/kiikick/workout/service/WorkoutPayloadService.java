package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.global.exception.InvalidWorkoutPayloadException;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class WorkoutPayloadService {

    private static final LocalDateTime MYSQL_DATETIME_MIN =
            LocalDateTime.of(1000, 1, 1, 0, 0);

    private static final LocalDateTime MYSQL_DATETIME_MAX =
            LocalDateTime.of(
                    9999, 12, 31,
                    23, 59, 59,
                    999_999_000
            );

    private final UserRepository userRepository;
    private final WorkoutPayloadRepository workoutPayloadRepository;
    private final WorkoutContextCodec context;

    public WorkoutPayloadService(
            UserRepository userRepository,
            WorkoutPayloadRepository workoutPayloadRepository,
            WorkoutContextCodec context
    ) {
        this.userRepository = userRepository;
        this.workoutPayloadRepository = workoutPayloadRepository;
        this.context = context;
    }

    /*
     * 운동 본체와 GPS·심박수를 하나의 트랜잭션으로 저장한다.
     *
     * 처리 중 RuntimeException이 발생하면 이 메서드에서 실행한
     * 모든 INSERT가 롤백된다.
     */
    @Transactional
    public WorkoutPayloadCreateResponse create(
            UUID userId,
            WorkoutPayloadCreateRequest request
    ) {
        validateUserExists(userId);
        context.validate(request);

        UUID workoutPayloadId = UUID.randomUUID();

        WorkoutPayload workoutPayload = new WorkoutPayload(
                workoutPayloadId,
                null,
                userId,
                convertDate(
                        "startDate",
                        request.startDate()
                ),
                convertDate(
                        "endDate",
                        request.endDate()
                ),
                request.totalCalories(),
                request.totalDistance(), context.events(request), context.field(request)
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
         * workoutPayloads를 가장 먼저 저장한다.
         */
        workoutPayloadRepository.saveWorkoutPayload(workoutPayload);
        workoutPayloadRepository.saveTrackPoints(trackPoints);
        workoutPayloadRepository.saveHeartRateSamples(heartRateSamples);

        return new WorkoutPayloadCreateResponse(workoutPayloadId);
    }

    private void validateUserExists(UUID userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    static List<TrackPoint> createTrackPoints(
            UUID workoutPayloadId,
            List<TrackPointRequest> requests
    ) {
        List<TrackPoint> trackPoints =
                new ArrayList<>(requests.size());

        for (
                int sampleSeq = 0;
                sampleSeq < requests.size();
                sampleSeq++
        ) {
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
                            convertDate(
                                    "points[" + sampleSeq + "].timestamp",
                                    request.timestamp()
                            )
                    )
            );
        }

        return trackPoints;
    }

    static List<HeartRateSample> createHeartRateSamples(
            UUID workoutPayloadId,
            List<HeartRateSampleRequest> requests
    ) {
        List<HeartRateSample> heartRateSamples =
                new ArrayList<>(requests.size());

        for (
                int sampleSeq = 0;
                sampleSeq < requests.size();
                sampleSeq++
        ) {
            HeartRateSampleRequest request = requests.get(sampleSeq);

            heartRateSamples.add(
                    new HeartRateSample(
                            workoutPayloadId,
                            sampleSeq,
                            convertDate(
                                    "heartRateSamples["
                                            + sampleSeq
                                            + "].timestamp",
                                    request.timestamp()
                            ),
                            request.bpm()
                    )
            );
        }

        return heartRateSamples;
    }

    /*
     * Apple epoch를 UTC LocalDateTime으로 변환한 후
     * MySQL DATETIME(6)에 저장 가능한 범위인지 검사한다.
     */
    static LocalDateTime convertDate(
            String fieldPath,
            Double appleEpochSeconds
    ) {
        try {
            LocalDateTime convertedDate =
                    AppleEpochTimeConverter.toUtcLocalDateTime(
                            appleEpochSeconds
                    );

            if (convertedDate.isBefore(MYSQL_DATETIME_MIN)
                    || convertedDate.isAfter(MYSQL_DATETIME_MAX)) {
                throw new IllegalArgumentException(
                        "MySQL DATETIME(6) 저장 범위 초과"
                );
            }

            return convertedDate;
        } catch (IllegalArgumentException exception) {
            throw new InvalidWorkoutPayloadException(
                    fieldPath
                            + ": MySQL DATETIME(6)에 저장할 수 없는 날짜입니다.",
                    exception
            );
        }
    }
}