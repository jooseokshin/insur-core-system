package com.insur.auth.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 업무 예외. 오류코드와 메시지가 공통 전문 헤더로 응답된다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String errorMessage;

    /** 요청 오류(HTTP 400)로 응답하는 업무 예외 */
    public BusinessException(String errorCode, String errorMessage) {
        this(HttpStatus.BAD_REQUEST, errorCode, errorMessage);
    }

    /** HTTP 상태를 지정한 업무 예외 */
    public BusinessException(HttpStatus status, String errorCode, String errorMessage) {
        super("[" + errorCode + "] " + errorMessage);
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
}
