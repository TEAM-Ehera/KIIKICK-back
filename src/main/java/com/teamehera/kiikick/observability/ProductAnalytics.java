package com.teamehera.kiikick.observability;

import com.posthog.server.PostHogCaptureOptions;
import com.posthog.server.PostHogInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "kiikick.observability.posthog.enabled", havingValue = "true")
class ProductAnalytics {
    private static final Logger log = LoggerFactory.getLogger(ProductAnalytics.class);
    private final PostHogInterface postHog;
    private final String environment;

    ProductAnalytics(PostHogInterface postHog,
                     @Value("${kiikick.observability.environment}") String environment) {
        this.postHog = postHog;
        this.environment = environment;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void capture(ProductEvent event) {
        try {
            // SDK의 비동기 큐를 사용한다. 전송 실패가 이미 커밋된 API 응답을 실패로 바꾸면 안 된다.
            postHog.capture(event.userId().toString(), event.type().eventName(),
                    PostHogCaptureOptions.builder()
                            .property("$insert_id", event.type().eventName() + ":" + event.entityId())
                            .property("environment", environment)
                            .property("source", "backend")
                            .build());
        } catch (RuntimeException exception) {
            // SDK 오류 메시지에는 키·이벤트 본문이 포함될 수 있어 유형만 남긴다.
            log.warn("Product analytics enqueue failed: {}", exception.getClass().getSimpleName());
        }
    }
}
