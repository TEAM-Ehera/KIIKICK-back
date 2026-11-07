package com.teamehera.kiikick.user.controller;

import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
import com.teamehera.kiikick.auth.dto.AuthApiErrorResponse;
import com.teamehera.kiikick.auth.dto.AuthApiResponse;
import com.teamehera.kiikick.auth.security.AuthenticatedUser;
import com.teamehera.kiikick.user.dto.ConsentResponse;
import com.teamehera.kiikick.user.service.MyProfileService;
import com.teamehera.kiikick.user.service.UserConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/me/consents")
@Tag(name = "Auth / 동의")
public class UserConsentController {
    private final UserConsentService service;
    private final AuthApiResponseFactory responses;
    public UserConsentController(UserConsentService service, AuthApiResponseFactory responses) {
        this.service = service; this.responses = responses;
    }

    @Operation(operationId = "getMyConsents", summary = "내 동의 목록 조회", description = "Principal의 본인만 조회합니다. GPS_COLLECTION·HEALTH_DATA의 최신 이력을 purpose 오름차순으로 반환합니다. 이력이 없으면 빈 배열이며 미제출 목적의 false 이력을 만들지 않습니다. 저장된 버전·선택·시각을 그대로 표현하며 다른 목적 이력은 보존합니다. Idempotency-Key·If-Match·본문·페이지네이션은 사용하지 않습니다. 철회 및 삭제 연동은 지원하지 않습니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공: data 배열과 meta", useReturnTypeSchema = true, content = @Content(mediaType = "application/json"), headers = @Header(name = "X-Request-Id", schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / TOKEN_EXPIRED: 미인증·만료·미존재·탈퇴 요청/삭제 사용자", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class)), headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string", example = "Bearer"))),
        @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE: DB 조회 실패", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class)))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthApiResponse<List<ConsentResponse>>> get(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor) {
        var body = responses.success(service.get(actor.userId()));
        return ResponseEntity.ok().header("X-Request-Id", body.meta().requestId())
                .cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(MyProfileService.MissingUser.class)
    public ResponseEntity<AuthApiErrorResponse> missing() { return error(401, "UNAUTHENTICATED", false); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<AuthApiErrorResponse> unavailable() { return error(503, "SERVICE_UNAVAILABLE", true); }
    private ResponseEntity<AuthApiErrorResponse> error(int status, String code, boolean retryable) {
        var meta = responses.meta();
        var result = ResponseEntity.status(status).header("X-Request-Id", meta.requestId()).cacheControl(CacheControl.noStore());
        if (status == 401) result.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return result.body(new AuthApiErrorResponse(new AuthApiErrorResponse.AuthApiError(code,
                "동의 목록을 조회할 수 없습니다.", retryable, "NOT_SAVED", Map.of()), meta));
    }
}
