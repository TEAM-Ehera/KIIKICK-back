package com.teamehera.kiikick.auth.controller;

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

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {
    private final LoginService loginService;
    private final AuthApiResponseFactory responses;

    public LoginController(LoginService loginService, AuthApiResponseFactory responses) {
        this.loginService = loginService;
        this.responses = responses;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = loginService.login(request.email(), request.password());
        User user = result.user();
        SignupUserResponse profile = new SignupUserResponse(user.id(), user.nickname(), user.avatarUrl(),
                user.onboardingCompletedAt() == null ? "REQUIRED" : "COMPLETED",
                result.comparisonConsent(), responses.toSeoul(user.createdAt()));
        LoginResponse response = new LoginResponse(result.sessionId(), result.accessToken(), result.refreshToken(),
                responses.toSeoul(LocalDateTime.ofInstant(result.expiresAt(), ZoneOffset.UTC)), profile);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(responses.success(response));
    }
}
