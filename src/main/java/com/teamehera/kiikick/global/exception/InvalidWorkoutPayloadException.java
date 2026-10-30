package com.teamehera.kiikick.global.exception;

/*
 * 처리할 수 없는 WorkoutPayload 값이 들어왔을 때 사용하는 예외다.
 * GlobalExceptionHandler가 이 예외를 HTTP 400으로 변환한다.
 */
public class InvalidWorkoutPayloadException
        extends RuntimeException {

    public InvalidWorkoutPayloadException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}