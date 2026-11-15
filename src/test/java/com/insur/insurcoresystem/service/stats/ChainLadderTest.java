package com.insur.insurcoresystem.service.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 체인래더·BF·케이프코드를 손 계산과 비교한다.
 * <pre>
 * 누적 지급           경과1     경과2     경과3   경과보험료  개별추산액
 *   P1              100,000  150,000  165,000   300,000        0
 *   P2              120,000  174,000            300,000   10,000
 *   P3              130,000                     300,000   40,000
 * f1 = (150,000 + 174,000) ÷ (100,000 + 120,000) = 1.472727…   f2 = 165,000 ÷ 150,000 = 1.1
 * CDF: 경과1 = 1.62, 경과2 = 1.1, 경과3 = 1 (꼬리 1)
 * CL 최종: P1 165,000, P2 191,400, P3 210,600 → IBNR 0, 7,400, 40,600
 * </pre>
 */
class ChainLadderTest {

    private static List<ChainLadder.Period> triangle(long p1Case) {
        return List.of(
                period("P1", p1Case, 100_000, 150_000, 165_000),
                period("P2", 10_000, 120_000, 174_000),
                period("P3", 40_000, 130_000));
    }

    @Test
    void 체인래더는_금액_가중_진전계수로_최종손해액을_구한다() {
        ChainLadder.Result r = ChainLadder.run(triangle(0), BigDecimal.ONE, new BigDecimal("0.7"));

        assertThat(r.factors()).extracting(f -> f.factor().toPlainString())
                .containsExactly("1.472727", "1.100000", "1.000000");
        assertThat(r.factors()).extracting(f -> f.cdf().toPlainString()).containsExactly("1.620000", "1.100000", "1.000000");
        assertThat(r.factors()).extracting(ChainLadder.Factor::dataPoints).containsExactly(2, 1, 0);
        assertThat(r.periods()).extracting(p -> p.clUltimate().longValue()).containsExactly(165_000L, 191_400L, 210_600L);
        assertThat(r.periods()).extracting(p -> p.clIbnr().longValue()).containsExactly(0L, 7_400L, 40_600L);
        // 진전율이 모두 50% 이상 (P3 = 1 / 1.62 = 0.6173) 이라 체인래더를 고른다
        assertThat(r.periods()).extracting(ChainLadder.PeriodResult::method).containsExactly("CL", "CL", "CL");
        assertThat(r.periods().get(2).developed()).isEqualByComparingTo("0.6173");
        // BF 도 함께 계산한다: P2 = 174,000 + 0.7 × 300,000 × (1 − 1/1.1) = 193,091, P3 = 130,000 + 210,000 × (1 − 1/1.62) = 210,370
        assertThat(r.periods().get(1).bfUltimate()).isEqualByComparingTo("193091");
        assertThat(r.periods().get(2).bfUltimate()).isEqualByComparingTo("210370");
    }

    @Test
    void 진전이_덜_된_사고_기간은_BF_를_고른다() {
        // 꼬리 2.0: CDF 경과1 = 3.24 (진전율 30.9%), 경과2 = 2.2 (45.5%), 경과3 = 2 (50%)
        ChainLadder.Result r = ChainLadder.run(triangle(0), new BigDecimal("2"), new BigDecimal("0.7"));
        assertThat(r.periods()).extracting(ChainLadder.PeriodResult::method).containsExactly("CL", "BF", "BF");
        // P1 체인래더 330,000 → IBNR 165,000
        assertThat(r.periods().get(0).selectedIbnr()).isEqualByComparingTo("165000");
        // P2 BF = 174,000 + 210,000 × (1 − 1/2.2) = 288,545 → IBNR = 288,545 − 174,000 − 10,000 = 104,545
        assertThat(r.periods().get(1).selectedIbnr()).isEqualByComparingTo("104545");
        // P3 BF = 130,000 + 210,000 × (1 − 1/3.24) = 275,185 → IBNR = 275,185 − 130,000 − 40,000 = 105,185
        assertThat(r.periods().get(2).selectedIbnr()).isEqualByComparingTo("105185");
    }

    @Test
    void 기대손해율을_주지_않으면_케이프코드로_구하고_음수_IBNR_은_0으로_적립한다() {
        // ELR = Σ지급 ÷ Σ(경과보험료 × 진전율) = 469,000 ÷ (300,000 + 272,727.27 + 185,185.19) = 0.6188
        ChainLadder.Result r = ChainLadder.run(triangle(50_000), BigDecimal.ONE, null);
        assertThat(r.elrEstimated()).isTrue();
        assertThat(r.elr()).isEqualByComparingTo("0.6188");
        // P1 은 최종 165,000 인데 지급 165,000 + 개별추산 50,000 → 계산 IBNR −50,000, 적립 0
        assertThat(r.periods().get(0).clIbnr()).isEqualByComparingTo("-50000");
        assertThat(r.periods().get(0).selectedIbnr()).isEqualByComparingTo("0");
    }

    @Test
    void 지급_자료가_없는_경과는_계수_1로_두고_경고한다() {
        ChainLadder.Result r = ChainLadder.run(List.of(period("P1", 0, 0, 0), period("P2", 0, 0)), BigDecimal.ONE, null);
        assertThat(r.factors().get(0).factor()).isEqualByComparingTo("1");
        assertThat(r.warnings()).anyMatch(w -> w.contains("자료가 없어"));
        assertThat(r.elr()).isEqualByComparingTo("0");
    }

    private static ChainLadder.Period period(String label, long caseReserve, long... cumulative) {
        BigDecimal[] c = new BigDecimal[cumulative.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = BigDecimal.valueOf(cumulative[i]);
        }
        return new ChainLadder.Period(label, c, BigDecimal.valueOf(300_000), BigDecimal.valueOf(caseReserve));
    }
}
