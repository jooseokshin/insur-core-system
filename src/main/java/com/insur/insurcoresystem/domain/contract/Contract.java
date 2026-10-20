package com.insur.insurcoresystem.domain.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 원장
 */
@Entity
@Table(name = "TB_CTR_LGD")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Contract {

    /** 보험종목: 자동차 */
    public static final String INSR_CLS_CAR = "01";
    /** 보험종목: 화재 */
    public static final String INSR_CLS_FIRE = "02";
    /** 계약상태: 청약 */
    public static final String CTR_STAT_APPLIED = "10";

    /** 증권번호 */
    @Id
    @Column(name = "CONTRACT_NO", length = 30)
    private String contractNo;

    /** 청약 접수 거래 UUID (멱등성 키, 최대 64자). 마이그레이션 이전 계약은 null */
    @Column(name = "GLOBAL_TX_ID", length = 64, unique = true)
    private String globalTxId;

    /** 청약번호 */
    @Column(name = "APPL_NO", length = 30, nullable = false, unique = true)
    private String applNo;

    /** 보험종목코드 (01:자동차, 02:화재) */
    @Column(name = "INSR_CLS_CD", length = 2, nullable = false)
    private String insrClsCd;

    /** 계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소). 변경은 {@link #changeStatus(ContractStatus)} 로만 한다 */
    @Column(name = "CTR_STAT_CD", length = 2, nullable = false)
    private String ctrStatCd;

    /** 보험시기 */
    @Column(name = "START_DATE", nullable = false)
    private LocalDate startDate;

    /** 보험종기 */
    @Column(name = "END_DATE", nullable = false)
    private LocalDate endDate;

    /** 계약자 ID */
    @Column(name = "POLICYHOLDER_ID", length = 20, nullable = false)
    private String policyholderId;

    /** 피보험자 ID */
    @Column(name = "INSURED_ID", length = 20, nullable = false)
    private String insuredId;

    /** 총보험료 */
    @Column(name = "TOTAL_PREMIUM", precision = 15, scale = 0, nullable = false)
    private BigDecimal totalPremium;

    /** 시스템 등록일시 */
    @Column(name = "SYS_REG_DT", nullable = false)
    private LocalDateTime sysRegDt;

    /** 청약을 접수한 사번 (최대 64자). 데이터 단위 권한(본인 접수 계약만 조회)의 기준. 이력 이전에 접수된 계약은 null */
    @Column(name = "REG_EMP_ID", length = 64)
    private String regEmpId;

    /** 청약 요청 내용의 지문(SHA-256 16진수 64자). 같은 거래 UUID 재전송이 처음과 같은 내용인지 비교한다. 이 컬럼 이전 계약은 null */
    @Column(name = "REQ_HASH", length = 64)
    private String reqHash;

    /** 보험료 산출에 쓴 운전자 만 나이 (자동차, 입력하지 않았으면 null) */
    @Column(name = "DRIVER_AGE")
    private Integer driverAge;

    /** 보험료 산출에 쓴 할인할증 등급 (자동차, 입력하지 않았으면 null) */
    @Column(name = "DISCOUNT_GRADE", length = 10)
    private String discountGrade;

    /** 보험료 산출에 쓴 건물 구조 등급 (화재, 입력하지 않았으면 null) */
    @Column(name = "STRUCTURE_GRADE", length = 10)
    private String structureGrade;

    /** 해지일 (해지(30)로 바꾼 날, 업무 시간대 기준). 해지하지 않았으면 null */
    @Column(name = "TERM_DT")
    private LocalDate termDate;

    /** 해지 환급보험료 (원). 해지하지 않았으면 null */
    @Column(name = "REFUND_PREM", precision = 15, scale = 0)
    private BigDecimal refundPremium;

    /** 이 계약이 갱신으로 만들어진 경우 원 계약의 증권번호. 갱신 계약이 아니면 null */
    @Column(name = "RENEWED_FROM", length = 30)
    private String renewedFrom;

    /**
     * 계약상태를 변경한다. 전이 가능 여부는 호출자(서비스)가 {@link ContractStatus#canChangeTo} 로 먼저 확인한다.
     *
     * @param next 변경할 상태
     */
    public void changeStatus(ContractStatus next) {
        this.ctrStatCd = next.getCode();
    }

    /** 계약자를 바꾼다. (배서) */
    public void changePolicyholder(String policyholderId) {
        this.policyholderId = policyholderId;
    }

    /** 피보험자를 바꾼다. (배서) */
    public void changeInsured(String insuredId) {
        this.insuredId = insuredId;
    }

    /**
     * 해지 정보를 기록한다. 상태를 해지(30)로 바꿀 때 {@link #changeStatus} 와 함께 호출한다.
     *
     * @param termDate      해지일
     * @param refundPremium 계산한 환급보험료
     */
    public void recordTermination(LocalDate termDate, BigDecimal refundPremium) {
        this.termDate = termDate;
        this.refundPremium = refundPremium;
    }
}
