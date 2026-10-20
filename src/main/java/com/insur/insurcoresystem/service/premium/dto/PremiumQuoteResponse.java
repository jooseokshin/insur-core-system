package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보험료 산출 응답 바디. 담보별 보험료와 총보험료(담보별 보험료의 합)를 담으며, 청약 요청에 그대로 쓸 수 있다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PremiumQuoteResponse {

    /** 보험종목코드 */
    @Schema(description = "보험종목코드")
    private String insrClsCd;
    /** 보험기간 일수 (보험시기 ~ 보험종기, 종기 당일 제외) */
    @Schema(description = "보험기간 일수 (보험시기 ~ 보험종기, 종기 당일 제외)")
    private long days;
    /** 이 보험종목과 보험시기에 입력이 필수인 계수 종류 (없으면 빈 목록). 청약에서도 같은 계수를 입력해야 한다 */
    @Schema(description = "이 보험종목과 보험시기에 입력이 필수인 계수 종류 (없으면 빈 목록). 청약에서도 같은 계수를 입력해야 한다")
    private List<String> requiredFactors;
    /** 적용한 요율 계수 (입력한 순서와 무관하게 종류별로, 없으면 빈 목록) */
    @Schema(description = "적용한 요율 계수 (입력한 순서와 무관하게 종류별로, 없으면 빈 목록)")
    private List<AppliedFactor> factors;
    /** 적용한 요율 계수들의 곱 (없으면 1) */
    @Schema(description = "적용한 요율 계수들의 곱 (없으면 1)")
    private BigDecimal combinedFactor;
    /** 담보별 산출 결과 (요청한 순서) */
    @Schema(description = "담보별 산출 결과 (요청한 순서)")
    private List<QuoteCoverageResponse> coverages;
    /** 총보험료 (담보별 보험료의 합) */
    @Schema(description = "총보험료 (담보별 보험료의 합)")
    private BigDecimal totalPremium;

    /** 담보 하나의 산출 결과 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuoteCoverageResponse {

        /** 담보코드 */
        @Schema(description = "담보코드")
        private String cvrgCd;
        /** 담보명 */
        @Schema(description = "담보명")
        private String cvrgNm;
        /** 가입금액 */
        @Schema(description = "가입금액")
        private BigDecimal isrdAmt;
        /** 적용한 연 요율 (계수 적용 전 기본 요율) */
        @Schema(description = "적용한 연 요율 (계수 적용 전 기본 요율)")
        private BigDecimal annualRate;
        /** 산출한 담보 보험료 (원 단위, 요율 계수 적용 후) */
        @Schema(description = "산출한 담보 보험료 (원 단위, 요율 계수 적용 후)")
        private BigDecimal prem;
    }
}
