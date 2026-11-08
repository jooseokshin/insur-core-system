package com.insur.insurcoresystem.service.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 요율 영향 시뮬레이션 요청 바디. 아무것도 저장하지 않는다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateImpactRequest {

    /** 보험종목 코드 (01 자동차, 02 화재). 필수 */
    @Schema(description = "보험종목 코드 (01 자동차, 02 화재). 필수", example = "01")
    private String insrClsCd;
    /** 경험 기간 시작일 (포함). 생략하면 종료일의 364일 전 (12개월) */
    @Schema(description = "경험 기간 시작일 (포함). 생략하면 종료일의 364일 전 (12개월)")
    private LocalDate fromDate;
    /** 경험 기간 종료일 (포함). 생략하면 오늘 */
    @Schema(description = "경험 기간 종료일 (포함). 생략하면 오늘")
    private LocalDate toDate;
    /** 목표 손해율 (0 초과 1.5 이하, 예 0.75). 주면 담보별 필요 요율 변경률(손해율법)을 계산한다 */
    @Schema(description = "목표 손해율 (0 초과 1.5 이하, 예 0.75). 주면 담보별 필요 요율 변경률(손해율법)을 계산한다", example = "0.75")
    private BigDecimal targetLossRatio;
    /** 갱신 영향을 볼 기간 (오늘부터 며칠 안에 만기가 오는 계약, 1~365, 기본 365) */
    @Schema(description = "갱신 영향을 볼 기간 (오늘부터 며칠 안에 만기가 오는 계약, 1~365, 기본 365)", example = "365")
    private Integer renewalWindowDays;
    /** 담보별 기본 요율 변경률. 빠진 담보는 0% (변경 없음) */
    @Schema(description = "담보별 기본 요율 변경률. 빠진 담보는 0% (변경 없음)")
    private List<Change> changes;

    /** 담보 하나의 요율 변경 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Change {
        /** 담보 코드 (현재 요율표에 있는 이 보험종목의 담보) */
        @Schema(description = "담보 코드 (현재 요율표에 있는 이 보험종목의 담보)", example = "CA001")
        private String cvrgCd;
        /** 기본 요율 변경률(%, -50 ~ +100, 소수 둘째 자리까지). 예 5 는 연 요율 × 1.05 */
        @Schema(description = "기본 요율 변경률(%, -50 ~ +100, 소수 둘째 자리까지). 예 5 는 연 요율 × 1.05", example = "5")
        private BigDecimal rateChangePct;
    }
}
