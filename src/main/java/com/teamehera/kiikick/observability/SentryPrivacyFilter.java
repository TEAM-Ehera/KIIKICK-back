package com.teamehera.kiikick.observability;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;
import org.springframework.stereotype.Component;

@Component
class SentryPrivacyFilter implements SentryOptions.BeforeSendCallback {
    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        event.setUser(null);
        event.setMessage(null);
        event.setExtras(null);
        event.setBreadcrumbs(null);
        event.setServerName(null);
        event.setTransaction(null);
        event.setThreads(null);
        event.getContexts().clear();
        event.setLevel(SentryLevel.ERROR);
        var request = event.getRequest();
        if (request != null) {
            // 허용한 필드만 새로 구성해 헤더·본문·URL 및 확장 필드를 함께 제거한다.
            var sanitized = new Request();
            sanitized.setMethod(request.getMethod());
            event.setRequest(sanitized);
        }
        if (event.getExceptions() != null) {
            for (var exception : event.getExceptions()) {
                // SQL 예외 메시지에 이메일·업로드 값이 포함될 수 있다.
                exception.setValue("[redacted]");
                if (exception.getStacktrace() != null && exception.getStacktrace().getFrames() != null) {
                    for (var frame : exception.getStacktrace().getFrames()) frame.setVars(null);
                }
            }
        }
        return event;
    }
}
