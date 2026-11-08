package com.insur.auth.config;

import com.insur.auth.common.Roles;
import com.insur.auth.security.LoginSuccessHandler;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * 인증 서버 보안 설정. OAuth 2.1 인가 서버(Authorization Code + PKCE, OIDC)이고, 필터 체인이 셋이다.
 * <ol>
 *     <li>인가 서버 엔드포인트(/auth/oauth2/**, /auth/userinfo, /auth/connect/logout, /.well-known/**).
 *         로그인하지 않은 브라우저가 인가 요청을 하면 로그인 화면으로 보낸다.</li>
 *     <li>본인 정보·비밀번호 변경·사원 관리 API(/auth/me, /auth/password, /auth/admin/**). 이 서버가 발급한 Bearer 토큰으로 호출하고,
 *         세션을 만들지 않는다. 사원 관리는 ADMIN 만.</li>
 *     <li>그 밖의 화면: 로그인(/auth/login), 임시 비밀번호 변경(/auth/password-change). 세션과 CSRF 토큰을 쓰는 일반 웹 화면이다.</li>
 * </ol>
 * 토큰은 RS256 으로 서명하고 공개키를 /auth/oauth2/jwks 로 내놓는다. 코어는 그 공개키로 검증한다.
 */
@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class SecurityConfig {

    private static final String LOGIN_PAGE = "/auth/login";

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        return http
                .securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, as -> as.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // 화면에서 온 인가 요청은 로그인 화면으로, 그 밖(userinfo 등)은 Bearer 토큰으로 인증한다
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/auth/me", "/auth/password", "/auth/admin/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
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

    @Bean
    @Order(3)
    SecurityFilterChain loginChain(HttpSecurity http) throws Exception {
        // 사번·비밀번호 확인은 빈으로 등록된 EmployeeAuthenticationProvider 하나가 한다. 여기서 authenticationProvider(...) 로
        // 또 붙이면 실패할 때 상위 인증 관리자가 한 번 더 확인해서 실패 횟수가 두 번 올라간다(5회가 아니라 3회에 잠김).
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(LOGIN_PAGE, "/auth/password-change", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage(LOGIN_PAGE)
                        .loginProcessingUrl(LOGIN_PAGE)
                        .usernameParameter("empId")
                        .passwordParameter("password")
                        .successHandler(new LoginSuccessHandler())
                        .failureHandler(new SimpleUrlAuthenticationFailureHandler(LOGIN_PAGE + "?error")))
                .logout(logout -> logout.logoutUrl("/auth/logout").logoutSuccessUrl(LOGIN_PAGE + "?logout"))
                .build();
    }

    /** 비밀번호 해시. BCrypt 는 느린 해시라 비밀번호 대입 공격의 비용을 높인다. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 엔드포인트 주소. nginx 가 /auth/** 를 이 서버로 넘기므로 모두 그 아래에 둔다.
     * 메타데이터(/.well-known/openid-configuration, /.well-known/oauth-authorization-server)는 표준 위치라 nginx 가 따로 넘긴다.
     */
    @Bean
    AuthorizationServerSettings authorizationServerSettings(OAuthProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.issuer())
                .authorizationEndpoint("/auth/oauth2/authorize")
                .pushedAuthorizationRequestEndpoint("/auth/oauth2/par")
                .tokenEndpoint("/auth/oauth2/token")
                .jwkSetEndpoint("/auth/oauth2/jwks")
                .tokenRevocationEndpoint("/auth/oauth2/revoke")
                .tokenIntrospectionEndpoint("/auth/oauth2/introspect")
                .deviceAuthorizationEndpoint("/auth/oauth2/device_authorization")
                .deviceVerificationEndpoint("/auth/oauth2/device_verification")
                .clientRegistrationEndpoint("/auth/oauth2/register")
                .oidcClientRegistrationEndpoint("/auth/connect/register")
                .oidcUserInfoEndpoint("/auth/userinfo")
                .oidcLogoutEndpoint("/auth/connect/logout")
                .build();
    }

    /**
     * 현업 화면(SPA) 클라이언트. 브라우저 코드에는 비밀값을 둘 수 없으므로 공개 클라이언트이고, 그래서 PKCE 가 필수다.
     * 자사 화면이라 동의 화면은 띄우지 않는다. 공개 클라이언트에는 갱신 토큰을 주지 않는다(DPoP 같은 묶음 장치 없이 주면 탈취 시 오래 쓰인다).
     * 액세스 토큰이 만료되면 화면이 인가 요청을 다시 하고, 로그인 세션이 남아 있으면 비밀번호 없이 새 토큰을 받는다.
     */
    @Bean
    RegisteredClientRepository registeredClientRepository(OAuthProperties properties) {
        OAuthProperties.Client web = properties.web();
        RegisteredClient client = RegisteredClient.withId(web.clientId())
                .clientId(web.clientId())
                .clientName("손해보험 기간계 현업 화면")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUris(uris -> uris.addAll(web.redirectUris()))
                .postLogoutRedirectUris(uris -> uris.addAll(web.postLogoutRedirectUris()))
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                        .accessTokenTimeToLive(Duration.ofMinutes(properties.accessTokenTtlMinutes()))
                        .build())
                .build();
        return new InMemoryRegisteredClientRepository(client);
    }

    /** 인가 코드와 발급 토큰 기록은 DB(auth.oauth2_authorization, V3)에 둔다. 재기동이나 여러 대 구성에서도 코드 교환이 끊기지 않는다. */
    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, clients);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(OAuthProperties properties) {
        return new ImmutableJWKSet<>(new JWKSet(SigningKeys.load(properties.signingKey())));
    }

    /** 이 서버의 API(체인 2)와 userinfo 가 자기가 발급한 토큰을 검증할 때 쓴다. */
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
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
