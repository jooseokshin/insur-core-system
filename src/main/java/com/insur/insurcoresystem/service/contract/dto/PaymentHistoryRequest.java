package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 계약 납입 내역 조회 요청 바디
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentHistoryRequest {

    /** 납입 내역을 조회할 계약의 증권번호 */
    @Schema(description = "납입 내역을 조회할 계약의 증권번호")
    private String contractNo;
    /** 페이지 번호 (0부터 시작, 생략하면 0) */
    @Schema(description = "페이지 번호 (0부터 시작, 생략하면 0)")
    private Integer page;
    /** 페이지 크기 (1~100, 생략하면 20) */
    @Schema(description = "페이지 크기 (1~100, 생략하면 20)")
    private Integer size;
}
