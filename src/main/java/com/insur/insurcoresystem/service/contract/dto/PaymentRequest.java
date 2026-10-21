package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 계약 납입(수납) 요청 바디
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

    /** 납입할 계약의 증권번호 */
    @Schema(description = "납입할 계약의 증권번호")
    private String contractNo;
    /** 납입 금액 (원, 양의 정수. 남은 보험료를 넘을 수 없다) */
    @Schema(description = "납입 금액 (원, 양의 정수. 남은 보험료를 넘을 수 없다)")
    private BigDecimal payAmt;
    /** 납부 방법 (CASH:현금, TRANSFER:계좌이체, CARD:카드) */
    @Schema(description = "납부 방법 (CASH:현금, TRANSFER:계좌이체, CARD:카드)")
    private String payMethod;
}
