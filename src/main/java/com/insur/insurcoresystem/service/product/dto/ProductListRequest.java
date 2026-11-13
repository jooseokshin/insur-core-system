package com.insur.insurcoresystem.service.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 상품 목록 조회 요청 바디. 조건은 모두 선택이다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductListRequest {

    /** 보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행, 05:실손의료) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행, 05:실손의료)")
    private String insrClsCd;
    /** 오늘 판매 중인 상품만 (생략 시 false) */
    @Schema(description = "오늘 판매 중인 상품만 (생략 시 false)")
    private Boolean onSaleOnly;
    /** 상품코드 (지정하면 그 상품 하나만) */
    @Schema(description = "상품코드 (지정하면 그 상품 하나만)")
    private String prdCd;
}
