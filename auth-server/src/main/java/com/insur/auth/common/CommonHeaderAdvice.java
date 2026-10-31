package com.insur.auth.common;

import java.lang.reflect.Type;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * 요청 전문의 헤더를 채운다. 거래 UUID 가 없으면 새로 만들고, 처리 사번은 인증된 토큰의 subject 로 덮어쓴다(로그인 요청은 비인증이라 그대로 둔다).
 * 요청 처리 중 오류 응답에서도 같은 거래 UUID 를 쓰도록 요청 속성에 보관한다.
 */
@ControllerAdvice
public class CommonHeaderAdvice extends RequestBodyAdviceAdapter {

    /** 요청 속성에 보관하는 헤더 키 */
    public static final String HEADER_ATTR = CommonHeaderAdvice.class.getName() + ".header";
    /** 로그 패턴(%X{globalTxId})에서 참조하는 MDC 키 */
    public static final String MDC_KEY = "globalTxId";

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return CommonMessage.class.isAssignableFrom(methodParameter.getParameterType());
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        CommonMessage<?> message = (CommonMessage<?>) body;
        CommonHeader header = message.getHeader();
        if (header == null) {
            header = new CommonHeader();
            message.setHeader(header);
        }
        if (header.getGlobalTxId() == null || header.getGlobalTxId().isBlank() || header.getGlobalTxId().length() > 64) {
            header.setGlobalTxId(UUID.randomUUID().toString());
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        header.setEmpId(authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName()) ? authentication.getName() : null);
        RequestHeaderHolder.set(header);
        MDC.put(MDC_KEY, header.getGlobalTxId().replaceAll("[\\r\\n\\t]", "_"));
        return body;
    }
}
