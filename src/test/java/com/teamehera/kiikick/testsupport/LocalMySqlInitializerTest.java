package com.teamehera.kiikick.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalMySqlInitializerTest {

    private Map<String, String> variables() {
        return new HashMap<>(Map.of(
                "MYSQL_TEST_DB_HOST", "127.0.0.1", "MYSQL_TEST_DB_PORT", "3306",
                "MYSQL_TEST_DB_NAME", "kiikick_repository_test",
                "MYSQL_TEST_DB_USERNAME", "test-only", "MYSQL_TEST_DB_PASSWORD", "test-only"));
    }

    @Test
    void loopback만_허용하고_검증한_설정을_고정한다() {
        for (String host : List.of("127.0.0.1", "localhost")) {
            Map<String, String> variables = variables();
            variables.put("MYSQL_TEST_DB_HOST", host);
            MockEnvironment environment = new MockEnvironment();
            LocalMySqlInitializer.configure(variables, environment);
            assertThat(environment.getProperty("spring.datasource.url"))
                    .startsWith("jdbc:mysql://" + host + ":3306/kiikick_repository_test?");
            assertThat(environment.getProperty("spring.sql.init.mode")).isEqualTo("never");
        }
    }

    @Test
    void 누락되거나_빈_환경변수는_거부한다() {
        for (String name : variables().keySet()) {
            Map<String, String> variables = variables();
            variables.remove(name);
            assertThatThrownBy(() -> LocalMySqlInitializer.configure(variables, new MockEnvironment()))
                    .isInstanceOf(IllegalStateException.class);
            variables.put(name, " ");
            assertThatThrownBy(() -> LocalMySqlInitializer.configure(variables, new MockEnvironment()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 원격호스트_다른포트_DB_URL삽입을_거부한다() {
        for (Map.Entry<String, String> override : Map.of(
                "MYSQL_TEST_DB_HOST", "forbidden.rds.amazonaws.com",
                "MYSQL_TEST_DB_PORT", "13308",
                "MYSQL_TEST_DB_NAME", "kiikick").entrySet()) {
            Map<String, String> variables = variables();
            variables.put(override.getKey(), override.getValue());
            assertThatThrownBy(() -> LocalMySqlInitializer.configure(variables, new MockEnvironment()))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (String host : List.of("127.0.0.2", "::1", "127.0.0.1,remote", "localhost/other?host=remote")) {
            Map<String, String> variables = variables();
            variables.put("MYSQL_TEST_DB_HOST", host);
            assertThatThrownBy(() -> LocalMySqlInitializer.configure(variables, new MockEnvironment()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void JDBC_Hikari_JNDI_우회는_빈_생성_전에_거부한다() {
        for (String name : List.of("spring.datasource.url", "spring.datasource.hikari.jdbc-url",
                "SPRING_DATASOURCE_HIKARI_JDBC_URL", "spring.datasource.hikari.jdbcUrl",
                "spring.datasource.hikari.data-source-class-name", "spring.datasource.hikari.data-source-jndi",
                "spring.datasource.jndi-name", "spring.datasource.type",
                "spring.datasource.hikari.connection-init-sql", "spring.datasource.hikari.catalog",
                "spring.datasource.hikari.username", "spring.datasource.hikari.password",
                "spring.datasource.hikari.data-source-properties.serverName",
                "spring.datasource.xa.properties.serverName", "spring.datasource.xa.data-source-class-name")) {
            try (GenericApplicationContext context = new GenericApplicationContext()) {
                AtomicBoolean created = new AtomicBoolean();
                context.registerBean("probe", Object.class, () -> { created.set(true); return new Object(); });
                context.setEnvironment(new MockEnvironment().withProperty(name, "forbidden-remote"));
                assertThatThrownBy(() -> {
                    LocalMySqlInitializer.configure(variables(), context.getEnvironment());
                    context.refresh();
                }).isInstanceOf(IllegalStateException.class);
                assertThat(created).isFalse();
            }
        }
    }
}
