package com.insur.auth.config;

import com.insur.auth.common.Roles;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 인증 서버 보안 설정.
 * <ul>
 *     <li>로그인(POST /auth/login)만 토큰 없이 호출할 수 있다.</li>
 *     <li>그 외 요청은 이 서버가 발급한 Bearer 토큰이 필요하고, 사원 관리(/auth/admin/**)는 ADMIN 역할만 쓸 수 있다.</li>
 *     <li>발급과 검증 모두 코어 백엔드와 같은 HS256 공유 비밀키를 쓴다. 그래서 코어가 이 서버의 토큰을 그대로 검증한다.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers("/auth/admin/**").hasRole(Roles.ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            writeError(response, HttpStatus.UNAUTHORIZED, "E-AUTH-001", "인증 토큰이 없거나 유효하지 않습니다.");
                        })
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, HttpStatus.FORBIDDEN, "E-AUTH-002", "접근 권한이 없습니다.")))
                .build();
    }

    /** 비밀번호 해시. BCrypt 는 느린 해시라 비밀번호 대입 공격의 비용을 높인다. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(@Value("${app.jwt.secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(secret)));
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        return NimbusJwtDecoder.withSecretKey(secretKey(secret)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * 공유 비밀키를 HS256 키로 만든다.
     *
     * @throws IllegalStateException 비밀키가 32바이트보다 짧은 경우 (기동 시점에 실패)
     */
    private static SecretKey secretKey(String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret 은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. (HS256)");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    /** roles 클레임의 각 값에 ROLE_ 접두사를 붙여 권한으로 쓴다. (코어 백엔드와 같은 규칙) */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"header":{"globalTxId":"%s","empId":null,"responseCode":"%s","responseMsg":"%s"},"body":null}"""
                .formatted(UUID.randomUUID(), code, message));
    }
}
