package com.teamehera.kiikick.global.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 사용자가 존재하지 않거나 이미 삭제된 경우 HTTP 404를 반환한다.
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(
            UserNotFoundException exception
    ) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "message",
                        exception.getMessage()
                ));
    }

    // @Valid 요청 검증이 실패한 경우 HTTP 400을 반환한다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("잘못된 요청입니다.");

        return ResponseEntity
                .badRequest()
                .body(Map.of("message", message));
    }

    /*
     * Apple epoch 변환처럼 WorkoutPayload 값 자체가 잘못된 경우
     * 서버 오류 500이 아닌 요청 오류 400을 반환한다.
     */
    @ExceptionHandler(InvalidWorkoutPayloadException.class)
    public ResponseEntity<Map<String, String>> handleInvalidWorkoutPayload(
            InvalidWorkoutPayloadException exception
    ) {
        return ResponseEntity
                .badRequest()
                .body(Map.of(
                        "message",
                        exception.getMessage()
                ));
    }
}