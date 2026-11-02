package com.insur.auth.common;

import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 현재 요청의 공통 전문 헤더를 요청 범위에 보관한다. 요청이 끝나면 요청 속성과 함께 사라지므로 따로 지울 필요가 없다.
 */
public final class RequestHeaderHolder {

    private RequestHeaderHolder() {
    }

    static void set(CommonHeader header) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(CommonHeaderAdvice.HEADER_ATTR, header, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** 현재 요청의 헤더. 전문을 읽기 전이면 비어 있다 */
    public static Optional<CommonHeader> get() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? Optional.empty()
                : Optional.ofNullable((CommonHeader) attributes.getAttribute(CommonHeaderAdvice.HEADER_ATTR,
                        RequestAttributes.SCOPE_REQUEST));
    }
}
