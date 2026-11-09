package com.insur.insurcoresystem.service.medical;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 4세대 실손의료보험 보상 계산 (순수 계산, DB·기관 호출 없음).
 *
 * <h3>진료 1건(영수증 1장)의 담보별 계산</h3>
 * <pre>
 *   공제금액 = max(최소 공제금액, ⌈보장대상의료비 × 자기부담률⌉)   단, 보장대상의료비를 넘지 않는다. 의료비가 0이면 공제도 0
 *   지급액   = 보장대상의료비 − 공제금액                          통원은 1회 한도(PER_VISIT_LIMIT)를 넘지 않는다
 * </pre>
 * 급여(ME001)는 급여 본인부담금, 비급여(ME002)는 비급여 금액이 보장대상의료비다. 자기부담률은 급여 20%, 비급여 30%(4세대),
 * 통원 최소 공제금액은 급여 의원·병원 1만원 / 종합병원·상급종합 2만원, 비급여 3만원이다. 공제를 올림하므로 지급액은 원 미만이 버려진다.
 *
 * <h3>비례보상 (중복보험)</h3>
 * 실손은 실제 쓴 의료비를 보상하는 손해보험이라, 실손을 여러 개 들어도 실제 손해보다 많이 받을 수 없다(이득 금지).
 * 그래서 계약마다 각자 계산한 보상책임액의 합이 손해액을 넘으면 손해액을 책임액 비율로 나눠 낸다.
 * <pre>
 *   분담액_i = ⌊손해액 × 책임액_i ÷ Σ책임액⌋      (Σ책임액 ≤ 손해액이면 각자 책임액 그대로)
 *   손해액   = 계약들 중 가장 큰 보상책임액 (공제가 가장 작은 계약이 본 손해액)
 * </pre>
 * 이 시스템은 다른 회사 계약도 우리와 같은 4세대 규칙이라고 가정해 책임액을 우리 계산과 같게 둔다. 실무에서는 계약 세대(1~4세대)마다
 * 자기부담률·공제가 달라 회사마다 책임액이 다르고, 신용정보원 조회 결과의 계약 세대·가입일로 각 회사 책임액을 따로 계산한다.
 * 연간 한도는 분담액에 적용한다(계약마다 자기 한도).
 */
public final class MedicalClaimCalculator {

    public static final String COVERED = "ME001";
    public static final String NON_COVERED = "ME002";

    private MedicalClaimCalculator() {
    }

    /** 보상 규칙 한 줄 (TB_PRD_MED_RULE) */
    public record Rule(String cvrgCd, String careType, String facilityGrade, BigDecimal copayRate, BigDecimal minDeduct,
                       BigDecimal perVisitLimit) {
    }

    /** 진료 1건 (손해사정에서 인정한 금액) */
    public record Item(int seq, String careType, String facilityType, BigDecimal coveredAmt, BigDecimal nonCoveredAmt) {
    }

    /** 진료 1건의 담보별 결과. 담보가 없으면(비급여 특약 미가입) 공제·지급이 null */
    public record ItemResult(int seq, BigDecimal coveredDeduct, BigDecimal coveredPay, BigDecimal nonCoveredDeduct,
                             BigDecimal nonCoveredPay) {
    }

    /** 담보 하나의 합계 */
    public record CoverageTotal(String cvrgCd, BigDecimal assessed, BigDecimal deduct, BigDecimal liability) {
    }

    /** 진료 내역 전체의 결과 */
    public record Result(List<ItemResult> items, Map<String, CoverageTotal> coverages) {
    }

