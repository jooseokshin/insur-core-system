package com.insur.auth.domain;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 시도 이력. 추가 전용이며 없는 사번으로 시도한 것도 남긴다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LoginHistory {

    public static final String SUCCESS = "SUCCESS";
    public static final String BAD_CREDENTIALS = "BAD_CREDENTIALS";
    public static final String LOCKED = "LOCKED";
    public static final String DISABLED = "DISABLED";

    private Long histId;

    /** 입력한 사번 (64자까지) */
    private String empId;

    /** 결과 (SUCCESS, BAD_CREDENTIALS, LOCKED, DISABLED) */
    private String resultCd;

    /** 접속 IP */
    private String clientIp;

    /** 시도 일시 */
    private LocalDateTime loginDt;
}
