package com.teamehera.kiikick.auth.controller;

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

    @PostMapping
    public ResponseEntity<AuthApiResponse<EmailVerificationResponse>> requestCode(
            @Valid @RequestBody EmailVerificationRequest request,
            HttpServletRequest servletRequest
    ) {
        EmailVerificationRequestResult result = verificationService.requestCode(
                request.email(), servletRequest.getRemoteAddr()
        );
        EmailVerificationResponse data = new EmailVerificationResponse(
                result.verificationId(),
                result.expiresAt(),
                result.resendAvailableAt()
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(responseFactory.success(data));
    }

    @PostMapping("/{verificationId}/verify")
    public AuthApiResponse<EmailVerificationSuccessResponse> verifyCode(
            @PathVariable UUID verificationId,
            @Valid @RequestBody EmailVerificationCodeRequest request
    ) {
        EmailVerificationSuccessResult result = verificationService.verifyCode(
                verificationId, request.code()
        );
        return responseFactory.success(new EmailVerificationSuccessResponse(
                result.registrationToken(),
                result.expiresAt()
        ));
    }
}
