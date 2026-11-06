package com.teamehera.kiikick.auth.controller;

import com.teamehera.kiikick.auth.dto.AuthApiErrorResponse;
import com.teamehera.kiikick.auth.dto.AuthApiMeta;
import com.teamehera.kiikick.auth.service.VerificationApiException;
import com.teamehera.kiikick.auth.service.SignupApiException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.teamehera.kiikick.auth.controller")
public class EmailVerificationExceptionHandler {

    private static final Clock CLOCK = Clock.systemUTC();

    @ExceptionHandler(VerificationApiException.class)
    public ResponseEntity<AuthApiErrorResponse> handleVerification(
            VerificationApiException exception
    ) {
        HttpHeaders headers = new HttpHeaders();
        if (exception.retryAt() != null) {
            long seconds = Math.max(1, Duration.between(
                    CLOCK.instant(),
                    exception.retryAt()
            ).toSeconds());
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }
        return new ResponseEntity<>(body(
                exception.code(), exception.getMessage(),
                exception.retryable(), exception.persistence()
        ), headers, exception.status());
    }

    @ExceptionHandler(SignupApiException.class)
    public ResponseEntity<AuthApiErrorResponse> handleSignup(
            SignupApiException exception
    ) {
        return ResponseEntity.status(exception.status()).body(body(
                exception.code(), exception.getMessage(),
                exception.retryable(), "NOT_SAVED"
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AuthApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("잘못된 요청입니다.");
        return ResponseEntity.badRequest().body(body(
                "VALIDATION_FAILED", message, false, "NOT_SAVED"
        ));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<AuthApiErrorResponse> handleMalformedRequest(Exception exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(
                "INVALID_REQUEST", "잘못된 요청입니다.", false, "NOT_SAVED"
        ));
    }

    private AuthApiErrorResponse body(
            String code,
            String message,
            boolean retryable,
            String persistence
    ) {
        return new AuthApiErrorResponse(
                new AuthApiErrorResponse.AuthApiError(
                        code, message, retryable, persistence, Map.of()
                ),
                new AuthApiMeta(
                        "req-" + UUID.randomUUID(),
                        AuthApiResponseFactory.toSeoul(CLOCK.instant())
                )
        );
    }
}
