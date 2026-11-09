package com.insur.insurcoresystem.domain.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 마일리지 특약 (자동차 계약 1건에 1행). 시작 주행거리를 등록하면 생기고, 만기 주행거리로 정산하면 환급액이 정해진다.
 */
@Entity
@Table(name = "TB_CTR_MILEAGE")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ContractMileage {

    /** 마일리지 상태: 시작 주행거리 등록 */
    public static final String STAT_REGISTERED = "10";
    /** 마일리지 상태: 정산 완료 */
    public static final String STAT_SETTLED = "20";

    /** 증권번호 */
    @Id
    @Column(name = "CONTRACT_NO", length = 30)
    private String contractNo;

    /** 마일리지 상태코드 (10:시작 등록, 20:정산 완료) */
    @Column(name = "MIL_STAT_CD", length = 2, nullable = false)
    private String milStatCd;

    /** 시작 주행거리 (계기판 누적 km) */
    @Column(name = "START_ODO_KM", nullable = false)
    private Integer startOdoKm;

    /** 시작 등록 일시 */
    @Column(name = "START_REG_DT", nullable = false)
    private LocalDateTime startRegDt;

    /** 시작 등록 처리자 */
    @Column(name = "START_EMP_ID", length = 64)
    private String startEmpId;

    /** 시작 등록 거래 UUID */
    @Column(name = "START_TX_ID", length = 64, nullable = false, unique = true)
    private String startTxId;

    /** 만기 주행거리 (정산 전 null) */
    @Column(name = "END_ODO_KM")
    private Integer endOdoKm;

    /** 정산 일시 */
    @Column(name = "END_REG_DT")
    private LocalDateTime endRegDt;

    /** 정산 처리자 */
    @Column(name = "END_EMP_ID", length = 64)
    private String endEmpId;

    /** 정산 거래 UUID */
    @Column(name = "END_TX_ID", length = 64, unique = true)
    private String endTxId;

    /** 실제 주행거리 (km) */
    @Column(name = "DRIVEN_KM")
    private Integer drivenKm;

    /** 1년 환산 주행거리 (km) */
    @Column(name = "ANNUAL_KM")
    private Integer annualKm;

    /** 적용 환급률 */
    @Column(name = "REFUND_RATE", precision = 5, scale = 4)
    private BigDecimal refundRate;

    /** 환급 기준 보험료 (원) */
    @Column(name = "REFUND_BASE_PREM", precision = 15, scale = 0)
    private BigDecimal refundBasePrem;

    /** 환급액 (원) */
    @Column(name = "REFUND_AMT", precision = 15, scale = 0)
    private BigDecimal refundAmt;

    /** 만기 주행거리로 정산한다. 계산은 서비스가 하고 결과만 남긴다. */
    public void settle(int endOdoKm, int drivenKm, int annualKm, BigDecimal refundRate, BigDecimal basePrem, BigDecimal refundAmt,
                       String empId, String txId, LocalDateTime now) {
        this.endOdoKm = endOdoKm;
        this.drivenKm = drivenKm;
        this.annualKm = annualKm;
        this.refundRate = refundRate;
        this.refundBasePrem = basePrem;
        this.refundAmt = refundAmt;
        this.endEmpId = empId;
        this.endTxId = txId;
        this.endRegDt = now;
        this.milStatCd = STAT_SETTLED;
    }
}
