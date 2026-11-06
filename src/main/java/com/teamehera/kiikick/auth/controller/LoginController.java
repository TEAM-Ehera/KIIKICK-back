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
import com.teamehera.kiikick.auth.dto.AuthApiErrorResponse;

import com.teamehera.kiikick.auth.dto.AuthApiResponse;
import com.teamehera.kiikick.auth.dto.LoginRequest;
import com.teamehera.kiikick.auth.dto.LoginResponse;
import com.teamehera.kiikick.auth.dto.SignupUserResponse;
import com.teamehera.kiikick.auth.service.LoginResult;
import com.teamehera.kiikick.auth.service.LoginService;
import com.teamehera.kiikick.user.domain.User;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@Tag(name = "Auth / 세션")
@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {
    private final LoginService loginService;
    private final AuthApiResponseFactory responses;

    public LoginController(LoginService loginService, AuthApiResponseFactory responses) {
        this.loginService = loginService;
        this.responses = responses;
    }

    @Operation(operationId = "loginWithEmail", summary = "이메일·비밀번호 로그인", description = "미등록 이메일과 비밀번호 오류는 동일 INVALID_CREDENTIALS. 계정별 15분 실패 10회 시 15분 제한, 성공 시 실패 초기화. CAPTCHA·로그인 IP 제한은 MVP 제외.")
    @SecurityRequirements
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공: data와 meta", useReturnTypeSchema = true, content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"data\":{\"sessionId\":\"00000010-0000-4000-8000-000000000010\",\"accessToken\":\"example-access-token\",\"refreshToken\":\"example-refresh-token\",\"expiresAt\":\"2026-11-06T12:15:00.123456+09:00\",\"user\":{\"id\":\"00000001-0000-4000-8000-000000000001\",\"nickname\":null,\"avatarUrl\":null,\"onboardingStatus\":\"REQUIRED\",\"comparisonConsent\":false,\"createdAt\":\"2026-11-06T12:00:00.123456+09:00\"}},\"meta\":{\"requestId\":\"req-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED / INVALID_REQUEST", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "429", description = "RATE_LIMITED: 계정 로그인 임시 제한", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":true,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")), headers = @Header(name = "Retry-After", description = "재시도 가능 시각이 있는 오류에 제공하는 초 단위 대기", schema = @Schema(type = "integer", format = "int64"))),
        @ApiResponse(responseCode = "503", description = "AUTHENTICATION_UNAVAILABLE", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"AUTHENTICATION_UNAVAILABLE\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":true,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")))
    })
    @PostMapping("/login")
    public ResponseEntity<AuthApiResponse<LoginResponse>> login(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = LoginRequest.class), examples = @ExampleObject(value = "{\"email\":\"player@example.com\",\"password\":\"A-safe-example42!\"}")))
            @Valid @RequestBody LoginRequest request) {
        LoginResult result = loginService.login(request.email(), request.password());
        User user = result.user();
        SignupUserResponse profile = new SignupUserResponse(user.id(), user.nickname(), user.avatarUrl(),
                user.onboardingCompletedAt() == null ? "REQUIRED" : "COMPLETED",
                result.comparisonConsent(), AuthApiResponseFactory.toSeoul(result.userCreatedAt()));
        LoginResponse response = new LoginResponse(result.sessionId(), result.accessToken(), result.refreshToken(),
                AuthApiResponseFactory.toSeoul(result.expiresAt()), profile);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(responses.success(response));
    }
}
