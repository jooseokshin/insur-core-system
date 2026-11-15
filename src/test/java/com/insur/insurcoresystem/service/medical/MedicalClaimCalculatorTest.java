package com.insur.insurcoresystem.service.medical;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 4세대 실손 계산을 손으로 계산한 값과 비교한다 (V26 MED-STD-2026 규칙과 같은 값).
 * <pre>
 * 1) 의원 통원   급여 30,000  → 공제 max(1만, 6,000)=10,000  → 20,000 | 비급여 50,000    → 공제 max(3만, 15,000)=30,000 → 20,000
 * 2) 종합 통원   급여 150,000 → 공제 max(2만, 30,000)=30,000 → 120,000 | 비급여 400,000  → 공제 120,000 → 280,000 → 1회 한도 200,000
 * 3) 상급 입원   급여 1,000,000 → 공제 200,000 → 800,000     | 비급여 2,000,001 → 공제 ⌈600,000.3⌉=600,001 → 1,400,000
 * 급여 합계 940,000, 비급여 합계 1,620,000
 * </pre>
 */
class MedicalClaimCalculatorTest {

    private static final List<MedicalClaimCalculator.Rule> RULES = List.of(
            rule("ME001", "IN", "ALL", "0.2", 0, null),
            rule("ME001", "OUT", "CLINIC", "0.2", 10000, 200000L),
            rule("ME001", "OUT", "GENERAL", "0.2", 20000, 200000L),
            rule("ME002", "IN", "ALL", "0.3", 0, null),
            rule("ME002", "OUT", "ALL", "0.3", 30000, 200000L));

    private static final List<MedicalClaimCalculator.Item> ITEMS = List.of(
            item(1, "OUT", "CLINIC", 30000, 50000),
            item(2, "OUT", "GENERAL", 150000, 400000),
            item(3, "IN", "TERTIARY", 1000000, 2000001));

    @Test
    void 진료마다_정률과_최소_공제_중_큰_금액을_빼고_통원은_1회_한도를_넘지_않는다() {
        var result = MedicalClaimCalculator.calculate(ITEMS, RULES, Set.of("ME001", "ME002"));

        assertThat(result.items()).extracting(r -> List.of(r.coveredDeduct(), r.coveredPay(), r.nonCoveredDeduct(), r.nonCoveredPay()))
                .containsExactly(
                        amounts(10000, 20000, 30000, 20000),
                        amounts(30000, 120000, 120000, 200000),
                        amounts(200000, 800000, 600001, 1400000));
        assertThat(result.coverages().get("ME001").liability()).isEqualByComparingTo("940000");
        assertThat(result.coverages().get("ME001").assessed()).isEqualByComparingTo("1180000");
        assertThat(result.coverages().get("ME002").liability()).isEqualByComparingTo("1620000");
    }

    @Test
    void 비급여_특약이_없으면_비급여는_계산하지_않고_금액이_0인_진료는_공제도_0이다() {
        var result = MedicalClaimCalculator.calculate(
                List.of(item(1, "OUT", "HOSPITAL", 0, 80000), item(2, "OUT", "HOSPITAL", 8000, 0)), RULES, Set.of("ME001"));
        assertThat(result.coverages()).containsOnlyKeys("ME001");
        assertThat(result.items().get(0).coveredDeduct()).isEqualByComparingTo("0");
        assertThat(result.items().get(0).nonCoveredPay()).isNull();
        // 병원 통원은 의원과 같은 1만원 공제. 8,000원이면 공제가 의료비를 넘지 않아 지급 0
        assertThat(result.items().get(1).coveredDeduct()).isEqualByComparingTo("8000");
        assertThat(result.items().get(1).coveredPay()).isEqualByComparingTo("0");
    }

    @Test
    void 비례보상은_손해액을_책임액_비율로_나누고_합이_손해액_이하면_각자_낸다() {
        // 같은 규칙 두 계약: 940,000 × 940,000 ÷ 1,880,000 = 470,000
        assertThat(MedicalClaimCalculator.proportionalShare(bd(940000), List.of(bd(940000)))).isEqualByComparingTo("470000");
        // 책임액 600,000 / 400,000: 손해액 600,000 × 600,000 ÷ 1,000,000 = 360,000, 다른 회사 240,000
        assertThat(MedicalClaimCalculator.proportionalShare(bd(600000), List.of(bd(400000)))).isEqualByComparingTo("360000");
        assertThat(MedicalClaimCalculator.proportionalShare(bd(400000), List.of(bd(600000)))).isEqualByComparingTo("240000");
        // 세 계약 같은 책임액 100,001 → 33,333 (원 미만 버림)
        assertThat(MedicalClaimCalculator.proportionalShare(bd(100001), List.of(bd(100001), bd(100001)))).isEqualByComparingTo("33333");
        // 다른 계약이 없거나 책임액이 0이면 그대로
        assertThat(MedicalClaimCalculator.proportionalShare(bd(500000), List.of())).isEqualByComparingTo("500000");
        assertThat(MedicalClaimCalculator.proportionalShare(bd(500000), List.of(bd(0)))).isEqualByComparingTo("500000");
    }

    private static MedicalClaimCalculator.Rule rule(String cvrg, String care, String grade, String rate, long min, Long limit) {
        return new MedicalClaimCalculator.Rule(cvrg, care, grade, new BigDecimal(rate), BigDecimal.valueOf(min),
                limit == null ? null : BigDecimal.valueOf(limit));
    }

    private static MedicalClaimCalculator.Item item(int seq, String care, String facility, long covered, long nonCovered) {
        return new MedicalClaimCalculator.Item(seq, care, facility, BigDecimal.valueOf(covered), BigDecimal.valueOf(nonCovered));
    }

    private static List<BigDecimal> amounts(long... values) {
        return java.util.Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
    }

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
