package com.teamehera.kiikick.observability;

import io.sentry.IScopes;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class SentryHandledErrorMonitorTest {
    @Test
    void capturesHandledServerErrorsButNotClientErrorsOrSdkHandledExceptions() {
        var scopes = mock(IScopes.class);
        var monitor = new SentryHandledErrorMonitor(Optional.of(scopes));
        var resolvers = new ArrayList<HandlerExceptionResolver>();
        monitor.extendHandlerExceptionResolvers(resolvers);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var failure = new IllegalStateException("database unavailable");
        assertNull(resolvers.getFirst().resolveException(request, response, null, failure));
        response.setStatus(400);
        monitor.afterCompletion(request, response, null, null);
        verifyNoInteractions(scopes);
        response.setStatus(503);
        monitor.afterCompletion(request, response, null, null);
        verify(scopes).captureException(failure);
        clearInvocations(scopes);
        monitor.afterCompletion(request, response, null, failure);
        verifyNoInteractions(scopes);
    }
}
