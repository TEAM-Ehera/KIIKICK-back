package com.teamehera.kiikick.observability;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;
import io.sentry.protocol.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SentryPrivacyFilterTest {
    @Test
    void removesSensitivePayloadsButPreservesExceptionTypeAndStackLocation() {
        var event = new SentryEvent();
        var request = new Request();
        request.setMethod("POST");
        request.setUrl("https://api.test/users/private-id");
        request.setHeaders(Map.of("Authorization", "Bearer secret"));
        request.setData(Map.of("password", "secret", "latitude", 37.5));
        request.setQueryString("email=private@example.com");
        request.setCookies("session=secret");
        event.setRequest(request);
        event.setUser(new User());
        event.setExtra("email", "private@example.com");
        var frame = new SentryStackFrame();
        frame.setFunction("save");
        frame.setLineno(42);
        frame.setVars(Map.of("password", "secret"));
        var exception = new SentryException();
        exception.setType("IllegalStateException");
        exception.setValue("email=private@example.com");
        exception.setStacktrace(new SentryStackTrace(List.of(frame)));
        event.setExceptions(List.of(exception));

        new SentryPrivacyFilter().execute(event, new Hint());

        assertThat(event.getUser()).isNull();
        assertThat(event.getExtras()).isNullOrEmpty();
        assertThat(event.getRequest().getMethod()).isEqualTo("POST");
        assertThat(event.getRequest().getUrl()).isNull();
        assertThat(event.getRequest().getHeaders()).isNullOrEmpty();
        assertThat(event.getRequest().getData()).isNull();
        assertThat(event.getRequest().getCookies()).isNull();
        assertThat(event.getRequest().getQueryString()).isNull();
        assertThat(exception.getValue()).isEqualTo("[redacted]");
        assertThat(exception.getType()).isEqualTo("IllegalStateException");
        assertThat(frame.getVars()).isNullOrEmpty();
        assertThat(frame.getLineno()).isEqualTo(42);
        assertThat(frame.getFunction()).isEqualTo("save");
    }
}
