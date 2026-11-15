package com.insur.insurcoresystem.service.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * IBNR 산출 조건
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IbnrRequest {
    /** 보험종목코드 (필수, 종목마다 진전 양상이 달라 섞지 않는다) */
    @Schema(description = "보험종목코드 (필수, 종목마다 진전 양상이 달라 섞지 않는다)")
    private String insrClsCd;
    /** 평가 기준일 (생략하면 직전 사고 기간 말일, 오늘 이후 불가) */
    @Schema(description = "평가 기준일 (생략하면 직전 사고 기간 말일, 오늘 이후 불가)")
    private LocalDate evaluationDate;
    /** 사고 기간 단위 (QUARTER 기본, YEAR) */
    @Schema(description = "사고 기간 단위 (QUARTER 기본, YEAR)")
    private String periodType;
    /** 사고 기간 수 (분기 2~40 기본 12, 연 2~10 기본 5) */
    @Schema(description = "사고 기간 수 (분기 2~40 기본 12, 연 2~10 기본 5)")
    private Integer periods;
    /** BF 기대손해율 (0 초과 3 이하, 생략하면 케이프코드로 실적에서 구한다) */
    @Schema(description = "BF 기대손해율 (0 초과 3 이하, 생략하면 케이프코드로 실적에서 구한다)")
    private BigDecimal expectedLossRatio;
    /** 꼬리 진전계수 (1 이상 3 이하, 기본 1) */
    @Schema(description = "꼬리 진전계수 (1 이상 3 이하, 기본 1)")
    private BigDecimal tailFactor;
}
