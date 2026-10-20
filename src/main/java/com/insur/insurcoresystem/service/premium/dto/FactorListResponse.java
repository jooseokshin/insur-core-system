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
 * 요율 계수 목록 조회 응답 바디. 보험종목, 계수 종류, 키/구간, 적용 시작일 순으로 정렬한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorListResponse {

    /** 계수 행 */
    @Schema(description = "계수 행")
    private List<FactorItem> items;

    /** 계수 행 하나 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FactorItem {

        /** 계수 일련번호 */
        @Schema(description = "계수 일련번호")
        private Long factorId;
        /** 보험종목코드 */
        @Schema(description = "보험종목코드")
        private String insrClsCd;
        /** 계수 종류 */
        @Schema(description = "계수 종류")
        private String factorType;
        /** 키형 계수의 코드 (범위형이면 null) */
        @Schema(description = "키형 계수의 코드 (범위형이면 null)")
        private String factorKey;
        /** 범위형 계수의 시작값 (키형이면 null) */
        @Schema(description = "범위형 계수의 시작값 (키형이면 null)")
        private Integer rangeFrom;
        /** 범위형 계수의 종료값 (키형이면 null) */
        @Schema(description = "범위형 계수의 종료값 (키형이면 null)")
        private Integer rangeTo;
        /** 계수 이름 */
        @Schema(description = "계수 이름")
        private String factorNm;
        /** 계수 값 */
        @Schema(description = "계수 값")
        private BigDecimal factorValue;
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
