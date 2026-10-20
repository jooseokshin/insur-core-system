package com.insur.insurcoresystem.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 설정을 따로 켜지 않으면(운영과 같은 기본 설정) API 문서가 노출되지 않는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDefaultOffTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 기본_설정에서는_API_명세와_Swagger_UI가_열리지_않는다() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }
}
