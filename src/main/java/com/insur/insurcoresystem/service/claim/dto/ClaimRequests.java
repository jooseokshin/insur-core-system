package com.insur.insurcoresystem.service.claim.dto;

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
 * 보상 API 요청 바디
 */
public final class ClaimRequests {

    private ClaimRequests() {
    }

    /** 사고 접수 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Report {
        /** 증권번호 */
        @Schema(description = "증권번호")
        private String contractNo;
        /** 사고일 (보험기간 안, 오늘 이전) */
        @Schema(description = "사고일 (보험기간 안, 오늘 이전)")
        private LocalDate acdtDt;
        /** 사고 내용 (500자 이하) */
        @Schema(description = "사고 내용 (500자 이하)")
        private String acdtDesc;
        /** 청구인 ID (생략하면 피보험자) */
        @Schema(description = "청구인 ID (생략하면 피보험자)")
        private String claimantId;
        /** 청구 담보와 추정 손해액 (1건 이상, 계약에 가입한 담보만) */
        @Schema(description = "청구 담보와 추정 손해액 (1건 이상, 계약에 가입한 담보만)")
        private List<ClaimAmount> coverages;
    }

    /** 담보 하나의 금액 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClaimAmount {
        /** 담보코드 */
        @Schema(description = "담보코드")
        private String cvrgCd;
        /** 금액 (접수: 청구 손해액, 손해사정: 사정 손해액) */
        @Schema(description = "금액 (접수: 청구 손해액, 손해사정: 사정 손해액)")
        private BigDecimal amount;
    }

    /** 손해사정 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Assess {
        /** 사고번호 */
        @Schema(description = "사고번호")
        private String clmNo;
        /** 담보별 사정 손해액 (청구한 담보 전부, 0 이상) */
        @Schema(description = "담보별 사정 손해액 (청구한 담보 전부, 0 이상)")
        private List<ClaimAmount> coverages;
        /** 사정 의견 (선택) */
        @Schema(description = "사정 의견 (선택)")
        private String memo;
    }

    /** 종결 처리 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Decide {
        /** 사고번호 */
        @Schema(description = "사고번호")
        private String clmNo;
        /** 처리 (PAY: 지급, DENY: 면책, WITHDRAW: 접수 취소) */
        @Schema(description = "처리 (PAY: 지급, DENY: 면책, WITHDRAW: 접수 취소)")
        private String decision;
        /** 메모 (면책은 사유 필수) */
        @Schema(description = "메모 (면책은 사유 필수)")
        private String memo;
    }

    /** 사고 상세 조회 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Inquiry {
        /** 사고번호 */
        @Schema(description = "사고번호")
        private String clmNo;
    }

    /** 사고 목록 조회. 조건은 모두 선택이다 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListCond {
        /** 보상 상태코드 (10, 20, 40, 80, 90) */
        @Schema(description = "보상 상태코드 (10, 20, 40, 80, 90)")
        private String clmStatCd;
        /** 증권번호 (정확히 일치) */
        @Schema(description = "증권번호 (정확히 일치)")
        private String contractNo;
        /** 접수일 시작 */
        @Schema(description = "접수일 시작")
        private LocalDate rptDateFrom;
        /** 접수일 종료 (당일 포함) */
        @Schema(description = "접수일 종료 (당일 포함)")
        private LocalDate rptDateTo;
        /** 고액 승인 대기만 (손해사정 완료이면서 지급액이 고액 기준을 넘는 건) */
        @Schema(description = "고액 승인 대기만 (손해사정 완료이면서 지급액이 고액 기준을 넘는 건)")
        private Boolean highValueOnly;
        /** 페이지 번호 (0부터) */
        @Schema(description = "페이지 번호 (0부터)")
        private Integer page;
        /** 페이지 크기 (1~100, 기본 20) */
        @Schema(description = "페이지 크기 (1~100, 기본 20)")
        private Integer size;
    }
}
