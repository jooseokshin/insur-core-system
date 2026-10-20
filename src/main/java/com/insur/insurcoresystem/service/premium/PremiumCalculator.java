package com.insur.insurcoresystem.service.premium;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 담보 보험료 계산식.
 * <pre>
 * 보험료 = 가입금액 × 연 요율 × 요율 계수 × 보험기간 일수 ÷ 365   (원 단위 미만 반올림)
 * </pre>
 * 요율 계수는 적용한 계수들의 곱이다(없으면 1).
 * 중간 단계에서는 반올림하지 않고 마지막에 한 번만 반올림한다. 윤년이어도 분모는 365 로 고정해, 366일 계약은 1년보다 약간 비싸다.
 */
public final class PremiumCalculator {

    /** 연 요율을 일 단위로 환산하는 기준 일수 */
    static final long DAYS_PER_YEAR = 365;

    private PremiumCalculator() {
    }

    /**
     * 담보 하나의 보험료를 계산한다.
     *
     * @param insuredAmount 가입금액
     * @param annualRate    연 요율
     * @param factor        요율 계수 (적용한 계수들의 곱, 없으면 1)
     * @param days          보험기간 일수 (보험시기 ~ 보험종기, 종기 당일 제외)
     * @return 원 단위로 반올림한 보험료
     */
    public static BigDecimal premium(BigDecimal insuredAmount, BigDecimal annualRate, BigDecimal factor, long days) {
        return insuredAmount.multiply(annualRate)
                .multiply(factor)
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(DAYS_PER_YEAR), 0, RoundingMode.HALF_UP);
    }
}
