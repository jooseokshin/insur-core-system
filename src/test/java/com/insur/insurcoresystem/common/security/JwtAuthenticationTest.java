package com.insur.insurcoresystem.common.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.service.contract.ContractService;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyResponse;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * JWT 인증 검증. 실제로 서명한 토큰을 Authorization 헤더로 보내 서명/만료 검증과 사번 설정을 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationTest {

    private static final String BODY = """
            { "header": { "globalTxId": "tx-auth", "empId": "HACKER" }, "body": {} }
            """;

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ContractService contractService;
    @Value("${app.jwt.secret}")
    String secret;

    @Test
    void 토큰이_없으면_401과_E_AUTH_001로_응답한다() throws Exception {
        apply(null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-001"))
                .andExpect(jsonPath("$.header.globalTxId").isNotEmpty());
    }

    @Test
    void 형식이_잘못된_토큰은_401이다() throws Exception {
        apply("not-a-jwt")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-001"));
    }

    @Test
    void 다른_비밀키로_서명한_토큰은_401이다() throws Exception {
        apply(token("another-secret-key-for-hs256-at-least-32-bytes", "E1", Instant.now().plusSeconds(60)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-001"));
    }

    @Test
    void 만료된_토큰은_401이다() throws Exception {
        apply(token(secret, "E1", Instant.now().minusSeconds(3600)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-001"));
    }

    @Test
    void 유효한_토큰이면_요청이_처리되고_처리사번은_요청_헤더가_아닌_토큰의_subject다() throws Exception {
        given(contractService.applyContract(any(), any(), any())).willReturn(ContractApplyResponse.builder().build());

        apply(token(secret, "E777", Instant.now().plusSeconds(60), "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.responseCode").value("0000"))
                .andExpect(jsonPath("$.header.empId").value("E777"));
    }

    @Test
    void 토큰의_roles_클레임이_권한으로_변환되어_역할이_없으면_403이다() throws Exception {
        // 서명과 만료는 유효하지만 roles 클레임이 비어 있는 토큰: 인증은 통과하지만 인가에서 거절된다
        apply(token(secret, "E777", Instant.now().plusSeconds(60)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-002"));
        // 이 시스템에 없는 역할만 가진 토큰도 마찬가지
        apply(token(secret, "E777", Instant.now().plusSeconds(60), "GUEST"))
                .andExpect(status().isForbidden());
    }

    private ResultActions apply(String bearerToken) throws Exception {
        var request = post("/api/contract/apply").contentType(MediaType.APPLICATION_JSON).content(BODY);
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return mockMvc.perform(request);
    }

    private static String token(String secret, String subject, Instant expiresAt, String... roles) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var claims = JwtClaimsSet.builder()
                .subject(subject)
                .claim("roles", List.of(roles))
                .issuedAt(expiresAt.minusSeconds(7200))
                .expiresAt(expiresAt)
                .build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
