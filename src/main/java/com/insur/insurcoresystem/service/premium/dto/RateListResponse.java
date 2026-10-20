package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요율 목록 조회 응답 바디. 보험종목, 담보코드, 적용 시작일 순으로 정렬한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateListResponse {

    /** 요율 행 */
    @Schema(description = "요율 행")
    private List<RateItem> items;

    /** 요율 행 하나 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateItem {

        /** 요율 일련번호 */
        @Schema(description = "요율 일련번호")
        private Long rateId;
        /** 보험종목코드 */
        @Schema(description = "보험종목코드")
        private String insrClsCd;
        /** 담보코드 */
        @Schema(description = "담보코드")
        private String cvrgCd;
        /** 담보명 */
        @Schema(description = "담보명")
        private String cvrgNm;
        /** 연 요율 */
        @Schema(description = "연 요율")
        private BigDecimal annualRate;
        /** 적용 시작일 (포함) */
        @Schema(description = "적용 시작일 (포함)")
        private LocalDate effFrom;
        /** 적용 종료일 (포함). 없으면 현재까지 유효 */
        @Schema(description = "적용 종료일 (포함). 없으면 현재까지 유효")
        private LocalDate effTo;
        /** 오늘(서울 기준) 기준 상태: ACTIVE(적용 중), FUTURE(적용 예정), CLOSED(종료) */
        @Schema(description = "오늘(서울 기준) 기준 상태: ACTIVE(적용 중), FUTURE(적용 예정), CLOSED(종료)")
        private String status;
    }
}
