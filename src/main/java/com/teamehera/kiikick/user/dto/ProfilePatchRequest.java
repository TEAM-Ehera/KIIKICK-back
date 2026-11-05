package com.teamehera.kiikick.user.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;
import java.util.Set;

public record ProfilePatchRequest(
        String nickname,
        String avatarUrl,
        boolean nicknamePresent,
        boolean avatarPresent
) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ProfilePatchRequest from(Map<String, Object> body) {
        if (body == null || body.isEmpty() || !Set.of("nickname", "avatarUrl").containsAll(body.keySet()))
            throw new InvalidInput("body");
        boolean hasNickname = body.containsKey("nickname"), hasAvatar = body.containsKey("avatarUrl");
        String nickname = null, avatar = null;
        if (hasNickname) {
            if (!(body.get("nickname") instanceof String value) || value.isBlank() || value.length() > 16)
                throw new InvalidInput("nickname");
            nickname = value;
        }
        if (hasAvatar && body.get("avatarUrl") != null) {
            if (!(body.get("avatarUrl") instanceof String value)) throw new InvalidInput("avatarUrl");
            avatar = value;
        }
        // null 값만으로는 누락 여부를 알 수 없으므로 제출된 필드를 별도로 보존한다.
        return new ProfilePatchRequest(nickname, avatar, hasNickname, hasAvatar);
    }

    public static final class InvalidInput extends RuntimeException {
        private final String field;
        public InvalidInput(String field) { super("Invalid profile request"); this.field = field; }
        public String field() { return field; }
    }
}
