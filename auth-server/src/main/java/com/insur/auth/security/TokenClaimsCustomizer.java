package com.insur.auth.security;

import com.insur.auth.common.BusinessException;
import com.insur.auth.domain.Employee;
import com.insur.auth.service.AuthService;
import com.insur.auth.config.SecurityConfig;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * 토큰에 코어가 읽는 클레임을 넣는다. 액세스 토큰: sub = 사번(기본값), roles = 역할 배열, name = 이름. ID 토큰: name.
 * <p>
 * 역할과 계정 상태는 로그인 세션에 담긴 값이 아니라 발급하는 순간 DB 에서 다시 읽는다. 그래서 로그인 세션이 살아 있어도
 * 사용 중지·잠금·비밀번호 초기화된 사원은 다음 토큰을 받지 못하고(invalid_grant), 역할 변경은 다음 토큰부터 반영된다.
 */
@Slf4j
@Component
public class TokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final AuthService authService;

    public TokenClaimsCustomizer(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
            customizeChannel(context);
            return;
        }
        // 그 밖에는 사원이 로그인해서 받은 토큰만 다룬다
        if (!(context.getPrincipal() instanceof UsernamePasswordAuthenticationToken principal)) {
            return;
        }
        Employee employee;
        try {
            employee = authService.requireTokenEligible(principal.getName());
        } catch (BusinessException e) {
            log.warn("토큰 발급 거절: 사번={}, 사유={}", principal.getName(), e.getErrorCode());
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                    e.getErrorCode() + " " + e.getErrorMessage(), null));
        }
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            context.getClaims()
                    // 클레임은 인가 기록에 JSON 으로도 저장되고, 다시 읽을 때 허용 목록에 있는 타입만 된다.
                    // List.copyOf 같은 JDK 불변 리스트는 목록에 없어서 ArrayList 로 넣는다.
                    .claim("roles", new ArrayList<>(employee.sortedRoles()))
                    .claim("name", employee.getEmpNm());
        } else if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
            context.getClaims().claim("name", employee.getEmpNm());
        }
    }

    /**
     * 채널계·대외기관 시스템 토큰 (client credentials). 대외기관은 EXTERNAL 역할만 넣는다.
     * 채널계 시스템 토큰 (client credentials). sub 는 클라이언트 ID(기본값)이고, 사람 역할 대신 CHANNEL 하나와 판매채널코드를 넣는다.
     * 채널 클라이언트로 등록하지 않은 클라이언트가 이 방식으로 토큰을 받으면(설정 실수) 거절한다.
     */
    private static void customizeChannel(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        if (Boolean.TRUE.equals(context.getRegisteredClient().getClientSettings().getSetting(SecurityConfig.PARTNER_SETTING))) {
            // 대외기관 시스템: EXTERNAL 역할만 (판매채널 없음)
            context.getClaims()
                    .claim("roles", new ArrayList<>(List.of("EXTERNAL")))
                    .claim("name", context.getRegisteredClient().getClientName());
            return;
        }
        Object channelCd = context.getRegisteredClient().getClientSettings().getSetting(SecurityConfig.CHANNEL_CD_SETTING);
        if (channelCd == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT,
                    "채널 시스템으로 등록되지 않은 클라이언트입니다.", null));
        }
        context.getClaims()
                .claim("roles", new ArrayList<>(List.of("CHANNEL")))
                .claim("channel_cd", channelCd.toString())
                .claim("name", context.getRegisteredClient().getClientName());
    }
}
