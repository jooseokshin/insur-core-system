package com.insur.insurcoresystem.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 공통 전문 (헤더 + 바디)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CommonMessage<T> {

    /** 공통 전문 헤더 (거래 ID, 처리 사번, 응답 코드/메시지) */
    @Schema(description = "공통 전문 헤더 (거래 ID, 처리 사번, 응답 코드/메시지)")
    private CommonHeader header;
    /** 업무별 전문 바디 (요청/응답 DTO). 오류 응답에서는 null */
    @Schema(description = "업무별 전문 바디 (요청/응답 DTO). 오류 응답에서는 null")
    private T body;
}
