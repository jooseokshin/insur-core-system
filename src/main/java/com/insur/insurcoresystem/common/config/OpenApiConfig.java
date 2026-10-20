package com.insur.insurcoresystem.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서(OpenAPI) 설정. 모든 API 가 JWT Bearer 토큰을 요구하므로 Swagger UI 의 Authorize 에 토큰을 넣으면 호출해 볼 수 있다.
 * (개발용 토큰은 scripts/dev-token.sh 로 만든다)
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI insurCoreOpenApi() {
        return new OpenAPI()
                .info(new Info().title("insur-core-system API").version("0.0.1")
                        .description("손해보험 기간계 API. 모든 요청과 응답은 공통 전문(header + body)이며, 오류는 header.responseCode 로 구분한다."))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
