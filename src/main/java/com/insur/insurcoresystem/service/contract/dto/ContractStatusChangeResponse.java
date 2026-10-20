package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약상태 변경 응답 바디
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractStatusChangeResponse {

    /** 증권번호 */
    @Schema(description = "증권번호")
    private String contractNo;
    /** 변경 전 계약상태코드 */
    @Schema(description = "변경 전 계약상태코드")
    private String beforeCtrStatCd;
    /** 변경 후 계약상태코드 */
    @Schema(description = "변경 후 계약상태코드")
    private String afterCtrStatCd;
    /** 해지일 (해지(30)로 바꾼 경우에만, 그 외 null) */
    @Schema(description = "해지일 (해지(30)로 바꾼 경우에만, 그 외 null)")
    private LocalDate termDate;
    /** 해지 환급보험료 (해지(30)로 바꾼 경우에만, 그 외 null) */
    @Schema(description = "해지 환급보험료 (해지(30)로 바꾼 경우에만, 그 외 null)")
    private BigDecimal refundPremium;
}
