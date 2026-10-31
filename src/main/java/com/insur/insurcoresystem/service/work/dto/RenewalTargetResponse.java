package com.insur.insurcoresystem.service.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 만기·갱신 대상 응답. 보험종기가 가까운 순으로 돌려준다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenewalTargetResponse {

    /** 기준일 (업무 시간대 오늘) */
    @Schema(description = "기준일 (업무 시간대 오늘)")
    private LocalDate baseDate;
    /** 적용한 조회 기간 (일) */
    @Schema(description = "적용한 조회 기간 (일)")
    private int withinDays;
    /** 페이지 번호 (0부터) */
    @Schema(description = "페이지 번호 (0부터)")
    private int page;
    /** 페이지 크기 */
    @Schema(description = "페이지 크기")
    private int size;
    /** 전체 건수 */
    @Schema(description = "전체 건수")
    private long totalElements;
    /** 전체 페이지 수 */
    @Schema(description = "전체 페이지 수")
    private int totalPages;
    /** 해당 페이지의 갱신 대상 계약 */
    @Schema(description = "해당 페이지의 갱신 대상 계약")
    private List<Item> items;

    /** 갱신 대상 계약 한 건 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        /** 증권번호 */
        @Schema(description = "증권번호")
        private String contractNo;
        /** 보험종목코드 (01:자동차, 02:화재) */
        @Schema(description = "보험종목코드 (01:자동차, 02:화재)")
        private String insrClsCd;
        /** 계약자 ID */
        @Schema(description = "계약자 ID")
        private String policyholderId;
        /** 피보험자 ID */
        @Schema(description = "피보험자 ID")
        private String insuredId;
        /** 보험시기 */
        @Schema(description = "보험시기")
        private LocalDate startDate;
        /** 보험종기 */
        @Schema(description = "보험종기")
        private LocalDate endDate;
        /** 보험종기까지 남은 일수 (0이면 오늘이 종기) */
        @Schema(description = "보험종기까지 남은 일수 (0이면 오늘이 종기)")
        private long daysToEnd;
        /** 총보험료 */
        @Schema(description = "총보험료")
        private BigDecimal totalPremium;
        /** 청약 접수 사번 */
        @Schema(description = "청약 접수 사번")
        private String regEmpId;
    }
}
