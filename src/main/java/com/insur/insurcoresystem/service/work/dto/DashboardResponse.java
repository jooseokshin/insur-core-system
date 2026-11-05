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
 * 업무 현황(대시보드) 응답. 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약 기준으로 집계한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardResponse {

    /** 기준일 (업무 시간대 오늘) */
    @Schema(description = "기준일 (업무 시간대 오늘)")
    private LocalDate baseDate;
    /** 집계 범위가 본인 접수 계약으로 제한됐는지 여부 (AGENT 만 가진 사용자) */
    @Schema(description = "집계 범위가 본인 접수 계약으로 제한됐는지 여부 (AGENT 만 가진 사용자)")
    private boolean ownContractsOnly;
    /** 계약상태별 건수와 총보험료 합계 */
    @Schema(description = "계약상태별 건수와 총보험료 합계")
    private List<StatusCount> statusCounts;
    /** 오늘 처리 실적 */
    @Schema(description = "오늘 처리 실적")
    private Today today;
    /** 할 일 유형별 건수. 사용자 역할로 처리할 수 없는 유형은 빠진다 */
    @Schema(description = "할 일 유형별 건수. 사용자 역할로 처리할 수 없는 유형은 빠진다")
    private List<TodoCount> todoCounts;
    /** 갱신 가능 기간(보험종기 60일 전~종기)에 들어왔지만 아직 갱신하지 않은 유효 계약 건수 */
    @Schema(description = "갱신 가능 기간(보험종기 60일 전~종기)에 들어왔지만 아직 갱신하지 않은 유효 계약 건수")
    private long renewalDueCount;
    /** 보상 현황. 보상을 다루는 역할(설계사·보상 담당·ADMIN)에게만 담고, 그 외에는 null */
    @Schema(description = "보상 현황. 보상을 다루는 역할(설계사·보상 담당·ADMIN)에게만 담고, 그 외에는 null")
    private Claims claims;

    /** 보상 현황 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Claims {
        /** 접수(손해사정 전) 건수 */
        @Schema(description = "접수(손해사정 전) 건수")
        private long reportedCount;
        /** 손해사정 완료(지급 대기) 건수 */
        @Schema(description = "손해사정 완료(지급 대기) 건수")
        private long assessedCount;
        /** 지급 대기 중 고액(관리자 승인 필요) 건수 */
        @Schema(description = "지급 대기 중 고액(관리자 승인 필요) 건수")
        private long highValuePendingCount;
        /** 오늘 접수한 사고 건수 */
        @Schema(description = "오늘 접수한 사고 건수")
        private long todayReportedCount;
        /** 오늘 지급한 건수 */
        @Schema(description = "오늘 지급한 건수")
        private long todayPaidCount;
        /** 오늘 지급한 보험금 합계 */
        @Schema(description = "오늘 지급한 보험금 합계")
        private BigDecimal todayPaidAmount;
    }

    /** 계약상태별 집계 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusCount {
        /** 계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소) */
        @Schema(description = "계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소)")
        private String ctrStatCd;
        /** 건수 */
        @Schema(description = "건수")
        private long count;
        /** 총보험료 합계 */
        @Schema(description = "총보험료 합계")
        private BigDecimal totalPremium;
    }

    /** 오늘(업무 시간대) 처리 실적 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Today {
        /** 오늘 접수한 청약 건수 */
        @Schema(description = "오늘 접수한 청약 건수")
        private long appliedCount;
        /** 오늘 접수한 청약의 총보험료 합계 */
        @Schema(description = "오늘 접수한 청약의 총보험료 합계")
        private BigDecimal appliedPremium;
        /** 오늘 승인(유효)한 건수 */
        @Schema(description = "오늘 승인(유효)한 건수")
        private long approvedCount;
        /** 오늘 청약취소한 건수 */
        @Schema(description = "오늘 청약취소한 건수")
        private long canceledCount;
        /** 오늘 해지한 건수 */
        @Schema(description = "오늘 해지한 건수")
        private long terminatedCount;
        /** 오늘 수납한 건수 */
        @Schema(description = "오늘 수납한 건수")
        private long paidCount;
        /** 오늘 수납한 금액 합계 */
        @Schema(description = "오늘 수납한 금액 합계")
        private BigDecimal paidAmount;
    }

    /** 할 일 유형별 건수 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TodoCount {
        /** 할 일 유형 (PENDING_APPROVAL, UNPAID, STALE_APPLICATION) */
        @Schema(description = "할 일 유형 (PENDING_APPROVAL, UNPAID, STALE_APPLICATION)")
        private String type;
        /** 유형 이름 */
        @Schema(description = "유형 이름")
        private String label;
        /** 건수 */
        @Schema(description = "건수")
        private long count;
    }
}
