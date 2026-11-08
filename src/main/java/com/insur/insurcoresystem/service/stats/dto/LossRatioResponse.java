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
 * 손해율 집계 응답. 손해율 = 발생손해액 ÷ 경과보험료 (사고년도 기준을 단순화한 정의, IBNR 미포함).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LossRatioResponse {

    /** 집계 기간 시작일 */
    @Schema(description = "집계 기간 시작일")
    private LocalDate fromDate;
    /** 집계 기간 종료일 */
    @Schema(description = "집계 기간 종료일")
    private LocalDate toDate;
    /** 묶는 단위 */
    @Schema(description = "묶는 단위 (CLASS, PRODUCT, COVERAGE)")
    private String groupBy;
    /** 묶음별 집계 (보험종목, 상품코드, 담보코드 순) */
    @Schema(description = "묶음별 집계 (보험종목, 상품코드, 담보코드 순)")
    private List<Row> rows;
    /** 전체 합계 */
    @Schema(description = "전체 합계")
    private Row total;

    /** 한 묶음의 집계 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        /** 보험종목코드 (전체 합계는 null) */
        @Schema(description = "보험종목코드 (전체 합계는 null)")
        private String insrClsCd;
        /** 상품코드 (PRODUCT 묶음. 상품 없이 접수한 계약은 null) */
        @Schema(description = "상품코드 (PRODUCT 묶음. 상품 없이 접수한 계약은 null)")
        private String prdCd;
        /** 담보코드 (COVERAGE 묶음) */
        @Schema(description = "담보코드 (COVERAGE 묶음)")
        private String cvrgCd;
        /** 기간 안에 보장한 계약 수 */
        @Schema(description = "기간 안에 보장한 계약 수")
        private long contractCount;
        /** 경과보험료 (보험료 × 기간 안 보장 일수 ÷ 보험기간 일수, 원 단위 반올림) */
        @Schema(description = "경과보험료 (보험료 × 기간 안 보장 일수 ÷ 보험기간 일수, 원 단위 반올림)")
        private BigDecimal earnedPremium;
        /** 사고 건수 (사고일이 기간 안, 면책·접수 취소 제외) */
        @Schema(description = "사고 건수 (사고일이 기간 안, 면책·접수 취소 제외)")
        private long claimCount;
        /** 지급 보험금 (지급 완료) */
        @Schema(description = "지급 보험금 (지급 완료)")
        private BigDecimal paidLoss;
        /** 미지급 추산액 (지급 대기는 사정 지급액, 접수는 청구액) */
        @Schema(description = "미지급 추산액 (지급 대기는 사정 지급액, 접수는 청구액)")
        private BigDecimal outstandingLoss;
        /** 발생손해액 = 지급 보험금 + 미지급 추산액 */
        @Schema(description = "발생손해액 = 지급 보험금 + 미지급 추산액")
        private BigDecimal incurredLoss;
        /** 손해율 = 발생손해액 ÷ 경과보험료 (소수 4자리, 경과보험료가 0이면 null) */
        @Schema(description = "손해율 = 발생손해액 ÷ 경과보험료 (소수 4자리, 경과보험료가 0이면 null)")
        private BigDecimal lossRatio;
    }
}
