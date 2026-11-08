package com.insur.insurcoresystem.common.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 사원(토큰 sub)별 호출 제한. 보안 필터 체인 안에서 토큰 검증 바로 뒤에 둔다 (SecurityConfig).
 * 토큰이 없는 요청은 여기서 세지 않는다. 그런 요청은 곧바로 401 이 되고, IP 단위 제한은 앞단 nginx 가 맡는다.
 * 일부러 @Component 로 등록하지 않는다. 등록하면 서블릿 필터로도 한 번 더 걸리는데, 그때는 인증 정보가 없다.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String CODE = "E-CMN-010";
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter rateLimiter;

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !rateLimiter.enabled() || !path(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken) || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        RateLimitStore.Decision decision = rateLimiter.tryConsume(auth.getName(), path(request));
        if (decision.allowed()) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
            chain.doFilter(request, response);
            return;
        }
        // 사번과 경로는 토큰 검증을 통과한 값과 서버 경로라 로그에 그대로 남겨도 된다
        log.warn("호출 제한 초과 empId={} path={} retryAfter={}s", auth.getName(), path(request), decision.retryAfterSeconds());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"header":{"globalTxId":"%s","empId":null,"responseCode":"%s","responseMsg":"요청이 너무 많습니다. %d초 뒤에 다시 시도해 주세요."},"body":null}"""
                .formatted(UUID.randomUUID(), CODE, decision.retryAfterSeconds()));
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
