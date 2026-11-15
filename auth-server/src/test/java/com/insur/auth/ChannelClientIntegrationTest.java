package com.insur.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널계 시스템 클라이언트(client credentials). 사람 로그인 없이 비밀값으로 토큰을 받고, 토큰에는 CHANNEL 역할과 판매채널코드가 들어간다.
 */
@SpringBootTest(properties = {
        "app.oauth.channels[0].client-id=ch-test",
        "app.oauth.channels[0].client-secret=test-channel-secret-0123456789abcdef",
        "app.oauth.channels[0].channel-cd=20",
        "app.oauth.channels[0].name=테스트 다이렉트",
        "app.oauth.partners[0].client-id=partner-test",
        "app.oauth.partners[0].client-secret=test-partner-secret-0123456789abcdef",
        "app.oauth.partners[0].name=테스트 중계",
})
@AutoConfigureMockMvc
@Transactional
class ChannelClientIntegrationTest {

    private static final String SECRET = "test-channel-secret-0123456789abcdef";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtDecoder jwtDecoder;

    private ResultActions token(String clientId, String secret) throws Exception {
        return token(clientId, secret, "channel");
    }

    private ResultActions token(String clientId, String secret, String scope) throws Exception {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(post("/auth/oauth2/token").header("Authorization", "Basic " + basic).accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("scope", scope));
    }

    private String accessToken() throws Exception {
        return JsonPath.read(token("ch-test", SECRET).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.access_token");
    }

    @Test
    void 채널_시스템은_비밀값으로_CHANNEL_역할과_판매채널이_든_토큰을_받는다() throws Exception {
        String res = token("ch-test", SECRET).andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(org.hamcrest.Matchers.lessThanOrEqualTo(600)))
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Jwt jwt = jwtDecoder.decode(JsonPath.read(res, "$.access_token"));
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
        assertThat(jwt.getSubject()).isEqualTo("ch-test");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CHANNEL");
        assertThat(jwt.getClaimAsString("channel_cd")).isEqualTo("20");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("테스트 다이렉트");
    }

    @Test
    void 대외기관_시스템은_EXTERNAL_역할만_든_토큰을_받는다() throws Exception {
        String res = token("partner-test", "test-partner-secret-0123456789abcdef", "partner").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Jwt jwt = jwtDecoder.decode(JsonPath.read(res, "$.access_token"));
        assertThat(jwt.getSubject()).isEqualTo("partner-test");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("EXTERNAL");
        assertThat(jwt.getClaims()).doesNotContainKey("channel_cd");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("테스트 중계");
    }

    @Test
    void 비밀값이_틀리면_거절한다() throws Exception {
        token("ch-test", "wrong-secret").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void 화면_클라이언트는_비밀값_방식으로_토큰을_받을_수_없다() throws Exception {
        // 공개 클라이언트는 PKCE 가 붙은 인가 코드 교환에서만 인증된다. 비밀값 없는 client_credentials 는 클라이언트 인증 실패다
        mockMvc.perform(post("/auth/oauth2/token").accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials").param("client_id", "insur-web"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void 채널_토큰으로는_사원_관리와_본인_정보를_쓸_수_없다() throws Exception {
        String bearer = "Bearer " + accessToken();
        mockMvc.perform(post("/auth/admin/emp-list").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"header\": {}, \"body\": {} }"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/auth/me").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"header\": {}, \"body\": {} }"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-103"));
    }
}
