package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 갱신 응답 바디. 갱신 계약은 새 청약(상태 10)으로 만들어지며 이후 일반 청약과 같은 흐름을 탄다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRenewResponse {

    /** 갱신 계약의 증권번호 */
    @Schema(description = "갱신 계약의 증권번호")
    private String contractNo;
    /** 갱신 계약의 청약번호 */
    @Schema(description = "갱신 계약의 청약번호")
    private String applNo;
    /** 갱신 계약의 계약상태코드 (10:청약) */
    @Schema(description = "갱신 계약의 계약상태코드 (10:청약)")
    private String ctrStatCd;
    /** 원 계약의 증권번호 */
    @Schema(description = "원 계약의 증권번호")
    private String renewedFrom;
    /** 갱신 계약의 보험시기 (원 계약의 보험종기) */
    @Schema(description = "갱신 계약의 보험시기 (원 계약의 보험종기)")
    private LocalDate startDate;
    /** 갱신 계약의 보험종기 */
    @Schema(description = "갱신 계약의 보험종기")
    private LocalDate endDate;
    /** 원 계약의 총보험료 */
    @Schema(description = "원 계약의 총보험료")
    private BigDecimal previousTotalPremium;
    /** 갱신 계약의 총보험료 (갱신 시점의 요율표 기준) */
    @Schema(description = "갱신 계약의 총보험료 (갱신 시점의 요율표 기준)")
    private BigDecimal totalPremium;
    /** 갱신 계약의 담보 건수 */
    @Schema(description = "갱신 계약의 담보 건수")
    private int coverageCount;
}
