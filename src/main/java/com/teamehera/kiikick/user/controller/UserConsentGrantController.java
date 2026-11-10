package com.teamehera.kiikick.user.controller;

import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
import com.teamehera.kiikick.auth.domain.ConsentPolicy;
import com.teamehera.kiikick.auth.dto.AuthApiErrorResponse;
import com.teamehera.kiikick.auth.security.AuthenticatedUser;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.user.service.MyProfileService;
import com.teamehera.kiikick.user.service.UserConsentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/consents")
// TODO(#68): 업로드 차단·파기·재계산 연동을 검증한 뒤 운영 활성화를 검토한다.
@ConditionalOnProperty(name = "kiikick.auth.consent-grant-test-enabled", havingValue = "true")
public class UserConsentGrantController {
    private final UserConsentService service;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;

    public UserConsentGrantController(UserConsentService service, AuthApiResponseFactory responses, ObjectMapper json) {
        this.service = service;
        this.responses = responses;
        this.json = json;
    }

    @PutMapping(path = "/{purpose}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> change(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable String purpose,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body) {
        if (ifMatch != null) return malformed(HttpHeaders.IF_MATCH);
        if (!ConsentPolicy.supportsPurpose(purpose)) return invalid("purpose");
        JsonNode input;
        try {
            input = json.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).readTree(body);
        } catch (JacksonException e) {
            return malformed("body");
        }
        if (input == null || !input.isObject() || input.size() != 2) return invalid("body");
        var version = input.path("policyVersion");
        if (!version.isString() || !ConsentPolicy.supports(purpose, version.asText())) return invalid("policyVersion");
        var granted = input.path("granted");
        if (!granted.isBoolean()) return invalid("granted");
        UUID id;
        try {
            id = UUID.fromString(key);
            if (!id.toString().equalsIgnoreCase(key)) return malformed("Idempotency-Key");
        } catch (IllegalArgumentException e) {
            return malformed("Idempotency-Key");
        }
        var saved = service.change(actor.userId(), purpose, version.asText(), granted.asBoolean(), id, ifMatch, body);
        var result = ResponseEntity.status(saved.status());
        json.readTree(saved.headersJson()).properties()
                .forEach(entry -> result.header(entry.getKey(), entry.getValue().asText()));
        return result.contentType(MediaType.APPLICATION_JSON).body(saved.bodyJson());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<AuthApiErrorResponse> missingHeader() {
        return malformed("Idempotency-Key");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AuthApiErrorResponse> unreadable() {
        return malformed("body");
    }

    @ExceptionHandler(MyProfileService.MissingUser.class)
    public ResponseEntity<AuthApiErrorResponse> missingUser() {
        return error(401, "UNAUTHENTICATED", "인증이 필요합니다.", false, Map.of());
    }

    @ExceptionHandler(MatchMutation.Rejected.class)
    public ResponseEntity<AuthApiErrorResponse> rejected(MatchMutation.Rejected e) {
        return error(e.status(), e.getMessage(), "동의 변경 요청을 처리할 수 없습니다.",
                "REQUEST_IN_PROGRESS".equals(e.getMessage()), Map.of());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<AuthApiErrorResponse> unavailable() {
        return error(503, "SERVICE_UNAVAILABLE", "동의 변경 요청을 처리할 수 없습니다.", true, Map.of());
    }

    private ResponseEntity<AuthApiErrorResponse> malformed(String field) {
        return error(400, "INVALID_REQUEST", "동의 변경 요청 형식을 확인해주세요.", false,
                Map.of("fields", List.of(field)));
    }

    private ResponseEntity<AuthApiErrorResponse> invalid(String field) {
        return error(422, "VALIDATION_FAILED", "동의 변경 요청을 확인해주세요.", false,
                Map.of("fields", List.of(field)));
    }

    private ResponseEntity<AuthApiErrorResponse> error(int status, String code, String message,
                                                        boolean retryable, Map<String, Object> details) {
        var meta = responses.meta();
        var result = ResponseEntity.status(status).header("X-Request-Id", meta.requestId())
                .cacheControl(CacheControl.noStore());
        if (status == 401) result.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return result.body(new AuthApiErrorResponse(new AuthApiErrorResponse.AuthApiError(
                code, message, retryable, "NOT_SAVED", details), meta));
    }
}
