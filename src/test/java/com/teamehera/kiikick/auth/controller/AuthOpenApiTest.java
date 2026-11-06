package com.teamehera.kiikick.auth.controller;

import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;
import java.util.*;
import java.util.stream.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthOpenApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean VerificationEmailSender email;
    JsonNode document;
    record Api(String path, String method, String status, boolean publicApi) {}
    final List<Api> apis = List.of(
        new Api("/api/v1/auth/email-verifications", "post", "202", true),
        new Api("/api/v1/auth/email-verifications/{verificationId}/verify", "post", "200", true),
        new Api("/api/v1/auth/signup", "post", "201", true),
        new Api("/api/v1/auth/login", "post", "200", true),
        new Api("/api/v1/auth/refresh", "post", "200", true),
        new Api("/api/v1/auth/logout", "post", "204", false),
        new Api("/api/v1/users/me", "get", "200", false),
        new Api("/api/v1/users/me", "patch", "200", false));

    @BeforeAll void generatedDocument() throws Exception {
        document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/auth-openapi.json"), document.toString());
    }
    JsonNode operation(Api api) { return document.path("paths").path(api.path).path(api.method); }
    JsonNode resolve(JsonNode schema) {
        for (int i = 0; i < 8 && schema.has("$ref"); i++) schema = document.at(schema.path("$ref").asText().substring(1));
        return schema;
    }
    JsonNode response(Api api) { return operation(api).path("responses").path(api.status); }
    JsonNode output(Api api) { return resolve(response(api).path("content").path("application/json").path("schema")); }
    JsonNode input(Api api) { return resolve(operation(api).path("requestBody").path("content").path("application/json").path("schema")); }
    boolean nullable(JsonNode schema) {
        schema = resolve(schema);
        return schema.path("nullable").asBoolean() || (schema.path("type").isString() && schema.path("type").asText().equals("null"))
                || StreamSupport.stream(schema.path("type").spliterator(), false).anyMatch(t -> t.asText().equals("null"))
                || StreamSupport.stream(schema.path("anyOf").spliterator(), false).anyMatch(this::nullable);
    }
    boolean required(JsonNode schema, String field) {
        return StreamSupport.stream(schema.path("required").spliterator(), false).anyMatch(n -> n.asText().equals(field));
    }
    JsonNode example(JsonNode content) {
        JsonNode value = content.has("example") ? content.path("example")
                : content.path("examples").properties().iterator().next().getValue().path("value");
        return value.isString() ? json.readTree(value.asText()) : value;
    }

    @Test void 대상8개_operation과_성공상태_설명이_생성된다() {
        assertThat(apis.stream().map(this::operation).filter(n -> !n.isMissingNode()).count()).isEqualTo(8);
        var ids = new HashSet<String>();
        for (var api : apis) {
            assertThat(operation(api).path("summary").asText()).isNotBlank();
            assertThat(operation(api).path("description").asText()).isNotBlank();
            assertThat(ids.add(operation(api).path("operationId").asText())).isTrue();
            assertThat(response(api).isMissingNode()).isFalse();
        }
        for (String absent : List.of("/api/v1/users/me/onboarding", "/api/v1/users/me/consents", "/api/v1/users/me/deletion-requests", "/api/v1/auth/password-reset"))
            assertThat(document.path("paths").has(absent)).isFalse();
    }
    @Test void 공개5개는_전역Bearer를_해제하고_보호3개는_Bearer를_요구한다() {
        var scheme = document.at("/components/securitySchemes/bearerAuth");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asText()).isEqualTo("JWT");
        for (var api : apis) {
            JsonNode security = operation(api).path("security");
            assertThat(security.isArray()).as(api.path + api.method).isTrue();
            if (api.publicApi) assertThat(security.isEmpty()).isTrue();
            else assertThat(security.get(0).has("bearerAuth")).isTrue();
        }
    }
    @Test void 본문성공은_data_meta이며_로그아웃204에는_본문이없다() {
        for (var api : apis) {
            if (api.status.equals("204")) {
                assertThat(response(api).path("content").isEmpty()).isTrue();
                continue;
            }
            assertThat(response(api).path("content").has("*/*")).isFalse();
            var schema = output(api);
            assertThat(schema.path("properties").has("data")).as(api.path + api.method).isTrue();
            assertThat(schema.path("properties").has("meta")).isTrue();
            var meta = resolve(schema.path("properties").path("meta"));
            assertThat(meta.path("properties").propertyNames()).contains("requestId", "serverTime");
            assertThat(meta.at("/properties/serverTime/format").asText()).isEqualTo("date-time");
            var ex = example(response(api).path("content").path("application/json"));
            assertThat(ex.has("data") && ex.has("meta")).isTrue();
            assertThat(ex.at("/meta/serverTime").asText()).endsWith("+09:00");
        }
    }
    @Test void PATCH는_JSON객체_누락_null_내부필드제외를_표현한다() {
        var api = apis.get(7); var schema = input(api);
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").propertyNames()).containsExactlyInAnyOrder("nickname", "avatarUrl");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("minProperties").asInt()).isEqualTo(1);
        assertThat(required(schema, "nickname") || required(schema, "avatarUrl")).isFalse();
        assertThat(nullable(schema.at("/properties/avatarUrl"))).isTrue();
        assertThat(nullable(schema.at("/properties/nickname"))).isFalse();
        assertThat(schema.at("/properties/nickname/maxLength").asInt()).isEqualTo(16);
        var out = resolve(output(api).at("/properties/data"));
        assertThat(out.path("properties").has("createdAt")).isTrue();
        assertThat(nullable(out.at("/properties/nickname"))).isTrue();
        assertThat(required(out, "nickname") && required(out, "avatarUrl")).isTrue();
    }
    @Test void 로그아웃의_빈객체와_선택_UUID는_필수나_nullable로_오표시되지않는다() {
        var schema = input(apis.get(5));
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").propertyNames()).containsExactly("sessionId");
        assertThat(required(schema, "sessionId")).isFalse();
        assertThat(nullable(schema.at("/properties/sessionId"))).isFalse();
        assertThat(schema.at("/properties/sessionId/format").asText()).isEqualTo("uuid");
    }
    @Test void 멱등헤더는_필요한2개에만_UUID로_표시하고_Principal을_숨긴다() {
        for (var api : apis) {
            var parameters = operation(api).path("parameters");
            var keys = StreamSupport.stream(parameters.spliterator(), false)
                    .filter(n -> n.path("name").asText().equals("Idempotency-Key")).toList();
            if (api.equals(apis.get(5)) || api.equals(apis.get(7))) {
                assertThat(keys).hasSize(1);
                assertThat(keys.get(0).path("required").asBoolean()).isTrue();
                assertThat(keys.get(0).at("/schema/format").asText()).isEqualTo("uuid");
            } else assertThat(keys).isEmpty();
            assertThat(StreamSupport.stream(parameters.spliterator(), false).map(n -> n.path("name").asText()).toList())
                    .doesNotContain("actor", "principal", "nicknamePresent", "avatarPresent", "If-Match");
        }
    }
    @Test void 비밀번호_토큰_필수조건과_6자리코드가_표현된다() {
        for (int index : List.of(2, 3)) {
            var schema = input(apis.get(index)); var password = schema.at("/properties/password");
            assertThat(required(schema, "password")).isTrue();
            assertThat(password.path("writeOnly").asBoolean()).isTrue();
            assertThat(password.path("minLength").asInt()).isEqualTo(8);
            assertThat(password.path("maxLength").asInt()).isEqualTo(64);
            assertThat(password.path("description").asText()).contains("72바이트");
        }
        var code = input(apis.get(1)).at("/properties/code");
        assertThat(code.path("pattern").asText()).isEqualTo("\\d{6}");
        assertThat(required(input(apis.get(4)), "refreshToken")).isTrue();
        assertThat(input(apis.get(4)).path("additionalProperties").asBoolean(true)).isFalse();
    }
    @Test void 오류상태와_공통봉투_예시가_생성된다() {
        Map<Integer, List<String>> expected = Map.of(
            0, List.of("400", "429", "503"), 1, List.of("400", "429"),
            2, List.of("400", "409", "422", "503"), 3, List.of("400", "401", "429", "503"),
            4, List.of("401", "422", "503"), 5, List.of("400", "401", "403", "409", "422", "503"),
            6, List.of("401", "503"), 7, List.of("400", "401", "409", "422", "503"));
        expected.forEach((index, statuses) -> statuses.forEach(status -> {
            var content = operation(apis.get(index)).path("responses").path(status).path("content").path("application/json");
            var schema = resolve(content.path("schema"));
            assertThat(schema.path("properties").propertyNames()).contains("error", "meta");
            var error = resolve(schema.at("/properties/error"));
            assertThat(error.path("properties").propertyNames()).contains("code", "message", "retryable", "persistence", "details");
            assertThat(example(content).path("error").has("code")).isTrue();
        }));
        assertThat(operation(apis.get(4)).at("/responses/401/description").asText()).contains("SAVED");
        assertThat(operation(apis.get(3)).at("/responses/429/headers/Retry-After").isMissingNode()).isFalse();
    }
    @Test void 실제로_보장하지않는_공개성공응답_추적헤더를_추가하지않는다() {
        for (var api : apis) if (api.publicApi) assertThat(response(api).path("headers").has("X-Request-Id")).isFalse();
    }
    @Test void Swagger_UI와_초기화설정은_공개로_제공된다() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("swagger-ui")));
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk()).andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }
}
