package com.insur.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OAuth 인가 서버 설정 (app.oauth.*).
 *
 * @param issuer                 토큰의 iss. 브라우저가 보는 출처(nginx 주소)이고 코어의 검증 값(AUTH_ISSUER)과 같아야 한다
 * @param accessTokenTtlMinutes  액세스 토큰 유효시간(분)
 * @param signingKey             서명용 RSA 개인키. PKCS#8 PEM 본문이나 그 파일 경로. 비우면 기동할 때마다 새로 만든다(개발용)
 * @param web                    현업 화면(SPA) 클라이언트
 */
@ConfigurationProperties("app.oauth")
public record OAuthProperties(
        String issuer,
        @DefaultValue("60") long accessTokenTtlMinutes,
        @DefaultValue("") String signingKey,
        Client web) {

    /**
     * 공개 클라이언트(비밀값 없음, PKCE 필수) 설정.
     *
     * @param clientId               클라이언트 ID
     * @param redirectUris           인가 코드를 돌려보낼 수 있는 주소 (정확히 같아야 한다)
     * @param postLogoutRedirectUris 로그아웃 뒤 돌아갈 수 있는 주소
     */
    public record Client(String clientId, List<String> redirectUris, List<String> postLogoutRedirectUris) {
    }
}
