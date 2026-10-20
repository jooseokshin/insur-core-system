package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요율 관리 감사 이력 조회 응답 바디 (한 페이지 분량)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditListResponse {

    /** 현재 페이지 번호 (0부터 시작) */
    @Schema(description = "현재 페이지 번호 (0부터 시작)")
    private int page;
    /** 요청한 페이지 크기 */
    @Schema(description = "요청한 페이지 크기")
    private int size;
    /** 조건에 맞는 전체 건수 */
    @Schema(description = "조건에 맞는 전체 건수")
    private long totalElements;
    /** 전체 페이지 수 (없으면 0) */
    @Schema(description = "전체 페이지 수 (없으면 0)")
    private int totalPages;
    /** 이 페이지의 감사 항목 (최근 처리 순) */
    @Schema(description = "이 페이지의 감사 항목 (최근 처리 순)")
    private List<AuditItem> items;

    /** 감사 항목 하나 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditItem {

        /** 대상 종류 (RATE, FACTOR, POLICY) */
        @Schema(description = "대상 종류 (RATE, FACTOR, POLICY)")
        private String targetType;
        /** 대상 식별 (예: 01/CA001) */
        @Schema(description = "대상 식별 (예: 01/CA001)")
        private String targetKey;
        /** 동작 (CREATE, REVISE) */
        @Schema(description = "동작 (CREATE, REVISE)")
        private String action;
        /** 개정 전 값 (처음 만든 경우 null) */
        @Schema(description = "개정 전 값 (처음 만든 경우 null)")
        private String beforeVal;
        /** 개정 후 값 */
        @Schema(description = "개정 후 값")
        private String afterVal;
        /** 새 값의 적용 시작일 */
        @Schema(description = "새 값의 적용 시작일")
        private LocalDate effFrom;
        /** 처리 사번 */
        @Schema(description = "처리 사번")
        private String empId;
        /** 거래 UUID */
        @Schema(description = "거래 UUID")
        private String globalTxId;
        /** 처리 일시 */
        @Schema(description = "처리 일시")
        private LocalDateTime chgDt;
    }
}
