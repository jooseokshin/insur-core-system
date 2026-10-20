package com.insur.insurcoresystem.service.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PremiumCalculatorTest {

    private static BigDecimal premium(String insuredAmount, String annualRate, long days) {
        return PremiumCalculator.premium(new BigDecimal(insuredAmount), new BigDecimal(annualRate), BigDecimal.ONE, days);
    }

    private static BigDecimal premium(String insuredAmount, String annualRate, String factor, long days) {
        return PremiumCalculator.premium(new BigDecimal(insuredAmount), new BigDecimal(annualRate),
                new BigDecimal(factor), days);
    }

    @Test
    void 일년_보험료는_가입금액에_연_요율을_곱한_값이다() {
        assertThat(premium("100000000", "0.005", 365)).isEqualByComparingTo("500000");
        assertThat(premium("20000000", "0.0175", 365)).isEqualByComparingTo("350000");
    }

    @Test
    void 단기_계약은_일수에_비례하고_원_단위로_반올림한다() {
        // 100,000,000 × 0.005 × 182 ÷ 365 = 249,315.068...
        assertThat(premium("100000000", "0.005", 182)).isEqualByComparingTo("249315");
        // 1일: 100,000,000 × 0.005 ÷ 365 = 1,369.863...
        assertThat(premium("100000000", "0.005", 1)).isEqualByComparingTo("1370");
    }

    @Test
    void 윤년의_366일_계약은_일년보다_조금_비싸다() {
        // 500,000 × 366 ÷ 365 = 501,369.863...
        assertThat(premium("100000000", "0.005", 366)).isEqualByComparingTo("501370");
    }

    @Test
    void 정확히_절반인_값은_올림한다() {
        // 정확히 .5 인 경계값: 1,000 × 0.0005 = 0.5 → 1 (HALF_UP)
        assertThat(premium("1000", "0.0005", 365)).isEqualByComparingTo("1");
        assertThat(premium("3000", "0.0005", 365)).isEqualByComparingTo("2");   // 1.5 → 2
    }

    @Test
    void 요율_계수는_기본_요율에_곱해진다() {
        // 100,000,000 × 0.005 × 1.5 = 750,000 (할증), × 0.8 = 400,000 (할인)
        assertThat(premium("100000000", "0.005", "1.5000", 365)).isEqualByComparingTo("750000");
        assertThat(premium("100000000", "0.005", "0.8000", 365)).isEqualByComparingTo("400000");
        // 계수 1 은 계수가 없는 것과 같다
        assertThat(premium("100000000", "0.005", "1", 365)).isEqualByComparingTo(premium("100000000", "0.005", 365));
    }

    @Test
    void 계수를_곱한_뒤_한_번만_반올림한다() {
        // 계수 1.2 × 0.95 = 1.14: 1,000,001 × 0.005 × 1.14 = 5,700.00570 → 5,700. 계수마다 먼저 반올림하면 값이 달라질 수 있다
        assertThat(premium("1000001", "0.005", "1.1400", 365)).isEqualByComparingTo("5700");
    }

    @Test
    void 결과는_소수점_없는_원_단위다() {
        assertThat(premium("100000000", "0.005", 182).scale()).isZero();
    }
}
