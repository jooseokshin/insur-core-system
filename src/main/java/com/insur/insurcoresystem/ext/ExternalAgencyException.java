package com.insur.insurcoresystem.ext;

/**
 * 대외기관 연계 실패 (연결·응답 시간 초과, 기관 오류 응답, 전문 해석 실패, FEP 차단). 업무 오류가 아니라 일시 장애일 수 있어서,
 * 호출하는 쪽이 "보류 후 다시 시도"(청약) 또는 "잠시 후 다시"(손해사정) 중 업무에 맞게 처리한다.
 * <p>
 * {@link #isMaybeDelivered()} 가 중요하다. 응답이 없었던 경우(시간 초과)는 기관이 이미 처리했을 수 있어서 이체처럼 돈이 움직이는 거래는
 * 같은 요청을 그냥 다시 보내면 안 되고 결과를 먼저 조회해야 한다. FEP 가 보내기 전에 막은 경우(종료·회선 차단)는 기관에 가지 않았으므로 다시 보내도 된다.
 */
public class ExternalAgencyException extends RuntimeException {

    private final String agency;
    private final String responseCode;
    private final boolean maybeDelivered;

    public ExternalAgencyException(String agency, String responseCode, String message) {
        super(message);
        this.agency = agency;
        this.responseCode = responseCode;
        this.maybeDelivered = responseCode == null;
    }

    public ExternalAgencyException(String agency, String message, Throwable cause) {
        super(message, cause);
        this.agency = agency;
        this.responseCode = null;
        this.maybeDelivered = true;
    }

    private ExternalAgencyException(String agency, String message, boolean maybeDelivered) {
        super(message);
        this.agency = agency;
        this.responseCode = null;
        this.maybeDelivered = maybeDelivered;
    }

    /** FEP 가 보내지 않은 실패 (기관 종료, 회선 차단). 기관에 가지 않았다 */
    public static ExternalAgencyException notSent(String agency, String message) {
        return new ExternalAgencyException(agency, message, false);
    }

    public String getAgency() {
        return agency;
    }

    /** 기관 응답코드 (연결 실패처럼 응답이 없으면 null) */
    public String getResponseCode() {
        return responseCode;
    }

    /** 기관이 요청을 받았을 수도 있는지 (응답 없음 = true, 오류 응답이나 FEP 차단 = false) */
    public boolean isMaybeDelivered() {
        return maybeDelivered;
    }
}
