package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.global.exception.UserNotFoundException;
import com.teamehera.kiikick.user.domain.User;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.dto.HeartRateSampleRequest;
import com.teamehera.kiikick.workout.dto.TrackPointRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateResponse;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * DB 없이 WorkoutPayloadService의 처리 순서와 변환 결과를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class WorkoutPayloadServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WorkoutPayloadRepository workoutPayloadRepository;

    @InjectMocks
    private WorkoutPayloadService workoutPayloadService;

    @Test
    void 사용자와_운동데이터가_유효하면_전체_데이터를_저장한다() {
        UUID userId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        User user = new User(
                userId,
                "테스트사용자",
                now,
                now,
                null
        );

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        WorkoutPayloadCreateRequest request =
                new WorkoutPayloadCreateRequest(
                        0.0,
                        60.0,
                        420.5,
                        3182.4,
                        List.of(
                                new TrackPointRequest(
                                        37.5665,
                                        126.9780,
                                        20.1,
                                        4.8,
                                        2.3,
                                        87.5,
                                        1.0
                                )
                        ),
                        List.of(
                                new HeartRateSampleRequest(
                                        1.0,
                                        142.0
                                )
                        )
                );

        WorkoutPayloadCreateResponse response =
                workoutPayloadService.create(userId, request);

        ArgumentCaptor<WorkoutPayload> workoutCaptor =
                ArgumentCaptor.forClass(WorkoutPayload.class);

        /*
         * 부모 운동이 GPS와 심박수보다 먼저 저장되는지 확인한다.
         */
        InOrder saveOrder = inOrder(workoutPayloadRepository);

        saveOrder.verify(workoutPayloadRepository)
                .saveWorkoutPayload(workoutCaptor.capture());

        WorkoutPayload savedWorkout = workoutCaptor.getValue();

        saveOrder.verify(workoutPayloadRepository)
                .saveTrackPoints(
                        argThat(points ->
                                points.size() == 1
                                        && points.get(0).sampleSeq() == 0
                                        && points.get(0)
                                        .workoutPayloadId()
                                        .equals(savedWorkout.id())
                        )
                );

        saveOrder.verify(workoutPayloadRepository)
                .saveHeartRateSamples(
                        argThat(samples ->
                                samples.size() == 1
                                        && samples.get(0).sampleSeq() == 0
                                        && samples.get(0)
                                        .workoutPayloadId()
                                        .equals(savedWorkout.id())
                        )
                );

        assertAll(
                () -> assertEquals(
                        userId,
                        savedWorkout.userId()
                ),
                () -> assertEquals(
                        LocalDateTime.of(2001, 1, 1, 0, 0),
                        savedWorkout.startDate()
                ),
                () -> assertEquals(
                        LocalDateTime.of(2001, 1, 1, 0, 1),
                        savedWorkout.endDate()
                ),
                () -> assertEquals(
                        savedWorkout.id(),
                        response.workoutPayloadId()
                )
        );
    }

    @Test
    void 사용자가_없으면_저장하지_않고_404용_예외를_발생시킨다() {
        UUID userId = UUID.randomUUID();

        when(userRepository.findById(userId))
                .thenReturn(Optional.empty());

        WorkoutPayloadCreateRequest request =
                new WorkoutPayloadCreateRequest(
                        0.0,
                        60.0,
                        420.5,
                        3182.4,
                        List.of(),
                        List.of()
                );

        assertThrows(
                UserNotFoundException.class,
                () -> workoutPayloadService.create(userId, request)
        );

        verify(workoutPayloadRepository, never())
                .saveWorkoutPayload(
                        org.mockito.ArgumentMatchers.any()
                );
    }
}
