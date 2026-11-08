package com.insur.auth.security;

import com.insur.auth.common.BusinessException;
import com.insur.auth.domain.Employee;
import com.insur.auth.service.AuthService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * 로그인 화면의 사번·비밀번호를 {@link AuthService#authenticate} 로 확인한다. 잠금, 실패 횟수, 로그인 이력 규칙은 그대로다.
 * <p>
 * 로그인 세션의 사용자는 표준 {@link User} 로 만든다. 인가 기록(oauth2_authorization)에 JSON 으로 저장되는데,
 * 직접 만든 클래스는 역직렬화 허용 목록에 없어서 저장한 기록을 다시 읽지 못한다.
 * 비밀번호를 바꿔야 하는 사원은 업무 역할 대신 {@link #PASSWORD_CHANGE_REQUIRED} 하나만 받는다.
 * 비밀번호로 인증한 시각은 {@link FactorGrantedAuthority}(FACTOR_PASSWORD)에 남는다. ID 토큰의 auth_time 이 이 값이다.
 */
@Component
public class EmployeeAuthenticationProvider implements AuthenticationProvider {

    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

    private final AuthService authService;
    private final Clock clock;

    public EmployeeAuthenticationProvider(AuthService authService, Clock clock) {
        this.authService = authService;
        this.clock = clock;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String clientIp = authentication.getDetails() instanceof WebAuthenticationDetails details
                ? details.getRemoteAddress() : null;
        Object credentials = authentication.getCredentials();
        try {
            Employee employee = authService.authenticate(authentication.getName(),
                    credentials == null ? null : credentials.toString(), clientIp);
            return authenticated(employee, clock.instant());
        } catch (BusinessException e) {
            throw new EmployeeLoginException(e.getErrorCode(), e.getErrorMessage());
        }
    }

    /** 사원으로 로그인 세션용 인증 객체를 만든다. (비밀번호 변경 화면이 변경을 마친 뒤에도 쓴다) */
    public static Authentication authenticated(Employee employee, Instant authenticatedAt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (employee.isPasswordChangeRequired()) {
            authorities.add(new SimpleGrantedAuthority(PASSWORD_CHANGE_REQUIRED));
        } else {
            employee.sortedRoles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }
        authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(authenticatedAt).build());
        User user = new User(employee.getEmpId(), "", authorities);
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
