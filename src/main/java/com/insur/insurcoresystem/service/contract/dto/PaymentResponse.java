package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 납입(수납) 응답 바디
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    /** 증권번호 */
    @Schema(description = "증권번호")
    private String contractNo;
    /** 이번 납입의 일련번호 */
    @Schema(description = "이번 납입의 일련번호")
    private Long paymentId;
    /** 이번에 낸 금액 */
    @Schema(description = "이번에 낸 금액")
    private BigDecimal payAmt;
    /** 납부 방법 */
    @Schema(description = "납부 방법 (CASH, TRANSFER, CARD)")
    private String payMethod;
    /** 납입 일시 */
    @Schema(description = "납입 일시")
    private LocalDateTime paidDt;
    /** 총보험료 */
    @Schema(description = "총보험료")
    private BigDecimal totalPremium;
    /** 이번 납입까지 낸 합계 */
    @Schema(description = "이번 납입까지 낸 합계")
    private BigDecimal paidPremium;
    /** 남은 보험료 (총보험료 − 납입 합계) */
    @Schema(description = "남은 보험료 (총보험료 − 납입 합계)")
    private BigDecimal remainingPremium;
    /** 납입이 끝났는지 (남은 보험료가 0). 끝나면 심사 담당이 승인(유효)할 수 있다 */
    @Schema(description = "납입이 끝났는지 (남은 보험료가 0). 끝나면 승인(유효)할 수 있다")
    private boolean fullyPaid;
}
