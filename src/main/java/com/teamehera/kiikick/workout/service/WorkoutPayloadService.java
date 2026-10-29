package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.common.exception.ApiException;
import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.dto.HeartRateSampleRequest;
import com.teamehera.kiikick.workout.dto.TrackPointRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateResponse;
import com.teamehera.kiikick.workout.repository.HeartRateSampleRepository;
import com.teamehera.kiikick.workout.repository.TrackPointRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkoutPayloadService {

    private final WorkoutPayloadRepository workoutPayloadRepository;
    private final TrackPointRepository trackPointRepository;
    private final HeartRateSampleRepository heartRateSampleRepository;

    public WorkoutPayloadService(
            WorkoutPayloadRepository workoutPayloadRepository,
            TrackPointRepository trackPointRepository,
            HeartRateSampleRepository heartRateSampleRepository
    ) {
        this.workoutPayloadRepository = workoutPayloadRepository;
        this.trackPointRepository = trackPointRepository;
        this.heartRateSampleRepository = heartRateSampleRepository;
    }

    @Transactional
    public WorkoutPayloadCreateResponse create(WorkoutPayloadCreateRequest request) {
        WorkoutPayload workoutPayload = workoutPayloadRepository.saveAndFlush(new WorkoutPayload(
                convertDate(request.startDate()),
                convertDate(request.endDate()),
                request.totalCalories(),
                request.totalDistance()
        ));

        List<TrackPoint> trackPoints = createTrackPoints(workoutPayload, request.points());
        List<HeartRateSample> heartRateSamples = createHeartRateSamples(
                workoutPayload,
                request.heartRateSamples()
        );
        trackPointRepository.saveAllAndFlush(trackPoints);
        heartRateSampleRepository.saveAllAndFlush(heartRateSamples);

        return new WorkoutPayloadCreateResponse(workoutPayload.getId());
    }

    private List<TrackPoint> createTrackPoints(
            WorkoutPayload workoutPayload,
            List<TrackPointRequest> requests
    ) {
        List<TrackPoint> trackPoints = new ArrayList<>(requests.size());
        for (int sampleSeq = 0; sampleSeq < requests.size(); sampleSeq++) {
            TrackPointRequest request = requests.get(sampleSeq);
            trackPoints.add(new TrackPoint(
                    workoutPayload,
                    sampleSeq,
                    request.latitude(),
                    request.longitude(),
                    request.altitude(),
                    request.horizontalAccuracy(),
                    request.speed(),
                    request.course(),
                    convertDate(request.timestamp())
            ));
        }
        return trackPoints;
    }

    private List<HeartRateSample> createHeartRateSamples(
            WorkoutPayload workoutPayload,
            List<HeartRateSampleRequest> requests
    ) {
        List<HeartRateSample> heartRateSamples = new ArrayList<>(requests.size());
        for (int sampleSeq = 0; sampleSeq < requests.size(); sampleSeq++) {
            HeartRateSampleRequest request = requests.get(sampleSeq);
            heartRateSamples.add(new HeartRateSample(
                    workoutPayload,
                    sampleSeq,
                    convertDate(request.timestamp()),
                    request.bpm()
            ));
        }
        return heartRateSamples;
    }

    private LocalDateTime convertDate(Double appleEpochSeconds) {
        try {
            return AppleEpochTimeConverter.toUtcLocalDateTime(appleEpochSeconds);
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("Apple epoch 날짜가 지원 범위를 벗어났습니다.");
        }
    }
}
