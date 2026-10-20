package com.insur.insurcoresystem.common.web;

/**
 * 클라이언트가 보낸 값을 로그에 남기기 전에 정리한다. 줄바꿈 같은 제어 문자가 든 값으로 로그 줄을 위조(log injection)하거나
 * 아주 긴 값으로 로그를 채우는 것을 막는다.
 */
public final class LogSanitizer {

    /** 로그에 남기는 값의 최대 길이 */
    private static final int MAX_LENGTH = 100;

    private LogSanitizer() {
    }

    /**
     * 제어 문자(줄바꿈, 탭 등과 유니코드 줄 구분자)를 '_'로 바꾸고 최대 길이를 넘으면 잘라 "..." 를 붙인다.
     *
     * @param value 로그에 남길 값
     * @return 정리된 값. null 이면 null
     */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]", "_");
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) + "..." : cleaned;
    }
}
