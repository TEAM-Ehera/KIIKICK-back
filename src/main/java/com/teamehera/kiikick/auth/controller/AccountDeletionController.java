package com.teamehera.kiikick.auth.controller;

import com.teamehera.kiikick.auth.dto.AuthApiErrorResponse;
import com.teamehera.kiikick.auth.dto.AuthApiMeta;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.teamehera.kiikick.auth.security.AuthenticatedUser;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.user.service.AccountDeletionService;
import com.teamehera.kiikick.user.service.MyProfileService;
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
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(AccountDeletionService.PATH)
@Tag(name = "Auth / 회원 탈퇴")
public class AccountDeletionController {
    private final AccountDeletionService deletion;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;
    private final Clock clock;

    public AccountDeletionController(AccountDeletionService deletion, AuthApiResponseFactory responses,
                                     ObjectMapper json, Clock clock) {
        this.deletion = deletion;
        this.responses = responses;
        this.json = json;
        this.clock = clock;
    }

    @Operation(operationId = "requestAccountDeletion", summary = "회원 탈퇴 요청",
            description = "본인 비밀번호를 재확인하고 계정 접근을 차단합니다. 202는 접수이며 개인정보 파기 완료를 뜻하지 않습니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "탈퇴 접수", content = @Content(
                mediaType = "application/json", schema = @Schema(implementation = ReceiptEnvelope.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_REQUEST", content = @Content(
                mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / TOKEN_EXPIRED / INVALID_CREDENTIALS", content = @Content(
                mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "DELETION_ALREADY_REQUESTED", content = @Content(
                mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class))),
        @ApiResponse(responseCode = "429", description = "RATE_LIMITED", content = @Content(
                mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE", content = @Content(
                mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> request(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor,
                                     @Parameter(required = true, schema = @Schema(type = "string", format = "uuid"))
                                     @RequestHeader("Idempotency-Key") String key,
                                     @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                     @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                                             content = @Content(mediaType = "application/json",
                                                     schema = @Schema(implementation = CurrentPasswordRequest.class)))
                                     @RequestBody byte[] payload) {
        if (ifMatch != null) return invalid();
        JsonNode body;
        try {
            body = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).readTree(payload);
        } catch (JacksonException exception) {
            return invalid();
        }
        if (body == null || !body.isObject() || body.size() != 1
                || !body.path("currentPassword").isString() || body.path("currentPassword").asText().isEmpty()) {
            return invalid();
        }
        UUID id;
        try {
            id = UUID.fromString(key);
            if (!id.toString().equalsIgnoreCase(key)) return invalid();
        } catch (IllegalArgumentException exception) {
            return invalid();
        }
        var saved = deletion.request(actor.userId(), body.path("currentPassword").asText(), id);
        var response = ResponseEntity.status(saved.status()).contentType(MediaType.APPLICATION_JSON);
        json.readTree(saved.headersJson()).properties()
                .forEach(entry -> response.header(entry.getKey(), entry.getValue().asText()));
        return response.body(saved.bodyJson());
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<AuthApiErrorResponse> invalid() {
        return error(400, "INVALID_REQUEST", "탈퇴 요청을 확인해주세요.", false);
    }

    @ExceptionHandler(AccountDeletionService.InvalidPassword.class)
    ResponseEntity<AuthApiErrorResponse> wrongPassword() {
        return error(401, "INVALID_CREDENTIALS", "현재 비밀번호가 올바르지 않습니다.", false);
    }

    @ExceptionHandler(AccountDeletionService.AlreadyRequested.class)
    ResponseEntity<AuthApiErrorResponse> alreadyRequested() {
        return error(409, "DELETION_ALREADY_REQUESTED", "이미 접수된 탈퇴 요청이 있습니다.", false);
    }

    @ExceptionHandler(AccountDeletionService.RateLimited.class)
    ResponseEntity<AuthApiErrorResponse> rateLimited(AccountDeletionService.RateLimited exception) {
        var remaining = Duration.between(clock.instant(), exception.retryAt());
        long seconds = Math.max(1, remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1));
        return error(429, "RATE_LIMITED", "잠시 후 다시 시도해주세요.", true,
                Map.of(HttpHeaders.RETRY_AFTER, Long.toString(seconds)));
    }

    @ExceptionHandler(MyProfileService.MissingUser.class)
    ResponseEntity<AuthApiErrorResponse> missingUser() {
        return error(401, "UNAUTHENTICATED", "인증이 필요합니다.", false,
                Map.of(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @ExceptionHandler(MatchMutation.Rejected.class)
    ResponseEntity<AuthApiErrorResponse> rejected(MatchMutation.Rejected exception) {
        if (exception.status() == 401) return missingUser();
        return error(exception.status(), exception.getMessage(), "탈퇴 요청을 처리할 수 없습니다.",
                "REQUEST_IN_PROGRESS".equals(exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<AuthApiErrorResponse> unavailable() {
        return error(503, "SERVICE_UNAVAILABLE", "탈퇴 요청을 처리할 수 없습니다.", true);
    }

    public record CurrentPasswordRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY,
                    format = "password") String currentPassword) {}

    public record ReceiptEnvelope(AccountDeletionService.Receipt data, AuthApiMeta meta) {}

    private ResponseEntity<AuthApiErrorResponse> error(int status, String code, String message, boolean retryable) {
        return error(status, code, message, retryable, Map.of());
    }

    private ResponseEntity<AuthApiErrorResponse> error(int status, String code, String message,
                                                        boolean retryable, Map<String, String> headers) {
        var meta = responses.meta();
        var response = ResponseEntity.status(status).header("X-Request-Id", meta.requestId())
                .cacheControl(CacheControl.noStore());
        headers.forEach(response::header);
        return response.body(new AuthApiErrorResponse(new AuthApiErrorResponse.AuthApiError(
                code, message, retryable, "NOT_SAVED", Map.of()), meta));
    }
}
