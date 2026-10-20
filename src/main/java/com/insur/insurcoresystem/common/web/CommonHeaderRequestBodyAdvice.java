package com.insur.insurcoresystem.common.web;

import com.insur.insurcoresystem.common.dto.CommonHeader;
import com.insur.insurcoresystem.common.dto.CommonMessage;
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
 * 공통 전문 수신 직후 헤더를 정규화(globalTxId 미입력 시 채번)하고 요청 범위에 보관한다.
 */
@ControllerAdvice
public class CommonHeaderRequestBodyAdvice extends RequestBodyAdviceAdapter {

    /**
     * 이 Advice 의 적용 대상을 판단한다. 요청 바디가 공통 전문(CommonMessage)인 컨트롤러 파라미터에만 적용한다.
     *
     * @return 공통 전문 파라미터이면 true
     */
    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return CommonMessage.class.isAssignableFrom(methodParameter.getParameterType());
    }

    /**
     * 전문 바디를 읽은 직후 헤더를 정규화한다.
     * <ul>
     *     <li>헤더가 없으면 빈 헤더를 생성한다.</li>
     *     <li>globalTxId 가 비어 있으면 UUID 를 새로 채번한다.</li>
     *     <li>empId 는 요청에 담긴 값 대신 인증된 JWT 의 subject 로 설정한다.</li>
     *     <li>정규화된 헤더를 요청 범위에 보관해 정상/오류 응답이 같은 거래 정보를 사용하도록 한다.</li>
     *     <li>globalTxId 를 로그 MDC 에 넣어 이후 모든 로그에 거래 ID 가 붙도록 한다. (제거는 {@link MdcClearFilter})</li>
     * </ul>
     *
     * @return 헤더가 정규화된 요청 전문
     */
    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        CommonMessage<?> message = (CommonMessage<?>) body;
        CommonHeader header = message.getHeader();
        if (header == null) {
            header = new CommonHeader();
            message.setHeader(header);
        }
        if (header.getGlobalTxId() == null || header.getGlobalTxId().isBlank()) {
            header.setGlobalTxId(UUID.randomUUID().toString());
        }
        // 처리 사번은 클라이언트가 보낸 값을 믿지 않고 인증된 토큰의 subject 로 덮어쓴다. (사번 위조 방지)
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            header.setEmpId(authentication.getName());
        }
        CommonHeaderContext.set(header);
        // 아직 검증 전인 클라이언트 값이므로 줄바꿈 등을 정리해서 로그에 싣는다. (응답과 DB 에는 원래 값을 그대로 쓴다)
        MDC.put(MdcClearFilter.MDC_KEY, LogSanitizer.clean(header.getGlobalTxId()));
        return body;
    }
}
