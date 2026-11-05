package com.insur.insurcoresystem.service.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 한 건과 담보 구성
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {

    /** 상품코드 */
    @Schema(description = "상품코드")
    private String prdCd;
    /** 상품명 */
    @Schema(description = "상품명")
    private String prdNm;
    /** 보험종목코드 (01:자동차, 02:화재) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재)")
    private String insrClsCd;
    /** 상품 설명 */
    @Schema(description = "상품 설명")
    private String prdDesc;
    /** 판매 시작일 */
    @Schema(description = "판매 시작일")
    private LocalDate saleFrom;
    /** 판매 종료일 (없으면 판매 중) */
    @Schema(description = "판매 종료일 (없으면 판매 중)")
    private LocalDate saleTo;
    /** 오늘(서울 기준) 판매 중인지 */
    @Schema(description = "오늘(서울 기준) 판매 중인지")
    private boolean onSale;
    /** 담보 구성 (주계약 먼저, 표시 순서대로) */
    @Schema(description = "담보 구성 (주계약 먼저, 표시 순서대로)")
    private List<Coverage> coverages;

    /** 상품의 담보 하나 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Coverage {
        /** 담보코드 */
        @Schema(description = "담보코드")
        private String cvrgCd;
        /** 담보명 (오늘 적용 중인 요율표 기준, 요율이 없으면 null) */
        @Schema(description = "담보명 (오늘 적용 중인 요율표 기준, 요율이 없으면 null)")
        private String cvrgNm;
        /** 담보 구분 (MAIN: 주계약, RIDER: 특약) */
        @Schema(description = "담보 구분 (MAIN: 주계약, RIDER: 특약)")
        private String cvrgType;
        /** 필수 가입 여부 */
        @Schema(description = "필수 가입 여부")
        private boolean required;
        /** 가입금액 최소 */
        @Schema(description = "가입금액 최소")
        private BigDecimal minIsrdAmt;
        /** 가입금액 최대 */
        @Schema(description = "가입금액 최대")
        private BigDecimal maxIsrdAmt;
        /** 자기부담금 (보상 때 손해액에서 빼는 금액) */
        @Schema(description = "자기부담금 (보상 때 손해액에서 빼는 금액)")
        private BigDecimal deductibleAmt;
    }
}
