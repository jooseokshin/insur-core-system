package com.insur.insurcoresystem.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 문서를 켠 상태(local 프로파일과 같은 설정)의 노출 검증. 명세는 토큰 없이 열리고, 업무 API 는 여전히 토큰을 요구한다.
 */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void API_명세는_토큰_없이_열리고_모든_업무_API와_Bearer_인증_방식을_담는다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("insur-core-system API"))
                .andExpect(jsonPath("$.paths['/api/contract/apply'].post").exists())
                .andExpect(jsonPath("$.paths['/api/contract/renew-quote'].post").exists())
                .andExpect(jsonPath("$.paths['/api/admin/premium/audit-list'].post").exists())
                .andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].scheme").value("bearer"));
    }

    @Test
    void 문서가_열려도_업무_API는_토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/contract/apply").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
