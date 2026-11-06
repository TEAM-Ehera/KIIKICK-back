package com.teamehera.kiikick.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import java.util.Set;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * Swagger UI 상단에 표시할 프로젝트 공통 정보를 설정한다.
 * API 경로나 실행 동작에는 영향을 주지 않는다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI kiikickOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(
                        "bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                ))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .info(new Info()
                        .title("KIIKICK Backend API")
                        .version("v1")
                        .description("""
                                KIIKICK 경기·쿼터 기록, 운동 업로드·결과·분석, 경기장, 친구, 알림 및 사용자 관리 API입니다.

                                WorkoutPayload의 날짜 숫자는 2001-01-01T00:00:00Z 기준 초 단위 Apple epoch입니다.
                                서버는 날짜를 UTC로 변환하여 저장합니다.
                                공개 Auth API를 제외한 API는 Bearer Access Token 인증이 필요합니다.
                                기존 사용자 운동 업로드 경로는 URL의 userId를 사용합니다. 경기·쿼터 API는 요청의 사용자 Principal로 소유권을 확인합니다.
                                """));
    }
    @Bean
    public OpenApiCustomizer authJsonResponseSchemas() {
        var paths = Set.of("/api/v1/auth/email-verifications",
                "/api/v1/auth/email-verifications/{verificationId}/verify",
                "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh",
                "/api/v1/auth/logout", "/api/v1/users/me");
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> {
                if (!paths.contains(path)) return;
                item.readOperations().forEach(operation -> {
                    if (operation.getResponses() == null) return;
                    operation.getResponses().values().forEach(response -> {
                        var content = response.getContent();
                        if (content == null) return;
                        var json = content.get("application/json");
                        var inferred = content.get("*/*");
                        if (json == null || inferred == null) return;
                        // 런타임 produces는 바꾸지 않고 추론된 제네릭 스키마를 JSON 예시에 결합한다.
                        if (json.getSchema() == null) json.setSchema(inferred.getSchema());
                        if (json.getSchema() != null) content.remove("*/*");
                    });
                });
            });
        };
    }

}
