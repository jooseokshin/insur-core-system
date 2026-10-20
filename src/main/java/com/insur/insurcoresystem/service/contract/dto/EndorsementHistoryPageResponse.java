package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 변경(배서) 이력 조회 응답 바디 (한 페이지 분량)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndorsementHistoryPageResponse {

    /** 증권번호 */
    @Schema(description = "증권번호")
    private String contractNo;
    /** 현재 페이지 번호 (0부터 시작) */
    @Schema(description = "현재 페이지 번호 (0부터 시작)")
    private int page;
    /** 요청한 페이지 크기 */
    @Schema(description = "요청한 페이지 크기")
    private int size;
    /** 전체 배서 항목 건수 */
    @Schema(description = "전체 배서 항목 건수")
    private long totalElements;
    /** 전체 페이지 수 (이력이 없으면 0) */
    @Schema(description = "전체 페이지 수 (이력이 없으면 0)")
    private int totalPages;
    /** 이 페이지의 배서 항목 (오래된 순) */
    @Schema(description = "이 페이지의 배서 항목 (오래된 순)")
    private List<Item> items;

    /** 배서 항목 하나 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        /** 변경한 항목 (POLICYHOLDER_ID, INSURED_ID) */
        @Schema(description = "변경한 항목 (POLICYHOLDER_ID, INSURED_ID)")
        private String fieldNm;
        /** 변경 전 값 */
        @Schema(description = "변경 전 값")
        private String beforeVal;
        /** 변경 후 값 */
        @Schema(description = "변경 후 값")
        private String afterVal;
        /** 처리 사번 */
        @Schema(description = "처리 사번")
        private String empId;
        /** 변경 일시 */
        @Schema(description = "변경 일시")
        private LocalDateTime chgDt;
    }
}
