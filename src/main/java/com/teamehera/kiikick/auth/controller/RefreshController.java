package com.teamehera.kiikick.auth.controller;

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

import com.teamehera.kiikick.auth.dto.*;
import com.teamehera.kiikick.auth.service.RefreshService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Tag(name = "Auth / 세션")
@RestController
@RequestMapping("/api/v1/auth")
public class RefreshController {
    private final RefreshService service;
    private final AuthApiResponseFactory responses;
    public RefreshController(RefreshService service, AuthApiResponseFactory responses) {
        this.service=service; this.responses=responses;
    }

    @Operation(operationId = "refreshAuthSession", summary = "Refresh Token Rotation", description = "Access Token 인증 불필요. 유효 Refresh를 1회 소비하고 새 토큰 발급. Grace 없음. 소비 토큰 재시도는 해당 세션 폐기·재로그인 필요 가능. iOS는 갱신 요청을 single-flight로 처리해야 함.")
    @SecurityRequirements
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공: data와 meta", useReturnTypeSchema = true, content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"data\":{\"sessionId\":\"00000010-0000-4000-8000-000000000010\",\"accessToken\":\"example-access-token\",\"refreshToken\":\"example-refresh-token\",\"expiresAt\":\"2026-11-06T12:15:00.123456+09:00\",\"user\":{\"id\":\"00000001-0000-4000-8000-000000000001\",\"nickname\":null,\"avatarUrl\":null,\"onboardingStatus\":\"REQUIRED\",\"comparisonConsent\":false,\"createdAt\":\"2026-11-06T12:00:00.123456+09:00\"}},\"meta\":{\"requestId\":\"req-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "401", description = "INVALID_REFRESH_TOKEN: 위조·만료·폐기·재사용. 재사용으로 세션 폐기가 저장되면 persistence=SAVED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"INVALID_REFRESH_TOKEN\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "422", description = "VALIDATION_FAILED: refreshToken 누락·null·타입·본문 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":true,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")))
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthApiResponse<LoginResponse>> refresh(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = RefreshRequest.class), examples = @ExampleObject(value = "{\"refreshToken\":\"example-refresh-token\"}")))
            @Valid @RequestBody RefreshRequest request) {
        var result=service.refresh(request.refreshToken());
        var user=result.user();
        var profile=new SignupUserResponse(user.id(),user.nickname(),user.avatarUrl(),
                user.onboardingCompletedAt()==null ? "REQUIRED" : "COMPLETED",result.comparisonConsent(),
                AuthApiResponseFactory.toSeoul(result.userCreatedAt()));
        var data=new LoginResponse(result.sessionId(),result.accessToken(),result.refreshToken(),
                AuthApiResponseFactory.toSeoul(result.expiresAt()),profile);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(responses.success(data));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<AuthApiErrorResponse> invalidRequest(Exception exception) {
        // AUTH-02의 422 계약을 다른 Auth API의 검증 응답과 분리해 유지한다.
        var error=new AuthApiErrorResponse.AuthApiError("VALIDATION_FAILED","Refresh Token은 필수 문자열입니다.",
                false,"NOT_SAVED",Map.of());
        return ResponseEntity.unprocessableContent().body(new AuthApiErrorResponse(error,responses.meta()));
    }
}
