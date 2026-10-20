package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 계약상태 변경 요청 바디
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractStatusChangeRequest {

    /** 상태를 변경할 계약의 증권번호 */
    @Schema(description = "상태를 변경할 계약의 증권번호")
    private String contractNo;
    /** 변경할 계약상태코드 (20:유효, 30:해지, 90:청약취소) */
    @Schema(description = "변경할 계약상태코드 (20:유효, 30:해지, 90:청약취소)")
    private String ctrStatCd;
}
