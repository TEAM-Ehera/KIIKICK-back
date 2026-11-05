package com.teamehera.kiikick.user.controller;

import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
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

@RestController
@RequestMapping("/api/v1/users/me")
public class MyProfileController {
    private final MyProfileService service;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;
    public MyProfileController(MyProfileService service, AuthApiResponseFactory responses, ObjectMapper json) {
        this.service = service; this.responses = responses; this.json = json;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthApiResponse<UserSelfResponse>> get(@AuthenticationPrincipal AuthenticatedUser actor) {
        var body = responses.success(service.get(actor.userId()));
        return ResponseEntity.ok().header("X-Request-Id", body.meta().requestId()).cacheControl(CacheControl.noStore()).body(body);
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> patch(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
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
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<AuthApiErrorResponse> unavailable() { return error(503, "SERVICE_UNAVAILABLE", true, Map.of()); }
    private ResponseEntity<AuthApiErrorResponse> error(int status, String code, boolean retryable, Map<String, Object> details) {
        var meta = responses.meta();
        var response = ResponseEntity.status(status).header("X-Request-Id", meta.requestId()).cacheControl(CacheControl.noStore());
        if (status == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(new AuthApiErrorResponse(new AuthApiErrorResponse.AuthApiError(code,
                "프로필 요청을 처리할 수 없습니다.", retryable, "NOT_SAVED", details), meta));
    }
}
