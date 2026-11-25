package com.teamehera.kiikick.observability;

import io.sentry.IScopes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Optional;

@Component
class SentryHandledErrorMonitor implements WebMvcConfigurer, HandlerInterceptor {
    private static final String FAILURE = SentryHandledErrorMonitor.class.getName() + ".failure";
    private final Optional<IScopes> scopes;

    SentryHandledErrorMonitor(Optional<IScopes> scopes) { this.scopes = scopes; }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.addFirst((request, response, handler, exception) -> {
            request.setAttribute(FAILURE, exception);
            return null;
        });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this); }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception unhandled) {
        // 처리되지 않은 예외는 SDK가 수집한다. 여기서는 예외 처리기가 5xx로 변환한 경우만 수집한다.
        if (unhandled == null && response.getStatus() >= 500
                && request.getAttribute(FAILURE) instanceof Throwable failure) {
            scopes.ifPresent(sentry -> sentry.captureException(failure));
        }
    }
}
