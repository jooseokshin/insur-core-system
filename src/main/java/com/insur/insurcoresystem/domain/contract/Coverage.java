package com.insur.insurcoresystem.domain.contract;

import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 담보
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Coverage {

    private Long id;

    /** 증권번호 */
    private String contractNo;

    /** 담보코드 */
    private String cvrgCd;

    /** 가입금액 */
    private BigDecimal isrdAmt;

    /** 담보보험료 */
    private BigDecimal prem;
}
