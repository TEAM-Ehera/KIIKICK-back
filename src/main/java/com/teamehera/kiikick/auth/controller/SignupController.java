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
import com.teamehera.kiikick.auth.dto.SignupRequest;
import com.teamehera.kiikick.auth.dto.SignupResponse;
import com.teamehera.kiikick.auth.dto.SignupUserResponse;
import com.teamehera.kiikick.auth.service.SignupResult;
import com.teamehera.kiikick.auth.service.SignupService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@Tag(name = "Auth / 세션")
@RestController
@RequestMapping("/api/v1/auth")
public class SignupController {

    private final SignupService signupService;
    private final AuthApiResponseFactory responseFactory;

    public SignupController(
            SignupService signupService,
            AuthApiResponseFactory responseFactory
    ) {
        this.signupService = signupService;
        this.responseFactory = responseFactory;
    }

    @Operation(operationId = "signupWithEmail", summary = "이메일 회원가입 완료", description = "registrationToken 검증·소비와 사용자·Credential·Session 생성을 원자 처리. 15분 Access/최초 발급 기준 최대 90일 Refresh 발급. 신규 사용자는 REQUIRED.")
    @SecurityRequirements
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "성공: data와 meta", useReturnTypeSchema = true, content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"data\":{\"sessionId\":\"00000010-0000-4000-8000-000000000010\",\"accessToken\":\"example-access-token\",\"refreshToken\":\"example-refresh-token\",\"expiresAt\":\"2026-11-06T12:15:00.123456+09:00\",\"user\":{\"id\":\"00000001-0000-4000-8000-000000000001\",\"nickname\":null,\"avatarUrl\":null,\"onboardingStatus\":\"REQUIRED\",\"comparisonConsent\":false,\"createdAt\":\"2026-11-06T12:00:00.123456+09:00\"}},\"meta\":{\"requestId\":\"req-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED / INVALID_REQUEST / INVALID_REGISTRATION_TOKEN", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "409", description = "SIGNUP_NOT_AVAILABLE: 중복 가입·가입 충돌", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"SIGNUP_NOT_AVAILABLE\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "422", description = "INVALID_PASSWORD: 길이·UTF-8 바이트·로컬 차단 목록 위반", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"INVALID_PASSWORD\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "503", description = "TOKEN_ISSUANCE_FAILED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"TOKEN_ISSUANCE_FAILED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":true,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")))
    })
    @PostMapping("/signup")
    public ResponseEntity<AuthApiResponse<SignupResponse>> signup(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = SignupRequest.class), examples = @ExampleObject(value = "{\"registrationToken\":\"example-registration-token\",\"password\":\"A-safe-example42!\"}")))
            @Valid @RequestBody SignupRequest request
    ) {
        SignupResult result = signupService.signup(
                request.registrationToken(), request.password()
        );
        SignupUserResponse user = new SignupUserResponse(
                result.userId(),
                null,
                null,
                "REQUIRED",
                false,
                AuthApiResponseFactory.toSeoul(result.userCreatedAt())
        );
        SignupResponse response = new SignupResponse(
                result.sessionId(),
                result.accessToken(),
                result.refreshToken(),
                AuthApiResponseFactory.toSeoul(result.accessTokenExpiresAt()),
                user
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.success(response));
    }
}
