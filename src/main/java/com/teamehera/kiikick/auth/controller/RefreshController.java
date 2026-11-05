package com.teamehera.kiikick.auth.controller;

import com.teamehera.kiikick.auth.dto.*;
import com.teamehera.kiikick.auth.service.RefreshService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class RefreshController {
    private final RefreshService service;
    private final AuthApiResponseFactory responses;
    public RefreshController(RefreshService service, AuthApiResponseFactory responses) {
        this.service=service; this.responses=responses;
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthApiResponse<LoginResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
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
