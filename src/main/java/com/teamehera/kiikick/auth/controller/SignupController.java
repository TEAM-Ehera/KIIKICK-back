package com.teamehera.kiikick.auth.controller;

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

    @PostMapping("/signup")
    public ResponseEntity<AuthApiResponse<SignupResponse>> signup(
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
                result.userCreatedAt()
        );
        SignupResponse response = new SignupResponse(
                result.sessionId(),
                result.accessToken(),
                result.refreshToken(),
                result.accessTokenExpiresAt(),
                user
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.success(response));
    }
}
