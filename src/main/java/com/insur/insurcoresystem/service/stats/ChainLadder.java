package com.insur.insurcoresystem.service.stats;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 지급 기준 손해 진전 삼각형으로 IBNR 을 계산한다 (순수 계산, DB 없음).
 *
 * <h3>입력</h3>
 * 사고 기간(오래된 것부터)마다 경과 1..a_i 의 누적 지급액, 경과보험료, 기준일의 개별추산액. a_i 는 기준일에 그 사고 기간이 도달한 경과다.
 *
 * <h3>체인래더 (CL)</h3>
 * <pre>
 *   경과 k → k+1 진전계수 f_k = Σ C(i,k+1) ÷ Σ C(i,k)      (두 경과 값이 다 있는 사고 기간만, 금액 가중 평균)
 *   누적 진전계수 CDF(a) = f_a × f_(a+1) × … × 꼬리
 *   최종손해액 U_CL = 지급액 × CDF(a_i)
 * </pre>
 * 자료가 없는 경과의 계수는 1.0 으로 두고 경고한다.
 *
 * <h3>본휴터-퍼거슨 (BF)</h3>
 * 최근 사고 기간은 지급액이 작아 CDF 를 곱하면 흔들림이 크다. BF 는 아직 지급되지 않을 부분만 기대손해율로 채운다.
 * <pre>
 *   진전율 d = 1 ÷ CDF
 *   U_BF = 지급액 + 기대손해율 × 경과보험료 × (1 − d)
 * </pre>
 * 기대손해율을 주지 않으면 케이프코드(Stanovsky-Bühlmann) 방식으로 실적에서 구한다: ELR = Σ지급액 ÷ Σ(경과보험료 × d).
 *
 * <h3>IBNR 과 선택</h3>
 * IBNR = 최종손해액 − 지급액 − 개별추산액. 진전율 {@value #MATURE_THRESHOLD} 이상이면 CL, 미만이면 BF 를 고르고,
 * 적립 값은 음수를 0 으로 둔다(개별추산액이 넉넉하다는 뜻이지만 준비금을 줄이지는 않는 보수적 처리). 계산 값은 그대로 돌려준다.
 */
public final class ChainLadder {

    /** 이 진전율 이상이면 체인래더, 미만이면 BF 를 고른다 (판단 기준, 회사마다 다르다) */
    public static final double MATURE_THRESHOLD = 0.5;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final int FACTOR_SCALE = 6;

    private ChainLadder() {
    }

    /** 사고 기간 하나의 입력. cumulative[k-1] = 경과 k 의 누적 지급액 */
    public record Period(String label, BigDecimal[] cumulative, BigDecimal earnedPremium, BigDecimal caseReserve) {
        int age() {
            return cumulative.length;
        }

        BigDecimal paid() {
            return cumulative.length == 0 ? BigDecimal.ZERO : cumulative[cumulative.length - 1];
        }
    }

    /** 경과별 진전계수. factor 는 k → k+1 (마지막 경과는 꼬리), 자료가 없으면 dataPoints 0 */
    public record Factor(int age, BigDecimal factor, BigDecimal cdf, int dataPoints) {
    }

    /** 사고 기간 하나의 결과 */
    public record PeriodResult(String label, int age, BigDecimal earnedPremium, BigDecimal paid, BigDecimal caseReserve,
                               BigDecimal cdf, BigDecimal developed, BigDecimal clUltimate, BigDecimal clIbnr,
                               BigDecimal bfUltimate, BigDecimal bfIbnr, String method, BigDecimal selectedUltimate,
                               BigDecimal selectedIbnr) {
    }

    public record Result(List<Factor> factors, BigDecimal elr, boolean elrEstimated, List<PeriodResult> periods,
                         List<String> warnings) {
    }

    /**
     * @param periods 오래된 사고 기간부터. 마지막 기간일수록 경과가 짧다
     * @param tail    꼬리 진전계수 (1 이상)
     * @param elr     기대손해율. null 이면 케이프코드로 구한다
     */
    public static Result run(List<Period> periods, BigDecimal tail, BigDecimal elr) {
        int maxAge = periods.stream().mapToInt(Period::age).max().orElse(0);
        List<String> warnings = new ArrayList<>();

        BigDecimal[] f = new BigDecimal[Math.max(maxAge, 1)];
        int[] points = new int[f.length];
        List<Integer> noData = new ArrayList<>();
        for (int k = 1; k < maxAge; k++) {
            BigDecimal num = BigDecimal.ZERO;
            BigDecimal den = BigDecimal.ZERO;
            for (Period p : periods) {
                if (p.age() >= k + 1) {
                    num = num.add(p.cumulative()[k]);
                    den = den.add(p.cumulative()[k - 1]);
                    points[k - 1]++;
                }
            }
            if (den.signum() == 0) {
                // 앞 경과의 지급이 없으면 비율을 정할 수 없다 (자료 부족)
                f[k - 1] = BigDecimal.ONE;
                noData.add(k);
                points[k - 1] = 0;
            } else {
                f[k - 1] = num.divide(den, MC);
            }
        }
        if (!noData.isEmpty()) {
            warnings.add("경과 " + noData.stream().map(k -> k + "→" + (k + 1)).collect(java.util.stream.Collectors.joining(", "))
                    + " 진전계수는 계산할 지급 자료가 없어 1.0 으로 두었습니다.");
        }
        if (maxAge >= 1) {
            f[maxAge - 1] = tail;
        }
        BigDecimal[] cdf = new BigDecimal[f.length];
        BigDecimal running = BigDecimal.ONE;
        for (int k = f.length; k >= 1; k--) {
            running = running.multiply(f[k - 1], MC);
            cdf[k - 1] = running;
        }
        List<Factor> factors = new ArrayList<>();
        for (int k = 1; k <= maxAge; k++) {
            factors.add(new Factor(k, f[k - 1].setScale(FACTOR_SCALE, RoundingMode.HALF_UP),
                    cdf[k - 1].setScale(FACTOR_SCALE, RoundingMode.HALF_UP), k < maxAge ? points[k - 1] : 0));
        }

        // 기대손해율: 주어지지 않으면 케이프코드
        boolean estimated = elr == null;
        BigDecimal usedElr = elr;
        if (estimated) {
            BigDecimal paid = BigDecimal.ZERO;
            BigDecimal usedPremium = BigDecimal.ZERO;
            for (Period p : periods) {
                if (p.age() == 0) {
                    continue;
                }
                paid = paid.add(p.paid());
                usedPremium = usedPremium.add(p.earnedPremium().divide(cdf[p.age() - 1], MC));
            }
            usedElr = usedPremium.signum() == 0 ? null : paid.divide(usedPremium, 4, RoundingMode.HALF_UP);
            if (usedElr == null) {
                warnings.add("경과보험료가 없어 기대손해율을 구하지 못해 모든 사고 기간에 체인래더를 씁니다.");
            }
        }

        List<PeriodResult> results = new ArrayList<>();
        for (Period p : periods) {
            BigDecimal c = p.age() == 0 ? BigDecimal.ONE : cdf[p.age() - 1];
            BigDecimal developed = BigDecimal.ONE.divide(c, MC);
            BigDecimal known = p.paid().add(p.caseReserve());
            BigDecimal clUlt = won(p.paid().multiply(c, MC));
            BigDecimal clIbnr = clUlt.subtract(known);
            BigDecimal bfUlt = usedElr == null ? null
                    : won(p.paid().add(usedElr.multiply(p.earnedPremium(), MC).multiply(BigDecimal.ONE.subtract(developed), MC)));
            BigDecimal bfIbnr = bfUlt == null ? null : bfUlt.subtract(known);
            boolean mature = developed.doubleValue() >= MATURE_THRESHOLD || bfUlt == null;
            BigDecimal selectedUlt = mature ? clUlt : bfUlt;
            BigDecimal selectedIbnr = (mature ? clIbnr : bfIbnr).max(BigDecimal.ZERO);
            results.add(new PeriodResult(p.label(), p.age(), p.earnedPremium(), p.paid(), p.caseReserve(),
                    c.setScale(FACTOR_SCALE, RoundingMode.HALF_UP), developed.setScale(4, RoundingMode.HALF_UP),
                    clUlt, clIbnr, bfUlt, bfIbnr, mature ? "CL" : "BF", selectedUlt, selectedIbnr));
            if (p.paid().signum() == 0 && p.earnedPremium().signum() > 0 && mature) {
                warnings.add("사고 기간 " + p.label() + " 은 지급액이 0 이라 체인래더 최종손해액이 0 입니다.");
            }
        }
        return new Result(factors, usedElr, estimated, results, warnings);
    }

    private static BigDecimal won(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }
}
