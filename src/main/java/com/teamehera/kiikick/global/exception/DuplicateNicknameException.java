package com.teamehera.kiikick.global.exception;

public final class DuplicateNicknameException extends RuntimeException {
    public static final String CODE = "NICKNAME_ALREADY_EXISTS";
    public static final String MESSAGE = "이미 사용 중인 닉네임입니다.";

    public DuplicateNicknameException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
