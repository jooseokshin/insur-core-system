package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 목록의 한 행. 담보와 상태 이력은 포함하지 않으며, 상세는 계약 조회 API 로 확인한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractSummaryResponse {

    /** 증권번호 */
    @Schema(description = "증권번호")
    private String contractNo;
    /** 청약번호 */
    @Schema(description = "청약번호")
    private String applNo;
    /** 보험종목코드 (01:자동차, 02:화재) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재)")
    private String insrClsCd;
    /** 계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소) */
    @Schema(description = "계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소)")
    private String ctrStatCd;
    /** 보험시기 */
    @Schema(description = "보험시기")
    private LocalDate startDate;
    /** 보험종기 */
    @Schema(description = "보험종기")
    private LocalDate endDate;
    /** 계약자 ID */
    @Schema(description = "계약자 ID")
    private String policyholderId;
    /** 총보험료 */
    @Schema(description = "총보험료")
    private BigDecimal totalPremium;
    /** 시스템 등록일시 (청약 접수 시각) */
    @Schema(description = "시스템 등록일시 (청약 접수 시각)")
    private LocalDateTime sysRegDt;
    /** 청약 접수 사번 (이력 이전 계약은 null) */
    @Schema(description = "청약 접수 사번 (이력 이전 계약은 null)")
    private String regEmpId;
}
