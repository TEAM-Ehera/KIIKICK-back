package com.teamehera.kiikick.user.avatar;

import java.util.UUID;

public interface AvatarStorage {
    String upload(UUID owner, byte[] jpeg);

    boolean manages(String url);

    void delete(UUID owner, String url);

    final class Unavailable extends RuntimeException {
        public Unavailable() {
            super("Avatar storage is unavailable");
        }

        public Unavailable(Throwable cause) {
            super("Avatar storage is unavailable", cause);
        }
    }
}
