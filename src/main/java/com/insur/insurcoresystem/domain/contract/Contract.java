package com.insur.insurcoresystem.domain.contract;

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
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Contract {

    /** 보험종목: 자동차 */
    public static final String INSR_CLS_CAR = "01";
    /** 보험종목: 화재 */
    public static final String INSR_CLS_FIRE = "02";
    /** 보험종목: 운전자 */
    public static final String INSR_CLS_DRIVER = "03";
    /** 보험종목: 해외여행 */
    public static final String INSR_CLS_TRAVEL = "04";
    /** 보험종목: 실손의료 */
    public static final String INSR_CLS_MEDICAL = "05";
    /** 계약상태: 청약 */
    public static final String CTR_STAT_APPLIED = "10";

    /** 증권번호 */
    private String contractNo;

    /** 청약 접수 거래 UUID (멱등성 키, 최대 64자). 마이그레이션 이전 계약은 null */
    private String globalTxId;

    /** 청약번호 */
    private String applNo;

    /** 보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행, 05:실손의료) */
    private String insrClsCd;

    /** 계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소). 변경은 {@link #changeStatus(ContractStatus)} 로만 한다 */
    private String ctrStatCd;

    /** 보험시기 */
    private LocalDate startDate;

    /** 보험종기 */
    private LocalDate endDate;

    /** 계약자 ID */
    private String policyholderId;

    /** 피보험자 ID */
    private String insuredId;

    /** 총보험료 */
    private BigDecimal totalPremium;

    /** 시스템 등록일시 */
    private LocalDateTime sysRegDt;

    /** 청약을 접수한 사번 (최대 64자). 데이터 단위 권한(본인 접수 계약만 조회)의 기준. 이력 이전에 접수된 계약은 null */
    private String regEmpId;

    /** 청약 요청 내용의 지문(SHA-256 16진수 64자). 같은 거래 UUID 재전송이 처음과 같은 내용인지 비교한다. 이 컬럼 이전 계약은 null */
    private String reqHash;

    /** 보험료 산출에 쓴 운전자 만 나이 (자동차, 입력하지 않았으면 null) */
    private Integer driverAge;

    /** 보험료 산출에 쓴 할인할증 등급 (자동차, 입력하지 않았으면 null) */
    private String discountGrade;

    /** 보험료 산출에 쓴 건물 구조 등급 (화재, 입력하지 않았으면 null) */
    private String structureGrade;

    /** 해지일 (해지(30)로 바꾼 날, 업무 시간대 기준). 해지하지 않았으면 null */
    private LocalDate termDate;

    /** 해지 환급보험료 (원). 해지하지 않았으면 null */
    private BigDecimal refundPremium;

    /** 상품코드. 상품 도입 전 계약이나 상품 없이 접수한 청약은 null */
    private String prdCd;

    /** 보험료 산출에 쓴 여행 지역 (해외여행, 입력하지 않았으면 null) */
    private String travelRegion;

    /** 보험료 산출에 쓴 피보험자 만 나이 (해외여행, 입력하지 않았으면 null) */
    private Integer insuredAge;

    /** 판매채널코드 (10:대면, 20:다이렉트, 30:TM). 갱신 계약은 원 계약의 채널을 잇는다 */
    private String salesChnlCd;

    /** 이 계약이 갱신으로 만들어진 경우 원 계약의 증권번호. 갱신 계약이 아니면 null */
    private String renewedFrom;

    /** 판매채널을 정하지 않고 저장하면 대면(10)으로 본다 (DB 기본값과 같은 규칙. JPA 는 null 을 그대로 넣으므로 여기서 채운다) */
    public void defaultSalesChannel() {
        if (salesChnlCd == null) {
            salesChnlCd = "10";
        }
    }

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
