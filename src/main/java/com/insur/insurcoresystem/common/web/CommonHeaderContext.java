package com.insur.insurcoresystem.common.web;

import com.insur.insurcoresystem.common.dto.CommonHeader;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 현재 요청의 공통 전문 헤더를 요청 범위에 보관한다.
 * 정상/오류 응답 모두 같은 globalTxId, empId 를 돌려주기 위해 사용한다.
 */
public final class CommonHeaderContext {

    private static final String ATTR_NAME = CommonHeaderContext.class.getName();

    /** 유틸리티 클래스이므로 인스턴스 생성을 막는다. */
    private CommonHeaderContext() {
    }

    /**
     * 현재 요청의 공통 헤더를 요청 범위에 저장한다. 요청 컨텍스트가 없는 경우(웹 요청 외 스레드 등)에는 저장하지 않는다.
     *
     * @param header 저장할 공통 전문 헤더
     */
    public static void set(CommonHeader header) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(ATTR_NAME, header, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /**
     * 요청 범위에 저장된 공통 헤더를 조회한다.
     *
     * @return 저장된 헤더. 요청 컨텍스트가 없거나 전문을 읽기 전에 실패해 저장되지 않았다면 빈 Optional
     */
    public static Optional<CommonHeader> get() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        return Optional.ofNullable((CommonHeader) attributes.getAttribute(ATTR_NAME, RequestAttributes.SCOPE_REQUEST));
    }
}
