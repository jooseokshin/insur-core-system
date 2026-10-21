package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 납입 내역 조회 응답 바디 (한 페이지 분량과 납입 현황)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentHistoryResponse {

    /** 증권번호 */
    @Schema(description = "증권번호")
    private String contractNo;
    /** 총보험료 */
    @Schema(description = "총보험료")
    private BigDecimal totalPremium;
    /** 지금까지 낸 합계 (페이지와 무관하게 전체) */
    @Schema(description = "지금까지 낸 합계 (페이지와 무관하게 전체)")
    private BigDecimal paidPremium;
    /** 남은 보험료 */
    @Schema(description = "남은 보험료")
    private BigDecimal remainingPremium;
    /** 현재 페이지 번호 (0부터 시작) */
    @Schema(description = "현재 페이지 번호 (0부터 시작)")
    private int page;
    /** 요청한 페이지 크기 */
    @Schema(description = "요청한 페이지 크기")
    private int size;
    /** 전체 납입 건수 */
    @Schema(description = "전체 납입 건수")
    private long totalElements;
    /** 전체 페이지 수 (납입이 없으면 0) */
    @Schema(description = "전체 페이지 수 (납입이 없으면 0)")
    private int totalPages;
    /** 이 페이지의 납입 항목 (오래된 순) */
    @Schema(description = "이 페이지의 납입 항목 (오래된 순)")
    private List<Item> items;

    /** 납입 항목 하나 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        /** 납입 일련번호 */
        @Schema(description = "납입 일련번호")
        private Long paymentId;
        /** 납입 금액 */
        @Schema(description = "납입 금액")
        private BigDecimal payAmt;
        /** 납부 방법 */
        @Schema(description = "납부 방법 (CASH, TRANSFER, CARD)")
        private String payMethod;
        /** 수납 처리 사번 */
        @Schema(description = "수납 처리 사번")
        private String empId;
        /** 납입 일시 */
        @Schema(description = "납입 일시")
        private LocalDateTime paidDt;
    }
}
