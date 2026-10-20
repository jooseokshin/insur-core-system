package com.insur.insurcoresystem.common.exception;

import com.insur.insurcoresystem.common.dto.CommonHeader;
import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.web.CommonHeaderContext;
import com.insur.insurcoresystem.common.web.LogSanitizer;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 예외를 공통 전문 형태의 오류 응답으로 변환한다.
 * <ul>
 *     <li>업무 예외(BusinessException): 업무 오류코드, 예외에 지정된 HTTP 상태 (기본 400, 조회 대상 없음 404, 채번 한도 초과 500)</li>
 *     <li>Spring MVC 표준 예외(전문 파싱 실패, 미지원 메서드 등): E-CMN-0xx, 해당 HTTP 상태</li>
 *     <li>그 외 시스템 예외: E-SYS-999, HTTP 500 (내부 메시지는 응답에 노출하지 않고 로그로만 남긴다)</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String SYSTEM_ERROR_CODE = "E-SYS-999";
    static final String SYSTEM_ERROR_MSG = "시스템 오류가 발생했습니다. 관리자에게 문의해 주세요.";

    /**
     * 업무 예외를 공통 전문 오류 응답으로 변환한다. 예외에 담긴 업무 오류코드/메시지를 그대로 응답하며,
     * HTTP 상태는 예외에 지정된 값(기본 400)을 따른다. 서버 측 오류(5xx)는 error, 그 외는 warn 으로 로그를 남긴다.
     *
     * @param e 업무 예외
     * @return 오류 응답 전문 (예외에 지정된 HTTP 상태)
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<CommonMessage<Void>> handleBusinessException(BusinessException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("업무 오류(서버 측): {}", LogSanitizer.clean(e.getMessage()));
        } else {
            log.warn("업무 오류: {}", LogSanitizer.clean(e.getMessage()));
        }
        return ResponseEntity.status(e.getStatus())
                .body(errorMessage(e.getErrorCode(), e.getErrorMessage()));
    }

    /**
     * 처리되지 않은 모든 시스템 예외를 공통 전문 오류 응답으로 변환한다.
     * 내부 예외 메시지는 응답에 노출하지 않고, 거래 추적을 위해 globalTxId 와 함께 로그로만 남긴다.
     *
     * @param e 처리되지 않은 예외
     * @return 오류 응답 전문 (HTTP 500)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonMessage<Void>> handleSystemException(Exception e) {
        CommonMessage<Void> body = errorMessage(SYSTEM_ERROR_CODE, SYSTEM_ERROR_MSG);
        log.error("[{}] 시스템 오류", LogSanitizer.clean(body.getHeader().getGlobalTxId()), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * ResponseEntityExceptionHandler 가 처리하는 Spring MVC 표준 예외의 응답 바디(ProblemDetail)를 공통 전문으로 바꾼다.
     */
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

    /**
     * 요청 범위에 보관된 헤더의 globalTxId, empId 를 이어받는다.
     * 전문을 읽기 전에 실패한 경우(파싱 오류 등)에는 globalTxId 를 새로 생성한다.
     */
    private CommonMessage<Void> errorMessage(String errorCode, String errorMessage) {
        CommonHeader reqHeader = CommonHeaderContext.get().orElse(null);
        CommonHeader header = CommonHeader.builder()
                .globalTxId(reqHeader != null ? reqHeader.getGlobalTxId() : UUID.randomUUID().toString())
                .empId(reqHeader != null ? reqHeader.getEmpId() : null)
                .responseCode(errorCode)
                .responseMsg(errorMessage)
                .build();
        return new CommonMessage<>(header, null);
    }
}
