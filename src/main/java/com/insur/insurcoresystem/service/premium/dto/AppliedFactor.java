package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보험료 산출에 적용한 요율 계수 하나
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppliedFactor {

    /** 계수 종류 (DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE) */
    @Schema(description = "계수 종류 (DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE)")
    private String factorType;
    /** 계수 이름 (예: 만 30~49세) */
    @Schema(description = "계수 이름 (예: 만 30~49세)")
    private String factorNm;
    /** 입력값 (예: 35, 2) */
    @Schema(description = "입력값 (예: 35, 2)")
    private String input;
    /** 적용한 계수 값 */
    @Schema(description = "적용한 계수 값")
    private BigDecimal factorValue;
}
