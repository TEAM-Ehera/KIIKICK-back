package com.teamehera.kiikick.auth.service;

import org.springframework.http.HttpStatus;

import java.time.Instant;

public class VerificationApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final boolean retryable;
    private final String persistence;
    private final Instant retryAt;

    private VerificationApiException(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            String persistence,
            Instant retryAt,
            Throwable cause
    ) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.persistence = persistence;
        this.retryAt = retryAt;
    }

    public static VerificationApiException invalidRequest(String message) {
        return new VerificationApiException(
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message,
                false, "NOT_SAVED", null, null
        );
    }

    public static VerificationApiException rateLimited(Instant retryAt) {
        return new VerificationApiException(
                HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "잠시 후 다시 시도해주세요.", true, "NOT_SAVED", retryAt, null
        );
    }

    public static VerificationApiException invalidVerification() {
        return new VerificationApiException(
                HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION",
                "인증번호가 올바르지 않거나 더 이상 사용할 수 없습니다.",
                false, "NOT_SAVED", null, null
        );
    }

    public static VerificationApiException expiredVerification() {
        return new VerificationApiException(
                HttpStatus.BAD_REQUEST, "VERIFICATION_EXPIRED",
                "인증번호가 만료되었습니다. 새 인증번호를 요청해주세요.",
                false, "NOT_SAVED", null, null
        );
    }

    public static VerificationApiException attemptsExceeded(Instant retryAt) {
        return new VerificationApiException(
                HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_ATTEMPTS_EXCEEDED",
                "인증 시도 횟수를 초과했습니다.", true, "NOT_SAVED", retryAt, null
        );
    }

    public static VerificationApiException emailDeliveryUnavailable(Throwable cause) {
        return new VerificationApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE",
                "인증 메일을 전송할 수 없습니다. 잠시 후 다시 시도해주세요.",
                true, "NOT_SAVED", null, cause
        );
    }

    public static VerificationApiException invalidRegistrationToken() {
        return new VerificationApiException(
                HttpStatus.BAD_REQUEST, "INVALID_REGISTRATION_TOKEN",
                "회원가입 인증 정보가 올바르지 않거나 만료되었습니다.",
                false, "NOT_SAVED", null, null
        );
    }

    public static VerificationApiException invalidCredentials() {
        return new VerificationApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "이메일 또는 비밀번호가 올바르지 않습니다.", false, "NOT_SAVED", null, null);
    }

    public static VerificationApiException authenticationUnavailable() {
        return new VerificationApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTHENTICATION_UNAVAILABLE",
                "로그인할 수 없습니다. 잠시 후 다시 시도해주세요.", true, "NOT_SAVED", null, null);
    }

    public static VerificationApiException invalidRefreshToken(boolean revoked) {
        return new VerificationApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "만료·폐기·재사용된 토큰", false, revoked ? "SAVED" : "NOT_SAVED", null, null);
    }

    public static VerificationApiException refreshUnavailable() {
        return new VerificationApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "세션을 갱신할 수 없습니다. 잠시 후 다시 시도해주세요.", true, "NOT_SAVED", null, null);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public String persistence() {
        return persistence;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
