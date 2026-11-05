package com.teamehera.kiikick.testsupport;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 테스트 Context가 DataSource를 생성하기 전에 연결 설정을 검증한다. */
public final class LocalMySqlInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final List<String> REQUIRED = List.of(
            "MYSQL_TEST_DB_HOST", "MYSQL_TEST_DB_PORT", "MYSQL_TEST_DB_NAME",
            "MYSQL_TEST_DB_USERNAME", "MYSQL_TEST_DB_PASSWORD");
    private static final List<String> FORBIDDEN = List.of(
            "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.data-source-class-name",
            "spring.datasource.hikari.data-source-jndi", "spring.datasource.jndi-name",
            "spring.datasource.type", "spring.datasource.xa.data-source-class-name",
            "spring.datasource.hikari.driver-class-name", "spring.datasource.hikari.catalog",
            "spring.datasource.hikari.schema", "spring.datasource.hikari.connection-init-sql",
            "spring.datasource.hikari.connection-test-query", "spring.datasource.hikari.username",
            "spring.datasource.hikari.password");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        configure(System.getenv(), context.getEnvironment());
    }

    public static void configure(Map<String, String> variables, ConfigurableEnvironment environment) {
        for (String name : REQUIRED) {
            if (variables.get(name) == null || variables.get(name).isBlank()) {
                throw new IllegalStateException("Required local test variable missing: " + name);
            }
        }
        String host = variables.get("MYSQL_TEST_DB_HOST");
        if (!List.of("127.0.0.1", "localhost").contains(host)
                || !"3306".equals(variables.get("MYSQL_TEST_DB_PORT"))
                || !"kiikick_repository_test".equals(variables.get("MYSQL_TEST_DB_NAME"))) {
            throw new IllegalStateException("Only the local dedicated MySQL test database is allowed");
        }
        try {
            if (Arrays.stream(InetAddress.getAllByName(host)).anyMatch(address -> !address.isLoopbackAddress())) {
                throw new IllegalStateException("Local test host must resolve only to loopback addresses");
            }
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("Cannot resolve local test host");
        }

        String url = "jdbc:mysql://" + host + ":3306/kiikick_repository_test"
                + "?sslMode=REQUIRED&connectionTimeZone=UTC"
                + "&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8";
        // 별도 풀 URL·JNDI·드라이버 속성은 검증한 URL을 우회할 수 있으므로 허용하지 않는다.
        FORBIDDEN.forEach(name -> rejectAlternateSetting(name, environment.containsProperty(name)));
        environment.getPropertySources().forEach(source -> {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    String normalized = canonical(name);
                    boolean forbidden = FORBIDDEN.stream().map(LocalMySqlInitializer::canonical)
                            .anyMatch(normalized::equals)
                            || normalized.startsWith("springdatasourcehikaridatasourceproperties")
                            || normalized.startsWith("springdatasourcexaproperties");
                    rejectAlternateSetting(name, forbidden);
                }
            }
        });
        String configuredUrl = environment.getProperty("spring.datasource.url");
        if (configuredUrl != null && !url.equals(configuredUrl)) {
            throw new IllegalStateException("MySQL test datasource URL does not match the validated local target");
        }
        Map<String, Object> safe = new HashMap<>();
        safe.put("spring.datasource.url", url);
        safe.put("spring.datasource.username", variables.get("MYSQL_TEST_DB_USERNAME"));
        safe.put("spring.datasource.password", variables.get("MYSQL_TEST_DB_PASSWORD"));
        safe.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        safe.put("spring.sql.init.mode", "never");
        environment.getPropertySources().addFirst(new MapPropertySource("validatedLocalMySql", safe));
    }

    private static String canonical(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static void rejectAlternateSetting(String name, boolean present) {
        if (present) {
            throw new IllegalStateException("Alternate MySQL test datasource setting is forbidden: " + name);
        }
    }
}
