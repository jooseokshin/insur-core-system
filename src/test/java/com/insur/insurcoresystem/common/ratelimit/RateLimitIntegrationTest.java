package com.insur.insurcoresystem.common.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 호출 제한을 켜고 작은 한도로 확인한다. 사번을 테스트마다 다르게 써서 버킷이 서로 섞이지 않게 한다.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.defaults.capacity=3",
        "app.rate-limit.defaults.per-minute=1",
        "app.rate-limit.rules[0].name=channel",
        "app.rate-limit.rules[0].roles=CHANNEL",
        "app.rate-limit.rules[0].paths=/api/**",
        "app.rate-limit.rules[0].capacity=5",
        "app.rate-limit.rules[0].per-minute=1",
        "app.rate-limit.rules[1].name=stats",
        "app.rate-limit.rules[1].paths=/api/stats/**",
        "app.rate-limit.rules[1].capacity=2",
        "app.rate-limit.rules[1].per-minute=1",
})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    private static final String BODY = """
            { "header": { "globalTxId": "tx-rate" }, "body": {} }
            """;

    @Autowired
    MockMvc mockMvc;

    @Test
    void 한도를_넘으면_429와_E_CMN_010_및_Retry_After로_응답한다() throws Exception {
        RequestPostProcessor emp = TestAuth.as("RL-001", Roles.ADMIN);
        for (int i = 2; i >= 0; i--) {
            call("/api/product/list", emp).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(i)));
        }
        call("/api/product/list", emp)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.header.responseCode").value("E-CMN-010"))
                .andExpect(jsonPath("$.header.globalTxId").isNotEmpty());
    }

    @Test
    void 사원마다_한도가_따로다() throws Exception {
        RequestPostProcessor a = TestAuth.as("RL-002", Roles.ADMIN);
        for (int i = 0; i < 3; i++) {
            call("/api/product/list", a).andExpect(status().isOk());
        }
        call("/api/product/list", a).andExpect(status().isTooManyRequests());
        call("/api/product/list", TestAuth.as("RL-003", Roles.ADMIN)).andExpect(status().isOk());
    }

    @Test
    void 규칙에_걸린_경로는_별도_버킷으로_센다() throws Exception {
        RequestPostProcessor emp = TestAuth.as("RL-004", Roles.ADMIN);
        call("/api/stats/loss-ratio", emp).andExpect(status().isOk());
        call("/api/stats/loss-ratio", emp).andExpect(status().isOk());
        call("/api/stats/loss-ratio", emp).andExpect(status().isTooManyRequests());
        // 통계 한도를 다 써도 일반 업무 호출은 기본 버킷에서 나간다
        call("/api/product/list", emp).andExpect(status().isOk());
    }

    @Test
    void 토큰이_없는_요청은_세지_않고_401이다() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/product/list").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void 권한이_없는_호출도_한도에서_빠진다() throws Exception {
        RequestPostProcessor agent = TestAuth.as("RL-005", Roles.AGENT);
        call("/api/stats/loss-ratio", agent).andExpect(status().isForbidden());
        call("/api/stats/loss-ratio", agent).andExpect(status().isForbidden());
        call("/api/stats/loss-ratio", agent).andExpect(status().isTooManyRequests());
    }

    @Test
    void 역할을_지정한_규칙은_그_역할의_토큰에만_적용된다() throws Exception {
        // 채널 시스템은 고객 여럿을 대신하므로 사원 기본 한도(3)가 아니라 채널 규칙(5)을 쓴다
        RequestPostProcessor channel = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(t -> t.subject("ch-rl").claim("channel_cd", "20"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + Roles.CHANNEL));
        for (int i = 4; i >= 0; i--) {
            call("/api/product/list", channel).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(i)));
        }
        call("/api/product/list", channel).andExpect(status().isTooManyRequests());
    }

    private ResultActions call(String path, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON).content(BODY));
    }
}
