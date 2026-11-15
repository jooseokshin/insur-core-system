package com.insur.insurcoresystem.service.cms.dto;

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
 * 계약의 자동이체 현황
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoDebitResponse {
    @Schema(description = "증권번호")
    private String contractNo;
    @Schema(description = "가장 최근 등록 (없으면 null)")
    private Mandate mandate;
    @Schema(description = "출금 내역 (최근순, 최대 20건)")
    private List<Debit> debits;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Mandate {
        @Schema(description = "등록 일련번호")
        private long mandateId;
        @Schema(description = "등록 상태 (10: 결제원 등록 대기, 20: 등록 의뢰함, 30: 등록 완료, 80: 등록 불능, 90: 해지)")
        private String statCd;
        @Schema(description = "예금주")
        private String payerNm;
        @Schema(description = "출금 은행 코드")
        private String bankCd;
        @Schema(description = "출금 계좌번호 (마스킹)")
        private String accountMask;
        @Schema(description = "출금 동의 방식 (PAPER, RECORDING, ARS, E_SIGN)")
        private String consentMethod;
        @Schema(description = "출금 동의 일시")
        private LocalDateTime consentDt;
        @Schema(description = "동의 증빙 관리번호")
        private String consentRef;
        @Schema(description = "결제원 등록 결과코드")
        private String resultCd;
        @Schema(description = "등록 결과 설명")
        private String resultMsg;
        @Schema(description = "갱신으로 이어받은 원 계약 (직접 등록이면 null)")
        private String inheritedFrom;
        @Schema(description = "등록 처리자")
        private String regEmpId;
        @Schema(description = "등록 일시")
        private LocalDateTime regDt;
        @Schema(description = "등록 완료·불능 확정 일시")
        private LocalDateTime doneDt;
        @Schema(description = "해지 일시")
        private LocalDateTime cancelDt;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Debit {
        @Schema(description = "출금 일련번호")
        private long debitId;
        @Schema(description = "출금일")
        private LocalDate debitDt;
        @Schema(description = "의뢰 금액 (원)")
        private BigDecimal amount;
        @Schema(description = "출금 차수 (최대 3)")
        private int attemptNo;
        @Schema(description = "출금 상태 (10: 의뢰 대상, 20: 결과 대기, 30: 성공, 80: 실패)")
        private String statCd;
        @Schema(description = "결제원 결과코드")
        private String resultCd;
        @Schema(description = "결과 설명")
        private String resultMsg;
        @Schema(description = "기록한 수납 일련번호")
        private Long paymentId;
    }
}
