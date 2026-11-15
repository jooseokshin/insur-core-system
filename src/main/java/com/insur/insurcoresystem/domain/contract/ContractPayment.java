package com.insur.insurcoresystem.domain.contract;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 납입(수납) 내역. 한 번 낸 금액이 한 건이며, 추가 전용이라 저장한 내역은 수정하거나 삭제하지 않는다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ContractPayment {

    /** 납부 방법: 현금 */
    public static final String METHOD_CASH = "CASH";
    /** 납부 방법: 계좌이체 */
    public static final String METHOD_TRANSFER = "TRANSFER";
    /** 납부 방법: 카드 */
    public static final String METHOD_CARD = "CARD";
    /** 자동이체 출금 (CMS 배치만 기록, 직원 수납 API 는 받지 않는다) */
    public static final String METHOD_CMS = "CMS";

    /** 납입 일련번호 (발생 순서) */
    private Long paymentId;

    /** 증권번호 */
    private String contractNo;

    /** 납입 금액 (원, 양의 정수) */
    private BigDecimal payAmt;

    /** 납부 방법 (CASH:현금, TRANSFER:계좌이체, CARD:카드, CMS:자동이체 출금) */
    private String payMethod;

    /** 수납 처리 사번 */
    private String empId;

    /** 납입을 접수한 거래 UUID (멱등성 키, 전체에서 유일) */
    private String globalTxId;

    /** 납입 일시 (업무 시간대 기준) */
    private LocalDateTime paidDt;
}
