package com.insur.insurcoresystem.service.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요율 영향 시뮬레이션 결과. 금액은 원 단위 반올림, 비율은 소수 넷째 자리, 변경률(%)은 소수 둘째 자리.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateImpactResponse {

    /** 보험종목 코드 */
    @Schema(description = "보험종목 코드")
    private String insrClsCd;
    /** 경험 기간 시작일 */
    @Schema(description = "경험 기간 시작일")
    private LocalDate fromDate;
    /** 경험 기간 종료일 */
    @Schema(description = "경험 기간 종료일")
    private LocalDate toDate;
    /** 목표 손해율 (요청값, 없으면 null) */
    @Schema(description = "목표 손해율 (요청값, 없으면 null)")
    private BigDecimal targetLossRatio;
    /** 갱신 영향 기간 시작일 (오늘) */
    @Schema(description = "갱신 영향 기간 시작일 (오늘)")
    private LocalDate renewalFrom;
    /** 갱신 영향 기간 종료일 (오늘 + renewalWindowDays) */
    @Schema(description = "갱신 영향 기간 종료일 (오늘 + renewalWindowDays)")
    private LocalDate renewalTo;
    /** 완전 신뢰도 기준 사고 건수 (1,082건: 90% 확률로 ±5% 안, 제한변동 신뢰도) */
    @Schema(description = "완전 신뢰도 기준 사고 건수 (1,082건: 90% 확률로 ±5% 안, 제한변동 신뢰도)")
    private long fullCredibilityClaims;
    /** 담보별 결과 (현재 요율표의 담보 순서) */
    @Schema(description = "담보별 결과 (현재 요율표의 담보 순서)")
    private List<Row> rows;
    /** 보험종목 전체 (계약·사고 건수는 중복 없이 센다) */
    @Schema(description = "보험종목 전체 (계약·사고 건수는 중복 없이 센다)")
    private Row total;
    /** 해석할 때 조심할 점 (기간 중 요율 개정, 최근 사고 미보고, 낮은 신뢰도 등) */
    @Schema(description = "해석할 때 조심할 점 (기간 중 요율 개정, 최근 사고 미보고, 낮은 신뢰도 등)")
    private List<String> warnings;

    /** 담보(또는 전체) 한 줄 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        /** 담보 코드 (전체 행은 null) */
        @Schema(description = "담보 코드 (전체 행은 null)")
        private String cvrgCd;
        /** 담보명 (전체 행은 null) */
        @Schema(description = "담보명 (전체 행은 null)")
        private String cvrgNm;
        /** 오늘 적용 중인 연 요율 (전체 행은 null) */
        @Schema(description = "오늘 적용 중인 연 요율 (전체 행은 null)")
        private BigDecimal currentRate;
        /** 시뮬레이션 연 요율 = 현재 × (1 + 변경률) (전체 행은 null) */
        @Schema(description = "시뮬레이션 연 요율 = 현재 × (1 + 변경률) (전체 행은 null)")
        private BigDecimal newRate;
        /** 적용한 변경률(%). 전체 행은 경과보험료 가중 평균 */
        @Schema(description = "적용한 변경률(%). 전체 행은 경과보험료 가중 평균")
        private BigDecimal rateChangePct;
        /** 경험 기간 경과보험료 */
        @Schema(description = "경험 기간 경과보험료")
        private BigDecimal earnedPremium;
        /** 경험 기간 발생손해액 (지급 + 미지급 추산) */
        @Schema(description = "경험 기간 발생손해액 (지급 + 미지급 추산)")
        private BigDecimal incurredLoss;
        /** 경험 기간 사고 건수 */
        @Schema(description = "경험 기간 사고 건수")
        private long claimCount;
        /** 현재 손해율 = 발생손해액 ÷ 경과보험료 (경과보험료 0 이면 null) */
        @Schema(description = "현재 손해율 = 발생손해액 ÷ 경과보험료 (경과보험료 0 이면 null)")
        private BigDecimal lossRatio;
        /** 새 요율이었다면의 손해율 = 발생손해액 ÷ (경과보험료 × (1 + 변경률)) */
        @Schema(description = "새 요율이었다면의 손해율 = 발생손해액 ÷ (경과보험료 × (1 + 변경률))")
        private BigDecimal simulatedLossRatio;
        /** 필요 변경률(%) = 손해율 ÷ 목표 손해율 − 1 (손해율법). 목표가 없거나 경과보험료 0 이면 null */
        @Schema(description = "필요 변경률(%) = 손해율 ÷ 목표 손해율 − 1 (손해율법). 목표가 없거나 경과보험료 0 이면 null")
        private BigDecimal indicatedChangePct;
        /** 신뢰도 Z = min(1, √(사고 건수 ÷ 1,082)) */
        @Schema(description = "신뢰도 Z = min(1, √(사고 건수 ÷ 1,082))")
        private BigDecimal credibility;
        /** 신뢰도 반영 변경률(%) = Z × 필요 변경률 (나머지 1 − Z 는 현재 요율 유지로 본다) */
        @Schema(description = "신뢰도 반영 변경률(%) = Z × 필요 변경률 (나머지 1 − Z 는 현재 요율 유지로 본다)")
        private BigDecimal credibilityWeightedChangePct;
        /** 오늘 유효한 계약 수 */
        @Schema(description = "오늘 유효한 계약 수")
        private long inForceContracts;
        /** 오늘 유효한 계약의 보험료 합계 (현재 요율 기준) */
        @Schema(description = "오늘 유효한 계약의 보험료 합계 (현재 요율 기준)")
        private BigDecimal inForcePremium;
        /** 갱신 영향 기간 안에 만기가 오고 아직 갱신하지 않은 유효 계약 수 */
        @Schema(description = "갱신 영향 기간 안에 만기가 오고 아직 갱신하지 않은 유효 계약 수")
        private long renewalContracts;
        /** 그 계약들의 현재 보험료 합계 */
        @Schema(description = "그 계약들의 현재 보험료 합계")
        private BigDecimal renewalPremium;
        /** 같은 조건으로 갱신할 때 보험료 증감 = 현재 보험료 × 변경률 */
        @Schema(description = "같은 조건으로 갱신할 때 보험료 증감 = 현재 보험료 × 변경률")
        private BigDecimal renewalPremiumChange;
    }
}
