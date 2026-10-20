package com.insur.insurcoresystem.service.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RefundCalculatorTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 3);
    private static final LocalDate END = LocalDate.of(2027, 10, 3);   // 365일

    private static BigDecimal refund(String totalPremium, LocalDate termDate) {
        return RefundCalculator.refund(new BigDecimal(totalPremium), START, END, termDate);
    }

    @Test
    void 남은_일수에_비례해_돌려주고_원_단위로_반올림한다() {
        // 해지일 2026-10-10: 남은 358일 / 365일 → 850,000 × 358 ÷ 365 = 833,698.63...
        assertThat(refund("850000", LocalDate.of(2026, 10, 10))).isEqualByComparingTo("833699");
        // 정확히 절반을 지난 시점이 아니어도 일수 비례: 보험기간의 정중앙(182일 경과, 183일 남음)
        assertThat(refund("730000", START.plusDays(182))).isEqualByComparingTo("366000");
    }

    @Test
    void 보험시기_전이나_당일에_해지하면_전액_돌려준다() {
        assertThat(refund("850000", START.minusDays(30))).isEqualByComparingTo("850000");
        assertThat(refund("850000", START)).isEqualByComparingTo("850000");
    }

    @Test
    void 보험종기_당일이거나_그_이후에_해지하면_돌려줄_금액이_없다() {
        assertThat(refund("850000", END)).isEqualByComparingTo("0");
        assertThat(refund("850000", END.plusDays(10))).isEqualByComparingTo("0");
    }

    @Test
    void 환급보험료는_총보험료를_넘지_않고_소수점이_없다() {
        for (int elapsed = -5; elapsed <= 370; elapsed += 7) {
            BigDecimal refund = refund("123457", START.plusDays(elapsed));
            assertThat(refund).isBetween(BigDecimal.ZERO, new BigDecimal("123457"));
            assertThat(refund.scale()).isZero();
        }
    }

    @Test
    void 해지가_늦을수록_환급보험료가_줄어든다() {
        BigDecimal previous = refund("850000", START);
        for (int elapsed = 1; elapsed <= 365; elapsed++) {
            BigDecimal current = refund("850000", START.plusDays(elapsed));
            assertThat(current).isLessThanOrEqualTo(previous);
            previous = current;
        }
    }
}
