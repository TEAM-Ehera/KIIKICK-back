package com.teamehera.kiikick.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
import com.teamehera.kiikick.global.exception.DuplicateNicknameException;
import com.teamehera.kiikick.auth.dto.AuthApiErrorResponse;
import com.teamehera.kiikick.auth.dto.AuthApiResponse;
import com.teamehera.kiikick.auth.security.AuthenticatedUser;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.user.dto.ProfilePatchRequest;
import com.teamehera.kiikick.user.dto.UserSelfResponse;
import com.teamehera.kiikick.user.service.MyProfileService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Tag(name = "Auth / 내 프로필")
@RestController
@RequestMapping("/api/v1/users/me")
public class MyProfileController {
    private final MyProfileService service;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;
    public MyProfileController(MyProfileService service, AuthApiResponseFactory responses, ObjectMapper json) {
        this.service = service; this.responses = responses; this.json = json;
    }

    @Operation(operationId = "getMyProfile", summary = "내 프로필 조회", description = "검증된 Principal의 본인 UUID만 사용. 온보딩과 비교 동의는 DB에서 파생. Idempotency-Key/If-Match 사용 안 함.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공: data와 meta", useReturnTypeSchema = true, content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"data\":{\"id\":\"00000001-0000-4000-8000-000000000001\",\"nickname\":null,\"avatarUrl\":null,\"onboardingStatus\":\"REQUIRED\",\"comparisonConsent\":false,\"createdAt\":\"2026-11-06T12:00:00.123456+09:00\"},\"meta\":{\"requestId\":\"req-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")), headers = @Header(name = "X-Request-Id", schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / TOKEN_EXPIRED: JWT 오류 또는 사용자 없음·삭제", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")), headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string", example = "Bearer"))),
        @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":true,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthApiResponse<UserSelfResponse>> get(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor) {
        var body = responses.success(service.get(actor.userId()));
        return ResponseEntity.ok().header("X-Request-Id", body.meta().requestId()).cacheControl(CacheControl.noStore()).body(body);
    }

    @Operation(operationId = "patchMyProfile", summary = "내 프로필 수정", description = "최소 한 필드 필요. 누락은 유지, nickname null 거부, avatarUrl null은 제거. 닉네임 Java UTF-16 최대 16자·활성 계정 간 중복 불가, 삭제된 계정의 닉네임 재사용 가능. 읽기 전용 필드 거부. 동일 키·원문 요청은 최초 JSON/meta 재생. If-Match 사용 안 함. 멱등 보존 1일은 임시 설정.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공: data와 meta", content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserSelfResponse.OpenApiResponse.class), examples = @ExampleObject(value = "{\"data\":{\"id\":\"00000001-0000-4000-8000-000000000001\",\"nickname\":\"준킥\",\"avatarUrl\":null,\"onboardingStatus\":\"REQUIRED\",\"comparisonConsent\":false,\"createdAt\":\"2026-11-06T12:00:00.123456+09:00\"},\"meta\":{\"requestId\":\"req-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")), headers = @Header(name = "X-Request-Id", schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 신규 요청의 If-Match 미지원", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")), headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string", example = "Bearer"))),
        @ApiResponse(responseCode = "409", description = "NICKNAME_ALREADY_EXISTS / IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"IDEMPOTENCY_KEY_REUSED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "415", description = "application/json 필요", content = @Content),
        @ApiResponse(responseCode = "422", description = "VALIDATION_FAILED: 필드·누락/null·길이·멱등 키 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE: 프로필·멱등 저장 롤백", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":true,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")))
    })
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> patch(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor,
            @Parameter(description = "같은 동작 재전송에는 동일 UUID 유지. 다른 요청에 재사용하면 409", required = true, schema = @Schema(type = "string", format = "uuid", example = "00000016-0000-4000-8000-000000000016")) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProfilePatchRequest.class), examples = @ExampleObject(value = "{\"nickname\":\"준킥\",\"avatarUrl\":null}")))
            @RequestBody byte[] body) {
        ProfilePatchRequest patch;
        try { patch = json.readValue(body, ProfilePatchRequest.class); }
        catch (JacksonException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof ProfilePatchRequest.InvalidInput input) throw input;
            }
            throw new ProfilePatchRequest.InvalidInput("body");
        }
        if (patch == null) throw new ProfilePatchRequest.InvalidInput("body");
        UUID id;
        try {
            id = UUID.fromString(key);
            if (key.length() != 36 || !id.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new ProfilePatchRequest.InvalidInput("Idempotency-Key"); }
        var saved = service.patch(actor.userId(), patch, id, ifMatch, body);
        var result = ResponseEntity.status(saved.status());
        json.readTree(saved.headersJson()).properties().forEach(e -> result.header(e.getKey(), e.getValue().asText()));
        // 재역직렬화로 날짜 오프셋을 바꾸지 않고 저장한 최초 JSON 응답을 재생한다.
        return result.contentType(MediaType.APPLICATION_JSON).body(saved.bodyJson());
    }

    @ExceptionHandler({ProfilePatchRequest.InvalidInput.class, MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<AuthApiErrorResponse> invalid(Exception e) {
        String field = e instanceof ProfilePatchRequest.InvalidInput i ? i.field() : e instanceof MissingRequestHeaderException ? "Idempotency-Key" : "body";
        return error(422, "VALIDATION_FAILED", false, Map.of("fields", List.of(field)));
    }
    @ExceptionHandler(MyProfileService.MissingUser.class)
    public ResponseEntity<AuthApiErrorResponse> missing() { return error(401, "UNAUTHENTICATED", false, Map.of()); }
    @ExceptionHandler(MatchMutation.Rejected.class)
    public ResponseEntity<AuthApiErrorResponse> rejected(MatchMutation.Rejected e) {
        return error(e.status(), e.getMessage(), "REQUEST_IN_PROGRESS".equals(e.getMessage()), Map.of());
    }
    @ExceptionHandler(DuplicateNicknameException.class)
    public ResponseEntity<AuthApiErrorResponse> duplicateNickname() {
        return error(409, DuplicateNicknameException.CODE, false, Map.of("fields", List.of("nickname")));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<AuthApiErrorResponse> unavailable() { return error(503, "SERVICE_UNAVAILABLE", true, Map.of()); }
    private ResponseEntity<AuthApiErrorResponse> error(int status, String code, boolean retryable, Map<String, Object> details) {
        var meta = responses.meta();
        var response = ResponseEntity.status(status).header("X-Request-Id", meta.requestId()).cacheControl(CacheControl.noStore());
        if (status == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(new AuthApiErrorResponse(new AuthApiErrorResponse.AuthApiError(code,
                DuplicateNicknameException.CODE.equals(code) ? DuplicateNicknameException.MESSAGE : "프로필 요청을 처리할 수 없습니다.", retryable, "NOT_SAVED", details), meta));
    }
}
