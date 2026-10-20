package com.insur.insurcoresystem.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 공통 전문 헤더
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
    @Schema(description = "거래 UUID")
    private String globalTxId;
    /** 처리 사번 */
    @Schema(description = "처리 사번")
    private String empId;
    /** 응답 코드 (성공: 0000) */
    @Schema(description = "응답 코드 (성공: 0000)")
    private String responseCode;
    /** 응답 메시지 */
    @Schema(description = "응답 메시지")
    private String responseMsg;
}
