package com.insur.auth.security;

import com.insur.auth.common.BusinessException;
import com.insur.auth.domain.Employee;
import com.insur.auth.service.AuthService;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
        // 사원이 로그인해서 받은 토큰만 다룬다 (앞으로 클라이언트 자격 증명 같은 기계 토큰이 생기면 여기를 지난다)
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
}
