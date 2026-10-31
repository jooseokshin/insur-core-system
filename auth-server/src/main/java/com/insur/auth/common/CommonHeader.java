package com.insur.auth.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 공통 전문 헤더. 코어 백엔드와 같은 규약(거래 UUID, 처리 사번, 응답 코드/메시지)을 쓴다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommonHeader {

    public static final String SUCCESS_CODE = "0000";
    public static final String SUCCESS_MSG = "정상 처리되었습니다.";

    /** 거래 UUID */
    private String globalTxId;
    /** 처리 사번 */
    private String empId;
    /** 응답 코드 (성공: 0000) */
    private String responseCode;
    /** 응답 메시지 */
    private String responseMsg;
}
