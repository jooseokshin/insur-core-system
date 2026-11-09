package com.insur.insurcoresystem.common.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.service.contract.ContractService;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyResponse;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 인증 서버가 발급하는 RS256 토큰 검증. 테스트 안에 공개키(JWKS)를 내놓는 작은 HTTP 서버를 띄워 실제 인증 서버 대신 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RsaJwtAuthenticationTest {

    private static final String ISSUER = "http://127.0.0.1:8088";
    private static final String BODY = """
            { "header": { "globalTxId": "tx-rsa" }, "body": {} }
            """;
    private static final RSAKey KEY = generate("auth-key");
    private static final RSAKey OTHER_KEY = generate("other-key");
    private static final HttpServer JWKS = startJwks();

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ContractService contractService;

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/auth/oauth2/jwks");
        registry.add("app.jwt.issuer", () -> ISSUER);
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
    }

    @Test
    void 인증_서버가_서명한_토큰이면_처리되고_처리사번은_sub다() throws Exception {
        given(contractService.applyContract(any(), any(), any())).willReturn(ContractApplyResponse.builder().build());
        apply(token(KEY, ISSUER, Instant.now().plusSeconds(60), "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.empId").value("E900"));
    }

    @Test
    void issuer_가_다르면_401이다() throws Exception {
        apply(token(KEY, "http://evil.example", Instant.now().plusSeconds(60), "AGENT"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-001"));
    }

    @Test
    void 공개키_목록에_없는_키로_서명했으면_401이다() throws Exception {
        apply(token(OTHER_KEY, ISSUER, Instant.now().plusSeconds(60), "AGENT")).andExpect(status().isUnauthorized());
    }

    @Test
    void 만료된_토큰은_401이다() throws Exception {
        apply(token(KEY, ISSUER, Instant.now().minusSeconds(3600), "AGENT")).andExpect(status().isUnauthorized());
    }

    @Test
    void 서명하지_않은_토큰은_401이다() throws Exception {
        String header = b64("{\"alg\":\"none\"}");
        String claims = b64("{\"sub\":\"E900\",\"iss\":\"" + ISSUER + "\",\"roles\":[\"ADMIN\"],\"exp\":"
                + Instant.now().plusSeconds(60).getEpochSecond() + "}");
        apply(header + "." + claims + ".").andExpect(status().isUnauthorized());
    }

    private ResultActions apply(String bearerToken) throws Exception {
        return mockMvc.perform(post("/api/contract/apply").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Authorization", "Bearer " + bearerToken));
    }

    private static String token(RSAKey key, String issuer, Instant expiresAt, String... roles) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).subject("E900").claim("roles", List.of(roles))
                .issuedAt(expiresAt.minusSeconds(7200)).expiresAt(expiresAt)
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(), claims))
                .getTokenValue();
    }

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 공개키만 내놓는다 (KEY 의 공개 부분). OTHER_KEY 는 목록에 없다. */
    private static HttpServer startJwks() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/auth/oauth2/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
