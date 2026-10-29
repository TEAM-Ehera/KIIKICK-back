package com.teamehera.kiikick.workout.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

import com.teamehera.kiikick.workout.dto.HeartRateSampleRequest;
import com.teamehera.kiikick.workout.dto.TrackPointRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.repository.HeartRateSampleRepository;
import com.teamehera.kiikick.workout.repository.TrackPointRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class WorkoutPayloadTransactionTest {

    @Autowired
    private WorkoutPayloadService workoutPayloadService;

    @Autowired
    private WorkoutPayloadRepository workoutPayloadRepository;

    @Autowired
    private TrackPointRepository trackPointRepository;

    @MockitoBean
    private HeartRateSampleRepository heartRateSampleRepository;

    @BeforeEach
    void cleanDatabase() {
        trackPointRepository.deleteAll();
        workoutPayloadRepository.deleteAll();
    }

    @Test
    void rollsBackPayloadAndTrackPointsWhenHeartRateSaveFails() {
        doThrow(new DataIntegrityViolationException("forced failure"))
                .when(heartRateSampleRepository)
                .saveAllAndFlush(anyList());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> workoutPayloadService.create(request())
        );

        assertEquals(0, workoutPayloadRepository.count());
        assertEquals(0, trackPointRepository.count());
    }

    private WorkoutPayloadCreateRequest request() {
        return new WorkoutPayloadCreateRequest(
                750000000.0,
                750000600.0,
                420.5,
                3182.4,
                List.of(new TrackPointRequest(
                        37.5665,
                        126.978,
                        20.1,
                        4.8,
                        -1.0,
                        -1.0,
                        750000001.0
                )),
                List.of(new HeartRateSampleRequest(750000001.0, 142.0))
        );
    }
}
