package com.insur.insurcoresystem.domain.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사고(보상 건). 상태와 금액은 아래 메서드로만 바꾼다. 전이 가능 여부는 서비스가 {@link ClaimStatus#canChangeTo} 로 먼저 확인한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Claim {

    /** 사고번호 (예: CLM-CAR-2026-000001) */
    private String clmNo;

    /** 증권번호 */
    private String contractNo;

    /** 보상 상태코드 (10:접수, 20:손해사정 완료, 40:지급 완료, 80:면책, 90:접수 취소) */
    private String clmStatCd;

    /** 사고일 */
    private LocalDate acdtDt;

    /** 사고 내용 */
    private String acdtDesc;

    /** 청구인 ID */
    private String claimantId;

    /** 청구 손해액 합계 */
    private BigDecimal totalClaimAmt;

    /** 지급액 합계 (손해사정 후. 면책·취소면 0) */
    private BigDecimal totalPayAmt;

    /** 접수 일시 */
    private LocalDateTime rptDt;

    /** 접수 사번 */
    private String rptEmpId;

    /** 손해사정 사번 */
    private String adjEmpId;

    /** 손해사정 일시 */
    private LocalDateTime adjDt;

    /** 종결(지급·면책·취소) 처리 사번 */
    private String decideEmpId;

    /** 종결 일시 */
    private LocalDateTime decideDt;

    /** 종결 메모 (면책 사유 등) */
    private String decideMemo;

    /** 접수 거래 UUID (멱등성 키) */
    private String globalTxId;

    /** 접수 요청 내용의 지문 (같은 거래 UUID 재전송이 같은 내용인지 비교) */
    private String reqHash;

    /** 담보별 금액 */
    private List<ClaimCoverage> items = new ArrayList<>();

    /** 사고를 접수한다 */
    public static Claim report(String clmNo, String contractNo, LocalDate acdtDt, String acdtDesc, String claimantId,
                               List<ClaimCoverage> items, String rptEmpId, String globalTxId, String reqHash, LocalDateTime now) {
        Claim claim = new Claim();
        claim.clmNo = clmNo;
        claim.contractNo = contractNo;
        claim.clmStatCd = ClaimStatus.REPORTED.getCode();
        claim.acdtDt = acdtDt;
        claim.acdtDesc = acdtDesc;
        claim.claimantId = claimantId;
        claim.items = new ArrayList<>(items);
        claim.totalClaimAmt = items.stream().map(ClaimCoverage::getClaimAmt).reduce(BigDecimal.ZERO, BigDecimal::add);
        claim.rptDt = now;
        claim.rptEmpId = rptEmpId;
        claim.globalTxId = globalTxId;
        claim.reqHash = reqHash;
        return claim;
    }

    public ClaimStatus status() {
        return ClaimStatus.findByCode(clmStatCd).orElseThrow();
    }

    /**
     * 손해사정 결과를 기록하고 손해사정 완료(20)로 바꾼다.
     *
     * @param results 담보코드별 손해사정 결과. 사고의 모든 담보가 있어야 한다
     */
    public void assess(Map<String, ClaimCoverage.Assessment> results, String empId, LocalDateTime now) {
        for (ClaimCoverage item : items) {
            item.assess(results.get(item.getCvrgCd()));
        }
        this.totalPayAmt = items.stream().map(ClaimCoverage::getPayAmt).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.clmStatCd = ClaimStatus.ASSESSED.getCode();
        this.adjEmpId = empId;
        this.adjDt = now;
    }

    /** 보험금을 지급하고 종결한다 */
    public void pay(String empId, String memo, LocalDateTime now) {
        close(ClaimStatus.PAID, empId, memo, now);
    }

    /** 면책(부지급)으로 종결한다 */
    public void deny(String empId, String memo, LocalDateTime now) {
        this.totalPayAmt = BigDecimal.ZERO;
        close(ClaimStatus.DENIED, empId, memo, now);
    }

    /** 접수를 취소한다 */
    public void withdraw(String empId, String memo, LocalDateTime now) {
        this.totalPayAmt = BigDecimal.ZERO;
        close(ClaimStatus.WITHDRAWN, empId, memo, now);
    }

    private void close(ClaimStatus next, String empId, String memo, LocalDateTime now) {
        this.clmStatCd = next.getCode();
        this.decideEmpId = empId;
        this.decideDt = now;
        this.decideMemo = memo;
    }
}
