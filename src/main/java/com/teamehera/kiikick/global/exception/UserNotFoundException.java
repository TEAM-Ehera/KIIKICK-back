package com.teamehera.kiikick.global.exception;

import java.util.UUID;

/*
 * 요청한 사용자가 없거나 이미 삭제된 경우 사용하는 예외.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID id) {
        super("사용자를 찾을 수 없습니다. id=" + id);
    }
}
