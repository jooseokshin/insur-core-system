package com.insur.insurcoresystem.domain.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Entity
@Table(name = "TB_CTR_PAYMENT")
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

    /** 납입 일련번호 (발생 순서) */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PAYMENT_ID")
    private Long paymentId;

    /** 증권번호 */
    @Column(name = "CONTRACT_NO", length = 30, nullable = false)
    private String contractNo;

    /** 납입 금액 (원, 양의 정수) */
    @Column(name = "PAY_AMT", precision = 15, scale = 0, nullable = false)
    private BigDecimal payAmt;

    /** 납부 방법 (CASH, TRANSFER, CARD) */
    @Column(name = "PAY_METHOD", length = 10, nullable = false)
    private String payMethod;

    /** 수납 처리 사번 */
    @Column(name = "EMP_ID", length = 64)
    private String empId;

    /** 납입을 접수한 거래 UUID (멱등성 키, 전체에서 유일) */
    @Column(name = "GLOBAL_TX_ID", length = 64, nullable = false, unique = true)
    private String globalTxId;

    /** 납입 일시 (업무 시간대 기준) */
    @Column(name = "PAID_DT", nullable = false)
    private LocalDateTime paidDt;
}
