package com.teamehera.kiikick.user.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.*;

@Schema(description = "닉네임과 선택 동의. consents는 필수이며 빈 배열 가능")
public record OnboardingRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 16, description = "활성 계정 간 중복 불가. 삭제된 계정 닉네임 재사용 가능") String nickname,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Consent> consents) {
    public record Consent(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"GPS_COLLECTION", "HEALTH_DATA"}) String purpose,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "1.0") String policyVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean granted) {}

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static OnboardingRequest from(Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("nickname", "consents"))) throw new InvalidInput("body");
        if (!(body.get("nickname") instanceof String nickname) || nickname.isBlank() || nickname.length() > 16)
            throw new InvalidInput("nickname");
        if (!(body.get("consents") instanceof List<?> submitted)) throw new InvalidInput("consents");
        List<Consent> consents = new ArrayList<>();
        Set<String> purposes = new HashSet<>();
        for (int i = 0; i < submitted.size(); i++) {
            String field = "consents[" + i + "]";
            if (!(submitted.get(i) instanceof Map<?, ?> item)
                    || !item.keySet().equals(Set.of("purpose", "policyVersion", "granted"))) throw new InvalidInput(field);
            if (!(item.get("purpose") instanceof String purpose)
                    || !Set.of("GPS_COLLECTION", "HEALTH_DATA").contains(purpose)) throw new InvalidInput(field + ".purpose");
            // 같은 목적의 상반된 선택을 한 요청에 저장하지 않도록 중복 입력을 거부한다.
            if (!purposes.add(purpose)) throw new InvalidInput(field + ".purpose");
            if (!"1.0".equals(item.get("policyVersion"))) throw new InvalidInput(field + ".policyVersion");
            if (!(item.get("granted") instanceof Boolean granted)) throw new InvalidInput(field + ".granted");
            consents.add(new Consent(purpose, "1.0", granted));
        }
        return new OnboardingRequest(nickname, List.copyOf(consents));
    }

    public static final class InvalidInput extends RuntimeException {
        private final String field;
        public InvalidInput(String field) { super("Invalid onboarding request"); this.field = field; }
        public String field() { return field; }
    }
}
