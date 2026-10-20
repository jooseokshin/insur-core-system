package com.insur.insurcoresystem.common.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.Arrays;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 테스트용 인증 헬퍼. 서명 검증을 거치지 않고 인증된 JWT 사용자(subject = 사번)로 요청을 보낸다.
 */
public final class TestAuth {

    public static final String EMP_ID = "E12345";

    private TestAuth() {
    }

    /** 사번 E12345, 모든 업무가 가능한 ADMIN 역할로 인증된 요청 */
    public static RequestPostProcessor emp() {
        return withRoles(Roles.ADMIN);
    }

    /** 사번 E12345 에 지정한 역할만 부여해 인증된 요청 (역할 이름은 ROLE_ 접두사 없이) */
    public static RequestPostProcessor withRoles(String... roles) {
        return as(EMP_ID, roles);
    }

    /** 지정한 사번과 역할로 인증된 요청 (역할 이름은 ROLE_ 접두사 없이) */
    public static RequestPostProcessor as(String empId, String... roles) {
        return jwt().jwt(token -> token.subject(empId))
                .authorities(Arrays.stream(roles)
                        .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList());
    }
}
