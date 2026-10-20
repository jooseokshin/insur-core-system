package com.insur.insurcoresystem.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 처리가 끝나면 로그 MDC 의 거래 ID 를 제거한다.
 * 서블릿 스레드는 풀에서 재사용되므로, 지우지 않으면 다음 요청의 로그에 이전 거래의 globalTxId 가 붙는다.
 * MDC 에 값을 넣는 곳은 {@link CommonHeaderRequestBodyAdvice} 이다.
 */
@Component
public class MdcClearFilter extends OncePerRequestFilter {

    /** 로그 패턴(%X{globalTxId})에서 참조하는 MDC 키 */
    public static final String MDC_KEY = "globalTxId";

    /**
     * 요청을 그대로 다음 필터/서블릿으로 넘기고, 정상/예외 종료 여부와 관계없이 MDC 를 정리한다.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
