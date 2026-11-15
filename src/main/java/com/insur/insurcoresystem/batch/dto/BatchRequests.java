package com.insur.insurcoresystem.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 배치 API 요청 바디
 */
public final class BatchRequests {

    private BatchRequests() {
    }

    /** 결산 실행·조회 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReserveClose {
        /** 결산 기준일 (분기 말일). 조회에서는 생략하면 최근 결산 */
        @Schema(description = "결산 기준일 (분기 말일). 조회에서는 생략하면 최근 결산")
        private LocalDate closeDate;
    }

    /** 대사 실행·조회 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recon {
        /** 대사일 (은행 처리일, 오늘 이전) */
        @Schema(description = "대사일 (은행 처리일, 오늘 이전)")
        private LocalDate reconDate;
        /** 조회에서 차이 건만 볼지 */
        @Schema(description = "조회에서 차이 건만 볼지")
        private Boolean diffOnly;
    }

    /** 자동이체 배치 실행 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CmsDebit {
        /** 출금일 (오늘 이전) */
        @Schema(description = "출금일 (오늘 이전)")
        private LocalDate debitDate;
    }

    /** 실행 이력 조회 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Executions {
        /** 작업 이름 (reserveCloseJob, payoutReconJob, cmsDebitJob, 생략하면 전부) */
        @Schema(description = "작업 이름 (reserveCloseJob, payoutReconJob, cmsDebitJob, 생략하면 전부)")
        private String jobName;
        /** 최대 건수 (1~100, 기본 20) */
        @Schema(description = "최대 건수 (1~100, 기본 20)")
        private Integer limit;
    }
}
