package com.insur.insurcoresystem.domain.claim;

import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사고의 담보별 금액. 접수 때 청구 손해액이 정해지고, 손해사정 때 사정 손해액·자기부담금·지급액이 채워진다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClaimCoverage {

    /** 담보코드 */
    private String cvrgCd;

    /** 청구 손해액 (접수 때 추정) */
    private BigDecimal claimAmt;

    /** 사정 손해액 (손해사정 결과) */
    private BigDecimal assessedAmt;

    /** 적용한 자기부담금 */
    private BigDecimal deductibleAmt;

    /** 지급액 */
    private BigDecimal payAmt;

    /** 손해사정 결과 (사정 손해액, 적용한 자기부담금, 지급액) */
    public record Assessment(BigDecimal assessedAmt, BigDecimal deductibleAmt, BigDecimal payAmt) {
    }

    public ClaimCoverage(String cvrgCd, BigDecimal claimAmt) {
        this.cvrgCd = cvrgCd;
        this.claimAmt = claimAmt;
    }

    /** 손해사정 결과를 기록한다 */
    void assess(Assessment result) {
        this.assessedAmt = result.assessedAmt();
        this.deductibleAmt = result.deductibleAmt();
        this.payAmt = result.payAmt();
    }
}
