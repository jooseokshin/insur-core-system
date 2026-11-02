package com.insur.auth.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 예외를 공통 전문 오류 응답으로 바꾼다. 업무 예외는 지정한 상태와 코드로, 그 외는 E-SYS-999(500)로 응답하고 내부 메시지는 로그에만 남긴다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String SYSTEM_ERROR_CODE = "E-SYS-999";
    static final String SYSTEM_ERROR_MSG = "시스템 오류가 발생했습니다. 관리자에게 문의해 주세요.";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<CommonMessage<Void>> handleBusinessException(BusinessException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("업무 오류(서버 측): {}", e.getMessage());
        } else {
            log.warn("업무 오류: {}", e.getMessage());
        }
        return ResponseEntity.status(e.getStatus()).body(errorMessage(e.getErrorCode(), e.getErrorMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonMessage<Void>> handleSystemException(Exception e) {
        CommonMessage<Void> body = errorMessage(SYSTEM_ERROR_CODE, SYSTEM_ERROR_MSG);
        log.error("[{}] 시스템 오류", body.getHeader().getGlobalTxId(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        CommonMessage<Void> message = switch (statusCode.value()) {
            case 400 -> errorMessage("E-CMN-002", "요청 전문 형식이 올바르지 않습니다.");
            case 404 -> errorMessage("E-CMN-003", "요청한 경로를 찾을 수 없습니다.");
            case 405 -> errorMessage("E-CMN-004", "허용되지 않은 HTTP 메서드입니다.");
            case 415 -> errorMessage("E-CMN-005", "지원하지 않는 Content-Type입니다.");
            default -> statusCode.is5xxServerError()
                    ? errorMessage(SYSTEM_ERROR_CODE, SYSTEM_ERROR_MSG)
                    : errorMessage("E-CMN-009", "요청을 처리할 수 없습니다.");
        };
        return new ResponseEntity<>(message, headers, statusCode);
    }

    private static CommonMessage<Void> errorMessage(String code, String msg) {
        CommonHeader req = RequestHeaderHolder.get().orElse(null);
        return new CommonMessage<>(CommonHeader.builder()
                .globalTxId(req != null ? req.getGlobalTxId() : UUID.randomUUID().toString())
                .empId(req != null ? req.getEmpId() : null)
                .responseCode(code)
                .responseMsg(msg)
                .build(), null);
    }

    /** 요청이 끝나면 로그 MDC 의 거래 ID 를 지운다. (서블릿 스레드는 재사용되므로) */
    @Component
    static class MdcClearFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            try {
                chain.doFilter(request, response);
            } finally {
                MDC.remove(CommonHeaderAdvice.MDC_KEY);
            }
        }
    }
}
