package com.insur.insurcoresystem.service.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 할 일함 목록 응답. 청약 상태의 계약을 오래된 접수순으로 돌려준다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoResponse {

    /** 할 일 유형 */
    @Schema(description = "할 일 유형")
    private String type;
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
    /** 해당 페이지의 청약 목록 */
    @Schema(description = "해당 페이지의 청약 목록")
    private List<Item> items;

    /** 할 일 한 건 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        /** 증권번호 */
        @Schema(description = "증권번호")
        private String contractNo;
        /** 청약번호 */
        @Schema(description = "청약번호")
        private String applNo;
        /** 보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행) */
        @Schema(description = "보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행)")
        private String insrClsCd;
        /** 계약자 ID */
        @Schema(description = "계약자 ID")
        private String policyholderId;
        /** 보험시기 */
        @Schema(description = "보험시기")
        private LocalDate startDate;
        /** 총보험료 */
        @Schema(description = "총보험료")
        private BigDecimal totalPremium;
        /** 지금까지 낸 보험료 합계 */
        @Schema(description = "지금까지 낸 보험료 합계")
        private BigDecimal paidPremium;
        /** 남은 보험료 */
        @Schema(description = "남은 보험료")
        private BigDecimal remainingPremium;
        /** 청약 접수 일시 */
        @Schema(description = "청약 접수 일시")
        private LocalDateTime sysRegDt;
        /** 청약 접수 사번 */
        @Schema(description = "청약 접수 사번")
        private String regEmpId;
        /** 접수 후 지난 일수 (업무 시간대 날짜 기준) */
        @Schema(description = "접수 후 지난 일수 (업무 시간대 날짜 기준)")
        private long elapsedDays;
    }
}
