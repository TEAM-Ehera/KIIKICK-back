package com.teamehera.kiikick.auth.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

public class AuthAccessDeniedHandler implements AccessDeniedHandler {

    private final AuthSecurityErrorWriter errorWriter;

    public AuthAccessDeniedHandler(AuthSecurityErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException, ServletException {
        errorWriter.write(
                request, response,
                HttpServletResponse.SC_FORBIDDEN,
                "FORBIDDEN",
                "접근 권한이 없습니다."
        );
    }
}
