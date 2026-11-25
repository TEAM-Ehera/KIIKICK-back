package com.teamehera.kiikick.observability;

import com.posthog.server.PostHogCaptureOptions;
import com.posthog.server.PostHogInterface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductAnalyticsTest {
    private AnnotationConfigApplicationContext context;
    private PostHogInterface postHog;
    private TransactionTemplate transaction;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean PostHogInterface client() { return mock(PostHogInterface.class); }
        @Bean ProductAnalytics analytics(PostHogInterface client) { return new ProductAnalytics(client, "test"); }
        @Bean PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:analytics"));
        }
    }

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        postHog = context.getBean(PostHogInterface.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach void close() { context.close(); }

    @Test
    void sendsOnlyAfterCommitWithStableDeduplicationId() {
        var event = new ProductEvent(ProductEvent.Type.WORKOUT_UPLOADED, UUID.randomUUID(), UUID.randomUUID());
        transaction.executeWithoutResult(status -> {
            context.publishEvent(event);
            verifyNoInteractions(postHog);
        });
        var options = ArgumentCaptor.forClass(PostHogCaptureOptions.class);
        verify(postHog).capture(eq(event.userId().toString()), eq("workout_uploaded"), options.capture());
        assertThat(options.getValue().getProperties()).containsOnlyKeys("$insert_id", "environment", "source")
                .containsEntry("$insert_id", "workout_uploaded:" + event.entityId());
    }

    @Test
    void rollbackAndCallsOutsideTransactionsDoNotSendEvents() {
        var event = new ProductEvent(ProductEvent.Type.USER_SIGNED_UP, UUID.randomUUID(), UUID.randomUUID());
        transaction.executeWithoutResult(status -> {
            context.publishEvent(event);
            status.setRollbackOnly();
        });
        context.publishEvent(event);
        verifyNoInteractions(postHog);
    }

    @Test
    void transportFailureDoesNotFailAnAlreadyCommittedOperation() {
        doThrow(new IllegalStateException("transport failed")).when(postHog)
                .capture(anyString(), anyString(), any(PostHogCaptureOptions.class));
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> context.publishEvent(
                new ProductEvent(ProductEvent.Type.MATCH_CREATED, UUID.randomUUID(), UUID.randomUUID()))));
        verify(postHog).capture(anyString(), eq("match_created"), any(PostHogCaptureOptions.class));
    }
}
