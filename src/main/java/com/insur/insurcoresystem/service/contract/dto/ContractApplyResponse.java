package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 청약 응답 바디
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractApplyResponse {

    /** 채번된 증권번호 */
    @Schema(description = "채번된 증권번호")
    private String contractNo;
    /** 채번된 청약번호 */
    @Schema(description = "채번된 청약번호")
    private String applNo;
    /** 계약상태코드 (10:청약) */
    @Schema(description = "계약상태코드 (10:청약)")
    private String ctrStatCd;
    /** 저장된 총보험료 */
    @Schema(description = "저장된 총보험료")
    private BigDecimal totalPremium;
    /** 저장된 담보 건수 */
    @Schema(description = "저장된 담보 건수")
    private int coverageCount;
}
