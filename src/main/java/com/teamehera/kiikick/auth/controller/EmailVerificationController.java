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
import com.teamehera.kiikick.auth.dto.EmailVerificationCodeRequest;
import com.teamehera.kiikick.auth.dto.EmailVerificationRequest;
import com.teamehera.kiikick.auth.dto.EmailVerificationResponse;
import com.teamehera.kiikick.auth.dto.EmailVerificationSuccessResponse;
import com.teamehera.kiikick.auth.service.EmailVerificationRequestResult;
import com.teamehera.kiikick.auth.service.EmailVerificationService;
import com.teamehera.kiikick.auth.service.EmailVerificationSuccessResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Auth / 이메일 인증")
@RestController
@RequestMapping("/api/v1/auth/email-verifications")
public class EmailVerificationController {

    private final EmailVerificationService verificationService;
    private final AuthApiResponseFactory responseFactory;

    public EmailVerificationController(
            EmailVerificationService verificationService,
            AuthApiResponseFactory responseFactory
    ) {
        this.verificationService = verificationService;
        this.responseFactory = responseFactory;
    }

    @Operation(operationId = "requestEmailVerification", summary = "이메일 인증번호 발송·재발송", description = "SIGN_UP 인증코드 6자리·3분, 마지막 발송 후 60초 재발송 대기. 이메일 24시간 5회·IP 1분 3회 독립 제한. 실제 이메일이 발송되므로 테스트 주소와 환경을 확인하세요.")
    @SecurityRequirements
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "성공: data와 meta", useReturnTypeSchema = true, content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"data\":{\"verificationId\":\"00000011-0000-4000-8000-000000000011\",\"expiresAt\":\"2026-11-06T12:03:00.123456+09:00\",\"resendAvailableAt\":\"2026-11-06T12:01:00.123456+09:00\"},\"meta\":{\"requestId\":\"req-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED / INVALID_REQUEST: 이메일·요청 형식", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "429", description = "RATE_LIMITED / VERIFICATION_ATTEMPTS_EXCEEDED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":true,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")), headers = @Header(name = "Retry-After", description = "재시도 가능 시각이 있는 오류에 제공하는 초 단위 대기", schema = @Schema(type = "integer", format = "int64"))),
        @ApiResponse(responseCode = "503", description = "EMAIL_DELIVERY_UNAVAILABLE: 발송 실패·DB 변경 롤백", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"EMAIL_DELIVERY_UNAVAILABLE\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":true,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")))
    })
    @PostMapping
    public ResponseEntity<AuthApiResponse<EmailVerificationResponse>> requestCode(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = EmailVerificationRequest.class), examples = @ExampleObject(value = "{\"email\":\"player@example.com\"}")))
            @Valid @RequestBody EmailVerificationRequest request,
            HttpServletRequest servletRequest
    ) {
        EmailVerificationRequestResult result = verificationService.requestCode(
                request.email(), servletRequest.getRemoteAddr()
        );
        EmailVerificationResponse data = new EmailVerificationResponse(
                result.verificationId(),
                AuthApiResponseFactory.toSeoul(result.expiresAt()),
                AuthApiResponseFactory.toSeoul(result.resendAvailableAt())
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(responseFactory.success(data));
    }

    @Operation(operationId = "verifyEmailVerification", summary = "이메일 인증번호 검증", description = "잘못된 코드 최대 5회. 성공 시 코드 즉시 소비, SIGN_UP 전용 10분 일회용 registrationToken 발급. 재발송해도 누적 실패는 유지.")
    @SecurityRequirements
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공: data와 meta", useReturnTypeSchema = true, content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"data\":{\"registrationToken\":\"example-registration-token\",\"expiresAt\":\"2026-11-06T12:10:00.123456+09:00\"},\"meta\":{\"requestId\":\"req-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED / INVALID_REQUEST / INVALID_VERIFICATION / VERIFICATION_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "429", description = "VERIFICATION_ATTEMPTS_EXCEEDED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"VERIFICATION_ATTEMPTS_EXCEEDED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":true,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")), headers = @Header(name = "Retry-After", description = "재시도 가능 시각이 있는 오류에 제공하는 초 단위 대기", schema = @Schema(type = "integer", format = "int64")))
    })
    @PostMapping("/{verificationId}/verify")
    public AuthApiResponse<EmailVerificationSuccessResponse> verifyCode(
            @PathVariable UUID verificationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = EmailVerificationCodeRequest.class), examples = @ExampleObject(value = "{\"code\":\"123456\"}")))
            @Valid @RequestBody EmailVerificationCodeRequest request
    ) {
        EmailVerificationSuccessResult result = verificationService.verifyCode(
                verificationId, request.code()
        );
        return responseFactory.success(new EmailVerificationSuccessResponse(
                result.registrationToken(),
                AuthApiResponseFactory.toSeoul(result.expiresAt())
        ));
    }
}
