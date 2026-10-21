package com.insur.insurcoresystem.common.security;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT 인증 설정. 이 시스템은 토큰을 발급하지 않고, 외부 인증 서버가 HS256 으로 서명해 발급한 Bearer 토큰만 검증한다.
 * <ul>
 *     <li>모든 요청은 유효한 토큰이 필요하다. (서명, 만료 검증)</li>
 *     <li>토큰의 subject(sub)가 처리 사번(empId)이 되고, roles 클레임이 역할 권한이 된다. (역할: {@link Roles})</li>
 *     <li>API 별 허용 역할은 URL 규칙으로 정하고, 상태 변경의 상태별 권한은 ContractAccessPolicy 가 추가로 확인한다.</li>
 *     <li>세션을 만들지 않으므로(STATELESS) CSRF 보호는 사용하지 않는다.</li>
 *     <li>인증/인가 실패도 공통 전문 형태로 응답한다. (E-AUTH-001: 401, E-AUTH-002: 403)</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    static final String UNAUTHORIZED_CODE = "E-AUTH-001";
    static final String FORBIDDEN_CODE = "E-AUTH-002";
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 보안 필터 체인을 구성한다. 모든 요청을 인증 대상으로 하고, JWT 리소스 서버로 동작하며 오류는 공통 전문으로 응답한다.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // API 문서(Swagger UI)는 토큰 없이 열 수 있다. 호출은 화면의 Authorize 에 토큰을 넣어서 한다.
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/contract/apply")
                        .hasAnyRole(Roles.AGENT, Roles.ADMIN)
                        // 갱신은 새 청약을 만드는 일이라 청약 접수와 같은 역할
                        .requestMatchers(HttpMethod.POST, "/api/contract/renew", "/api/contract/renew-quote")
                        .hasAnyRole(Roles.AGENT, Roles.ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/contract/inquiry")
                        .hasAnyRole(Roles.AGENT, Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN)
                        // 요율/계수 관리: 조회는 심사·계약 관리·ADMIN, 개정과 신규 등록은 ADMIN 만
                        .requestMatchers(HttpMethod.POST, "/api/admin/premium/rate-list", "/api/admin/premium/factor-list",
                                "/api/admin/premium/policy-list", "/api/admin/premium/audit-list")
                        .hasAnyRole(Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/admin/premium/rate-revise", "/api/admin/premium/factor-revise",
                                "/api/admin/premium/policy-revise")
                        .hasRole(Roles.ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/premium/quote")
                        .hasAnyRole(Roles.AGENT, Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/contract/list")
                        .hasAnyRole(Roles.AGENT, Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/contract/endorse")
                        .hasAnyRole(Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/contract/endorse-history")
                        .hasAnyRole(Roles.AGENT, Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN)
                        // 납입(수납)은 계약 관리 담당과 ADMIN, 납입 내역 조회는 모든 역할(AGENT 는 본인 접수 계약만)
                        .requestMatchers(HttpMethod.POST, "/api/contract/payment")
                        .hasAnyRole(Roles.OPERATOR, Roles.ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/contract/payment-history")
                        .hasAnyRole(Roles.AGENT, Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/contract/status-history")
                        .hasAnyRole(Roles.AGENT, Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN)
                        // 상태 변경은 여기서 심사/관리 담당자만 통과시키고, 변경할 상태별 세부 권한은 ContractAccessPolicy 가 확인한다.
                        .requestMatchers(HttpMethod.POST, "/api/contract/status-change")
                        .hasAnyRole(Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            writeError(response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_CODE,
                                    "인증 토큰이 없거나 유효하지 않습니다.");
                        })
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, HttpStatus.FORBIDDEN, FORBIDDEN_CODE, "접근 권한이 없습니다.")))
                .build();
    }

    /**
     * JWT 를 인증 객체로 바꾸는 변환기. roles 클레임(문자열 배열)의 각 값에 ROLE_ 접두사를 붙여 권한으로 사용한다.
     * 처리 사번(이름)은 기본값인 sub 클레임이다.
     */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * 토큰 서명 검증기를 만든다. 공유 비밀키(HS256)로 서명과 만료 시각(exp, nbf)을 검증한다.
     *
     * @param secret 서명 비밀키 (app.jwt.secret, 32바이트 이상)
     * @throws IllegalStateException 비밀키가 HS256 최소 길이(32바이트)보다 짧은 경우 (기동 시점에 실패)
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret 은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. (HS256)");
        }
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * 컨트롤러 이전 단계(보안 필터)에서 발생한 오류를 공통 전문 JSON 으로 직접 기록한다.
     * 이 단계에서는 요청 전문을 읽지 못했으므로 거래 UUID 는 새로 채번하고, 문자열은 고정값만 사용한다.
     */
    private static void writeError(HttpServletResponse response, HttpStatus status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"header":{"globalTxId":"%s","empId":null,"responseCode":"%s","responseMsg":"%s"},"body":null}"""
                .formatted(UUID.randomUUID(), code, message));
    }
}
