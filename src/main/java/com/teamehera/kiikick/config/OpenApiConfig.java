package com.teamehera.kiikick.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
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
                .info(new Info()
                        .title("KIIKICK Backend API")
                        .version("v1")
                        .description("""
                                KIIKICK 사용자 관리 및 Apple Watch 운동 기록 업로드 API입니다.

                                WorkoutPayload의 날짜 숫자는 2001-01-01T00:00:00Z 기준 초 단위 Apple epoch입니다.
                                서버는 날짜를 UTC로 변환하여 저장합니다.
                                현재 개발 단계에서는 인증 없이 URL의 userId로 운동 소유자를 지정합니다.
                                """));
    }
}
