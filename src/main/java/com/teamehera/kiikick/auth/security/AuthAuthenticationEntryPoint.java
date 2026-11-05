package com.teamehera.kiikick.auth.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.Locale;

public class AuthAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthSecurityErrorWriter errorWriter;

    public AuthAuthenticationEntryPoint(AuthSecurityErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        if (exception instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException oauth
                && "temporarily_unavailable".equals(oauth.getError().getErrorCode())) {
            errorWriter.write(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "TEMPORARILY_UNAVAILABLE", "인증 상태를 지금 확인할 수 없습니다.");
            return;
        }
        boolean expired = isExpired(exception);
        errorWriter.write(
                request, response,
                HttpServletResponse.SC_UNAUTHORIZED,
                expired ? "TOKEN_EXPIRED" : "UNAUTHENTICATED",
                expired ? "Access Token이 만료되었습니다." : "인증이 필요합니다."
        );
    }

    private boolean isExpired(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof JwtValidationException validationException
                    && validationException.getErrors().stream()
                    .anyMatch(error -> error.getDescription()
                            .toLowerCase(Locale.ROOT).contains("expired"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
