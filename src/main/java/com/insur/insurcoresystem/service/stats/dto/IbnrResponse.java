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
 * IBNR 산출 결과 (지급 기준 손해 진전 삼각형)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IbnrResponse {
    @Schema(description = "보험종목코드")
    private String insrClsCd;
    @Schema(description = "평가 기준일")
    private LocalDate evaluationDate;
    @Schema(description = "사고 기간 단위 (QUARTER, YEAR)")
    private String periodType;
    @Schema(description = "꼬리 진전계수")
    private BigDecimal tailFactor;
    @Schema(description = "BF 에 쓴 기대손해율 (구하지 못하면 null)")
    private BigDecimal expectedLossRatio;
    @Schema(description = "기대손해율을 실적에서 구했는지 (케이프코드)")
    private boolean elrEstimated;
    @Schema(description = "진전율이 이 값 이상이면 체인래더, 미만이면 BF 를 고른다")
    private double matureThreshold;
    @Schema(description = "경과별 진전계수")
    private List<Factor> factors;
    @Schema(description = "누적 지급액 삼각형 (사고 기간별 경과 1부터)")
    private List<TriangleRow> triangle;
    @Schema(description = "사고 기간별 결과 (오래된 것부터)")
    private List<Period> periods;
    @Schema(description = "합계")
    private Total total;
    @Schema(description = "해석할 때 주의할 점")
    private List<String> warnings;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Factor {
        @Schema(description = "경과 (k → k+1 계수, 마지막 경과는 꼬리)")
        private int age;
        @Schema(description = "진전계수")
        private BigDecimal factor;
        @Schema(description = "누적 진전계수 (이 경과부터 최종까지)")
        private BigDecimal cdf;
        @Schema(description = "계수 계산에 쓴 사고 기간 수 (0 이면 자료 없음 또는 꼬리)")
        private int dataPoints;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TriangleRow {
        @Schema(description = "사고 기간 (예: 2026Q3)")
        private String period;
        @Schema(description = "경과별 누적 지급액 (원, 아직 오지 않은 경과는 없음)")
        private List<BigDecimal> cells;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Period {
        @Schema(description = "사고 기간 (예: 2026Q3)")
        private String period;
        @Schema(description = "사고 기간 시작일")
        private LocalDate periodFrom;
        @Schema(description = "사고 기간 종료일 (포함)")
        private LocalDate periodTo;
        @Schema(description = "기준일의 경과")
        private int age;
        @Schema(description = "경과보험료 (원)")
        private BigDecimal earnedPremium;
        @Schema(description = "지급액 (원)")
        private BigDecimal paid;
        @Schema(description = "개별추산액 (원)")
        private BigDecimal caseReserve;
        @Schema(description = "누적 진전계수")
        private BigDecimal cdf;
        @Schema(description = "진전율 = 1 / 누적 진전계수")
        private BigDecimal developed;
        @Schema(description = "체인래더 최종손해액 (원)")
        private BigDecimal clUltimate;
        @Schema(description = "체인래더 IBNR (원, 음수 그대로)")
        private BigDecimal clIbnr;
        @Schema(description = "BF 최종손해액 (원, 기대손해율이 없으면 null)")
        private BigDecimal bfUltimate;
        @Schema(description = "BF IBNR (원, 음수 그대로)")
        private BigDecimal bfIbnr;
        @Schema(description = "고른 방법 (CL, BF)")
        private String method;
        @Schema(description = "적립 IBNR (원, 음수는 0)")
        private BigDecimal ibnr;
        @Schema(description = "최종손해율 = 고른 최종손해액 / 경과보험료 (경과보험료 0 이면 null)")
        private BigDecimal ultimateLossRatio;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Total {
        @Schema(description = "경과보험료 (원)")
        private BigDecimal earnedPremium;
        @Schema(description = "지급액 (원)")
        private BigDecimal paid;
        @Schema(description = "개별추산액 (원)")
        private BigDecimal caseReserve;
        @Schema(description = "체인래더 IBNR 합계 (원, 기간별 음수는 0)")
        private BigDecimal clIbnr;
        @Schema(description = "BF IBNR 합계 (원, 기간별 음수는 0, 기대손해율이 없으면 null)")
        private BigDecimal bfIbnr;
        @Schema(description = "적립 IBNR (원)")
        private BigDecimal ibnr;
        @Schema(description = "지급준비금 = 개별추산액 + 적립 IBNR (원)")
        private BigDecimal reserve;
    }
}