    /**
     * 진료 내역을 계산한다.
     *
     * @param rules 상품의 보상 규칙
     * @param held  계약에 가입한 담보코드
     * @throws IllegalStateException 맞는 규칙이 없을 때 (상품 설정 오류)
     */
    public static Result calculate(List<Item> items, List<Rule> rules, java.util.Set<String> held) {
        List<ItemResult> results = new ArrayList<>();
        BigDecimal[] cov = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};   // 사정액, 공제, 책임액
        BigDecimal[] non = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (Item item : items) {
            BigDecimal[] c = held.contains(COVERED) ? one(item, item.coveredAmt(), rule(rules, COVERED, item)) : null;
            BigDecimal[] n = held.contains(NON_COVERED) ? one(item, item.nonCoveredAmt(), rule(rules, NON_COVERED, item)) : null;
            if (c != null) {
                add(cov, item.coveredAmt(), c);
            }
            if (n != null) {
                add(non, item.nonCoveredAmt(), n);
            }
            results.add(new ItemResult(item.seq(), c == null ? null : c[0], c == null ? null : c[1],
                    n == null ? null : n[0], n == null ? null : n[1]));
        }
        Map<String, CoverageTotal> totals = new java.util.TreeMap<>();
        if (held.contains(COVERED)) {
            totals.put(COVERED, new CoverageTotal(COVERED, cov[0], cov[1], cov[2]));
        }
        if (held.contains(NON_COVERED)) {
            totals.put(NON_COVERED, new CoverageTotal(NON_COVERED, non[0], non[1], non[2]));
        }
        return new Result(results, totals);
    }

    /**
     * 비례보상 분담액.
     *
     * @param own    우리 계약의 보상책임액
     * @param others 다른 계약들의 보상책임액
     * @return 우리 분담액 (원 미만 버림)
     */
    public static BigDecimal proportionalShare(BigDecimal own, List<BigDecimal> others) {
        BigDecimal loss = others.stream().reduce(own, BigDecimal::max);
        BigDecimal total = others.stream().reduce(own, BigDecimal::add);
        if (total.signum() == 0 || total.compareTo(loss) <= 0) {
            return own;
        }
        return loss.multiply(own).divide(total, 0, RoundingMode.FLOOR);
    }

    /** [공제, 지급] */
    private static BigDecimal[] one(Item item, BigDecimal amount, Rule rule) {
        if (amount.signum() == 0) {
            return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal byRate = amount.multiply(rule.copayRate()).setScale(0, RoundingMode.CEILING);
        BigDecimal deduct = byRate.max(rule.minDeduct()).min(amount);
        BigDecimal pay = amount.subtract(deduct);
        if ("OUT".equals(item.careType()) && rule.perVisitLimit() != null) {
            pay = pay.min(rule.perVisitLimit());
        }
        return new BigDecimal[] {deduct, pay};
    }

    private static void add(BigDecimal[] sum, BigDecimal amount, BigDecimal[] r) {
        sum[0] = sum[0].add(amount);
        sum[1] = sum[1].add(r[0]);
        sum[2] = sum[2].add(r[1]);
    }

    /**
     * 규칙을 찾는다. 입원은 ALL, 통원은 의료기관 등급(의원·병원 = CLINIC, 종합병원·상급종합 = GENERAL)을 먼저 찾고 없으면 ALL.
     */
    static Rule rule(List<Rule> rules, String cvrgCd, Item item) {
        String grade = switch (item.facilityType()) {
            case "CLINIC", "HOSPITAL" -> "CLINIC";
            default -> "GENERAL";
        };
        Optional<Rule> exact = "OUT".equals(item.careType())
                ? rules.stream().filter(r -> r.cvrgCd().equals(cvrgCd) && r.careType().equals("OUT") && r.facilityGrade().equals(grade)).findFirst()
                : Optional.empty();
        return exact.or(() -> rules.stream()
                        .filter(r -> r.cvrgCd().equals(cvrgCd) && r.careType().equals(item.careType()) && r.facilityGrade().equals("ALL"))
                        .findFirst())
                .orElseThrow(() -> new IllegalStateException("실손 보상 규칙이 없습니다: " + cvrgCd + " " + item.careType() + " " + grade));
    }
}
