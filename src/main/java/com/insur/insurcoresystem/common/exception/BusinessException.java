package com.insur.insurcoresystem.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 업무 예외
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String errorMessage;

    /**
     * 요청 오류(HTTP 400)로 응답하는 업무 예외를 생성한다. 입력값 검증 실패 등 클라이언트가 고칠 수 있는 오류에 사용한다.
     *
     * @param errorCode    업무 오류코드 (예: E-CTR-001)
     * @param errorMessage 응답 전문에 담길 오류 메시지
     */
    public BusinessException(String errorCode, String errorMessage) {
        this(HttpStatus.BAD_REQUEST, errorCode, errorMessage);
    }

    /**
     * HTTP 상태를 지정해 업무 예외를 생성한다. 예외 메시지는 "[오류코드] 오류메시지" 형태로 구성된다.
     * 조회 대상 없음(404), 서버 측 한도 초과(500)처럼 400 이 아닌 상태로 응답해야 할 때 사용한다.
     *
     * @param status       응답 HTTP 상태
     * @param errorCode    업무 오류코드 (예: E-CTR-010)
     * @param errorMessage 응답 전문에 담길 오류 메시지
     */
    public BusinessException(HttpStatus status, String errorCode, String errorMessage) {
        super("[" + errorCode + "] " + errorMessage);
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
}
