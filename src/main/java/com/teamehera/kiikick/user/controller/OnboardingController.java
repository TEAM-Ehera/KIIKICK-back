package com.teamehera.kiikick.user.controller;

import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
import com.teamehera.kiikick.auth.dto.AuthApiErrorResponse;
import com.teamehera.kiikick.auth.security.AuthenticatedUser;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.user.dto.OnboardingRequest;
import com.teamehera.kiikick.user.dto.UserSelfResponse;
import com.teamehera.kiikick.user.service.MyProfileService;
import com.teamehera.kiikick.user.service.OnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@RestController
@RequestMapping("/api/v1/users/me/onboarding")
@Tag(name = "Auth / 온보딩")
public class OnboardingController {
    private final OnboardingService service;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;
    public OnboardingController(OnboardingService service, AuthApiResponseFactory responses, ObjectMapper json) {
        this.service = service; this.responses = responses; this.json = json;
    }

    @Operation(operationId = "completeOnboarding", summary = "온보딩 완료",
            description = "닉네임 최대 16자·중복 허용. GPS_COLLECTION·HEALTH_DATA / 1.0만 허용하며 모두 선택입니다. 빈 배열·모두 거부도 완료합니다. 미제출 항목은 저장하지 않습니다. 동일 키·원문 요청은 최초 응답을 재생하고 완료 후 새 요청은 409입니다. If-Match는 사용하지 않습니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "온보딩 완료 또는 같은 멱등 요청 재생", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserSelfResponse.OpenApiResponse.class))),
        @ApiResponse(responseCode = "400", description = "INVALID_REQUEST: If-Match 미지원", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "INVALID_STATE / IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "VALIDATION_FAILED: 입력·목적·버전·중복 목적·멱등 키 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE: 저장 실패, 전체 롤백", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class)))
    })
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> complete(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor,
            @Parameter(description = "동일 요청 재전송은 같은 UUID 사용", required = true, schema = @Schema(type = "string", format = "uuid")) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = OnboardingRequest.class), examples = @ExampleObject(value = "{\"nickname\":\"준킥\",\"consents\":[{\"purpose\":\"GPS_COLLECTION\",\"policyVersion\":\"1.0\",\"granted\":true},{\"purpose\":\"HEALTH_DATA\",\"policyVersion\":\"1.0\",\"granted\":false}]}")))
            @RequestBody byte[] body) {
        OnboardingRequest input;
        try { input = json.readValue(body, OnboardingRequest.class); }
        catch (JacksonException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof OnboardingRequest.InvalidInput invalid) throw invalid;
            }
            throw new OnboardingRequest.InvalidInput("body");
        }
        if (input == null) throw new OnboardingRequest.InvalidInput("body");
        UUID id;
        try {
            id = UUID.fromString(key);
            if (key.length() != 36 || !id.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new OnboardingRequest.InvalidInput("Idempotency-Key"); }
        var saved = service.complete(actor.userId(), input, id, ifMatch, body);
        var result = ResponseEntity.status(saved.status());
        // 저장된 JSON을 그대로 재생하여 최초 requestId와 날짜 오프셋을 보존한다.
        json.readTree(saved.headersJson()).properties().forEach(e -> result.header(e.getKey(), e.getValue().asText()));
        return result.contentType(MediaType.APPLICATION_JSON).body(saved.bodyJson());
    }

    @ExceptionHandler({OnboardingRequest.InvalidInput.class, MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<AuthApiErrorResponse> invalid(Exception e) {
        String field = e instanceof OnboardingRequest.InvalidInput i ? i.field() : e instanceof MissingRequestHeaderException ? "Idempotency-Key" : "body";
        return error(422, "VALIDATION_FAILED", false, Map.of("fields", List.of(field)));
    }
    @ExceptionHandler(OnboardingService.AlreadyCompleted.class)
    public ResponseEntity<AuthApiErrorResponse> completed() { return error(409, "INVALID_STATE", false, Map.of()); }
    @ExceptionHandler(MyProfileService.MissingUser.class)
    public ResponseEntity<AuthApiErrorResponse> missing() { return error(401, "UNAUTHENTICATED", false, Map.of()); }
    @ExceptionHandler(MatchMutation.Rejected.class)
    public ResponseEntity<AuthApiErrorResponse> rejected(MatchMutation.Rejected e) {
        return error(e.status(), e.getMessage(), "REQUEST_IN_PROGRESS".equals(e.getMessage()), Map.of());
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<AuthApiErrorResponse> unavailable() { return error(503, "SERVICE_UNAVAILABLE", true, Map.of()); }
    private ResponseEntity<AuthApiErrorResponse> error(int status, String code, boolean retryable, Map<String, Object> details) {
        var meta = responses.meta();
        var result = ResponseEntity.status(status).header("X-Request-Id", meta.requestId()).cacheControl(CacheControl.noStore());
        if (status == 401) result.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return result.body(new AuthApiErrorResponse(new AuthApiErrorResponse.AuthApiError(code,
                "온보딩 요청을 처리할 수 없습니다.", retryable, "NOT_SAVED", details), meta));
    }
}
