package com.insur.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * 로그인 성공 처리. 보통은 로그인 전에 가려던 곳(인가 요청)으로 돌려보낸다.
 * 비밀번호를 바꿔야 하는 사원은 로그인 세션을 만들지 않고 변경 화면으로 보낸다. 세션에 인증이 없으므로
 * 인가 엔드포인트가 이 사원에게 인가 코드를 내줄 수 없다 (화면을 건너뛰어도 토큰을 못 받는다).
 */
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    /** 비밀번호 확인은 끝났고 새 비밀번호를 기다리는 사번 (세션 속성) */
    public static final String PENDING_PASSWORD_CHANGE = "insur.pendingPasswordChange";

    private final RequestCache requestCache = new HttpSessionRequestCache();
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, jakarta.servlet.ServletException {
        boolean mustChange = authentication.getAuthorities().stream()
                .anyMatch(a -> EmployeeAuthenticationProvider.PASSWORD_CHANGE_REQUIRED.equals(a.getAuthority()));
        if (!mustChange) {
            redirectStrategy.sendRedirect(request, response, takeSavedTarget(requestCache, request, response));
            return;
        }
        // 폼 로그인 필터가 이미 세션에 저장한 인증을 되돌린다
        SecurityContextHolder.clearContext();
        request.getSession().removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        request.getSession().setAttribute(PENDING_PASSWORD_CHANGE, authentication.getName());
        redirectStrategy.sendRedirect(request, response, "/auth/password-change");
    }

    /**
     * 로그인 전에 가려던 곳(보통 인가 요청)을 꺼낸다. 없으면 업무 화면 첫 화면.
     * 저장된 전체 주소는 프록시가 넘긴 Host 로 만들어져 포트가 빠질 수 있으므로, 경로와 쿼리만 써서 같은 출처로 돌아간다.
     */
    public static String takeSavedTarget(RequestCache cache, HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = cache.getRequest(request, response);
        cache.removeRequest(request, response);
        if (saved instanceof DefaultSavedRequest d) {
            return d.getRequestURI() + (d.getQueryString() != null ? "?" + d.getQueryString() : "");
        }
        return "/";
    }
}
