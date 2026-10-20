package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 갱신 보험료 미리보기 응답 바디. 저장하는 것은 없다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRenewPreviewResponse {

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
    /** 갱신 시점 요율로 산출한 총보험료 */
    @Schema(description = "갱신 시점 요율로 산출한 총보험료")
    private BigDecimal totalPremium;
    /** 총보험료 증감 (갱신 − 원 계약, 음수면 인하) */
    @Schema(description = "총보험료 증감 (갱신 − 원 계약, 음수면 인하)")
    private BigDecimal premiumDifference;
    /** 적용한 요율 계수의 곱 */
    @Schema(description = "적용한 요율 계수의 곱")
    private BigDecimal combinedFactor;
    /** 적용된 필수 요율 계수 종류 */
    @Schema(description = "적용된 필수 요율 계수 종류")
    private List<String> requiredFactors;
    /** 담보별 보험료 비교 */
    @Schema(description = "담보별 보험료 비교")
    private List<Item> coverages;

    /** 담보 하나의 보험료 비교 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        /** 담보코드 */
        @Schema(description = "담보코드")
        private String cvrgCd;
        /** 가입금액 */
        @Schema(description = "가입금액")
        private BigDecimal isrdAmt;
        /** 원 계약의 담보 보험료 */
        @Schema(description = "원 계약의 담보 보험료")
        private BigDecimal previousPrem;
        /** 갱신 담보 보험료 */
        @Schema(description = "갱신 담보 보험료")
        private BigDecimal prem;
    }
}
