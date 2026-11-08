package com.insur.insurcoresystem.service.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 손해율 집계 요청 바디
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LossRatioRequest {

    /** 집계 기간 시작일 (포함). 생략하면 올해 1월 1일 */
    @Schema(description = "집계 기간 시작일 (포함). 생략하면 올해 1월 1일")
    private LocalDate fromDate;
    /** 집계 기간 종료일 (포함). 생략하면 오늘 */
    @Schema(description = "집계 기간 종료일 (포함). 생략하면 오늘")
    private LocalDate toDate;
    /** 묶는 단위 (CLASS: 보험종목, PRODUCT: 상품, COVERAGE: 담보). 생략하면 PRODUCT */
    @Schema(description = "묶는 단위 (CLASS: 보험종목, PRODUCT: 상품, COVERAGE: 담보). 생략하면 PRODUCT")
    private String groupBy;
}
