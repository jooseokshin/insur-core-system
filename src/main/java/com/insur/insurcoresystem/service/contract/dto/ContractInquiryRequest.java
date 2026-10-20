package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 계약 조회 요청 바디. 증권번호 또는 청약번호 중 하나만 입력한다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractInquiryRequest {

    /** 증권번호 (예: POL-CAR-2026-000001) */
    @Schema(description = "증권번호 (예: POL-CAR-2026-000001)")
    private String contractNo;
    /** 청약번호 (예: APP-CAR-2026-000001) */
    @Schema(description = "청약번호 (예: APP-CAR-2026-000001)")
    private String applNo;
}
