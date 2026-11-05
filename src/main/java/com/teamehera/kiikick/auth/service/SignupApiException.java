package com.teamehera.kiikick.auth.service;

import org.springframework.http.HttpStatus;

public class SignupApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    private SignupApiException(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public static SignupApiException invalidPassword() {
        return new SignupApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "INVALID_PASSWORD",
                "비밀번호 정책을 확인해주세요.",
                false,
                null
        );
    }

    public static SignupApiException signupNotAvailable(Throwable cause) {
        return new SignupApiException(
                HttpStatus.CONFLICT,
                "SIGNUP_NOT_AVAILABLE",
                "회원가입을 완료할 수 없습니다.",
                false,
                cause
        );
    }

    public static SignupApiException tokenIssuanceFailed(Throwable cause) {
        return new SignupApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TOKEN_ISSUANCE_FAILED",
                "로그인 세션을 발급할 수 없습니다. 잠시 후 다시 시도해주세요.",
                true,
                cause
        );
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
}
