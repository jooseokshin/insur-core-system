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
 * @param channels               채널계 시스템 클라이언트 (다이렉트·TM 등). 비밀값이 비어 있는 항목은 등록하지 않는다
 */
@ConfigurationProperties("app.oauth")
public record OAuthProperties(
        String issuer,
        @DefaultValue("60") long accessTokenTtlMinutes,
        @DefaultValue("") String signingKey,
        Client web,
        @DefaultValue List<ChannelClient> channels) {

    /**
     * 공개 클라이언트(비밀값 없음, PKCE 필수) 설정.
     *
     * @param clientId               클라이언트 ID
     * @param redirectUris           인가 코드를 돌려보낼 수 있는 주소 (정확히 같아야 한다)
     * @param postLogoutRedirectUris 로그아웃 뒤 돌아갈 수 있는 주소
     */
    public record Client(String clientId, List<String> redirectUris, List<String> postLogoutRedirectUris) {
    }

    /**
     * 채널계 시스템 클라이언트. 사람이 아니라 서버가 client credentials 로 토큰을 받는 기밀 클라이언트다.
     *
     * @param clientId     클라이언트 ID. 토큰의 sub 가 되고, 코어는 이 값으로 채널이 접수한 계약을 구분한다
     * @param clientSecret 클라이언트 비밀값 (32자 이상, 환경변수로 주입). BCrypt 로 바꿔 메모리에만 둔다
     * @param channelCd    판매채널코드 (20 다이렉트, 30 TM). 토큰의 channel_cd 클레임
     * @param name         채널 이름 (토큰의 name 클레임)
     * @param tokenTtlMinutes 토큰 유효시간(분). 시스템은 다시 받기 쉬우므로 사람 토큰보다 짧게 둔다
     */
    public record ChannelClient(String clientId, String clientSecret, String channelCd, String name,
                                @DefaultValue("10") long tokenTtlMinutes) {
    }
}
