package com.teamehera.kiikick.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;
import java.util.Set;

@Schema(description = "최소 한 필드 필요. 누락은 유지, nickname null은 거부, avatarUrl null은 제거", additionalProperties = Schema.AdditionalPropertiesValue.FALSE, minProperties = 1)
public record ProfilePatchRequest(
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = false, minLength = 1, maxLength = 16, description = "기존 CRUD와 동일한 Java UTF-16 길이 최대 16자. 공백만 입력 불가, 중복 허용, 자동 trim 없음", example = "준킥") String nickname,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, description = "누락은 기존 값 유지, null은 이미지 제거. 서버는 이 URL에 접속하지 않음", example = "https://example.com/avatar.png") String avatarUrl,
        @Schema(hidden = true) boolean nicknamePresent,
        @Schema(hidden = true) boolean avatarPresent
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
