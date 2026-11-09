package com.insur.insurcoresystem.ext;

/**
 * 대외기관 연계 실패 (연결·응답 시간 초과, 기관 오류 응답, 전문 해석 실패). 업무 오류가 아니라 일시 장애일 수 있어서,
 * 호출하는 쪽이 "보류 후 다시 시도"(청약) 또는 "잠시 후 다시"(손해사정) 중 업무에 맞게 처리한다.
 */
public class ExternalAgencyException extends RuntimeException {

    private final String agency;
    private final String responseCode;

    public ExternalAgencyException(String agency, String responseCode, String message) {
        super(message);
        this.agency = agency;
        this.responseCode = responseCode;
    }

    public ExternalAgencyException(String agency, String message, Throwable cause) {
        super(message, cause);
        this.agency = agency;
        this.responseCode = null;
    }

    public String getAgency() {
        return agency;
    }

    /** 기관 응답코드 (연결 실패처럼 응답이 없으면 null) */
    public String getResponseCode() {
        return responseCode;
    }
}
