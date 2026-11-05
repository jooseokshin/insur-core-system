package com.insur.insurcoresystem.service.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 목록 응답
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductListResponse {

    /** 상품 목록 (보험종목, 상품코드순) */
    @Schema(description = "상품 목록 (보험종목, 상품코드순)")
    private List<ProductResponse> items;
}
