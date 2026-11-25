package com.teamehera.kiikick.observability;

import com.posthog.server.PostHog;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogInterface;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kiikick.observability.posthog.enabled", havingValue = "true")
class PostHogConfiguration {
    @Bean(destroyMethod = "close")
    PostHogInterface postHog(
            @Value("${kiikick.observability.posthog.api-key:}") String key,
            @Value("${kiikick.observability.posthog.host}") URI host
    ) {
        if (key.isBlank() || !"https".equals(host.getScheme()) || host.getHost() == null) {
            throw new IllegalStateException("Configure a PostHog project key and HTTPS ingestion host");
        }
        return PostHog.with(PostHogConfig.builder(key).host(host.toString()).build());
    }
}
