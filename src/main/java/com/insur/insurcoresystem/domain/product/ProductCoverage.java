package com.insur.insurcoresystem.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품의 담보 하나. 주계약(MAIN)은 반드시 가입하고, 특약(RIDER)은 필수 표시가 없으면 고를 수 있다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductCoverage {

    public static final String MAIN = "MAIN";
    public static final String RIDER = "RIDER";

    /** 담보코드 */
    @Column(name = "CVRG_CD", length = 10, nullable = false)
    private String cvrgCd;

    /** 담보 구분 (MAIN: 주계약, RIDER: 특약) */
    @Column(name = "CVRG_TYPE", length = 10, nullable = false)
    private String cvrgType;

    /** 필수 가입 여부 (주계약은 항상 true) */
    @Column(name = "REQUIRED_YN", nullable = false)
    private boolean required;

    /** 가입금액 최소 (포함) */
    @Column(name = "MIN_ISRD_AMT", precision = 15, scale = 0, nullable = false)
    private BigDecimal minIsrdAmt;

    /** 가입금액 최대 (포함) */
    @Column(name = "MAX_ISRD_AMT", precision = 15, scale = 0, nullable = false)
    private BigDecimal maxIsrdAmt;

    /** 자기부담금. 보상 때 손해액에서 먼저 뺀다 */
    @Column(name = "DEDUCTIBLE_AMT", precision = 15, scale = 0, nullable = false)
    private BigDecimal deductibleAmt;

    /** 표시 순서 */
    @Column(name = "SORT_ORD", nullable = false)
    private int sortOrd;
}
