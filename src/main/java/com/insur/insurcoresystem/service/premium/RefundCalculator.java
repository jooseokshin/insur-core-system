package com.insur.insurcoresystem.service.premium;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 해지 환급보험료 계산식. 남은 보험기간에 비례해 총보험료를 돌려준다.
 * <pre>
 * 환급보험료 = 총보험료 × 남은 일수 ÷ 보험기간 일수   (원 단위 미만 반올림)
 * 남은 일수 = 보험종기 − max(해지일, 보험시기),  0 이상
 * </pre>
 * 보험시기 전에 해지하면 총보험료 전액, 보험종기 당일 이후에 해지하면 0원이다. 단기요율 같은 해지 공제는 적용하지 않는다.
 */
public final class RefundCalculator {

    private RefundCalculator() {
    }

    /**
     * 해지 환급보험료를 계산한다.
     *
     * @param totalPremium 총보험료
     * @param startDate    보험시기
     * @param endDate      보험종기 (보험시기 이후)
     * @param termDate     해지일
     * @return 원 단위로 반올림한 환급보험료 (0 이상, 총보험료 이하)
     */
    public static BigDecimal refund(BigDecimal totalPremium, LocalDate startDate, LocalDate endDate, LocalDate termDate) {
        long totalDays = ChronoUnit.DAYS.between(startDate, endDate);
        LocalDate effective = termDate.isBefore(startDate) ? startDate : termDate;
        long remainingDays = Math.max(0, ChronoUnit.DAYS.between(effective, endDate));
        return totalPremium.multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.HALF_UP);
    }
}
