package com.insur.insurcoresystem.service.stats;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.stats.dto.LossRatioRequest;
import com.insur.insurcoresystem.service.stats.dto.LossRatioResponse;
import com.insur.insurcoresystem.service.stats.dto.RateImpactRequest;
import com.insur.insurcoresystem.service.stats.dto.RateImpactResponse;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 요율 영향 시뮬레이션. 담보별 기본 요율을 바꾸면 손해율과 갱신 보험료가 어떻게 달라지는지 계산한다. 아무것도 저장하지 않는다.
 * <ul>
 *     <li>경험: 경험 기간의 경과보험료·발생손해액은 손해율 집계({@link LossRatioService})와 같은 정의다.</li>
 *     <li>새 요율의 손해율: 같은 계약과 같은 사고가 그대로이고 보험료만 변경률만큼 달랐다고 본다.
 *         보험료 = 가입금액 × 연 요율 × 계수 × 일수 ÷ 365 라서 기본 요율에 정비례하기 때문이다.</li>
 *     <li>필요 변경률(손해율법): 손해율 ÷ 목표 손해율 − 1. 목표 손해율은 1 − 사업비율 − 목표 이익률로 정하는 값이라 요청으로 받는다.</li>
 *     <li>신뢰도(제한변동 신뢰도): 완전 신뢰 기준 1,082건 = (1.645 ÷ 0.05)², 사고 건수가 포아송 분포일 때 실제 건수가 기대값의
 *         ±5% 안에 들 확률이 90% 가 되는 건수다. Z = min(1, √(n ÷ 1,082)), 나머지 1 − Z 는 현재 요율 유지(0%)로 본다.</li>
 *     <li>갱신 영향: 오늘부터 기간 안에 만기가 오고 아직 갱신하지 않은 유효 계약이 같은 조건으로 갱신한다고 보고, 현재 보험료 × 변경률.</li>
 * </ul>
 * 하지 않는 것: 손해 추세·물가 반영, IBNR·손해 진전, 사업비 재계산, 요율을 바꿨을 때의 해약·신계약 변화(가격 탄력성),
 * 경험 기간 중 요율이 바뀐 경우의 온레벨 조정. 해당하는 상황은 warnings 로 알려 준다. 요율 결정의 참고 자료이지 계리 검증을 대신하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateImpactService {

    /** 완전 신뢰도 기준 사고 건수: (z₀.₉₅ ÷ k)² = (1.645 ÷ 0.05)² ≈ 1,082 */
    static final long FULL_CREDIBILITY_CLAIMS = 1082;
    static final BigDecimal MIN_CHANGE_PCT = new BigDecimal("-50");
    static final BigDecimal MAX_CHANGE_PCT = new BigDecimal("100");
    static final BigDecimal MAX_TARGET_LOSS_RATIO = new BigDecimal("1.5");
    static final int MAX_RENEWAL_WINDOW_DAYS = 365;
    /** 사고일로부터 이 일수 안의 사고는 아직 다 보고되지 않았을 수 있다고 알린다 (IBNR 경고 기준) */
    static final int RECENT_REPORTING_DAYS = 90;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext MC = MathContext.DECIMAL64;

    /** 오늘 적용 중인 담보별 연 요율 */
    private static final String CURRENT_TARIFF = """
            SELECT CVRG_CD, CVRG_NM, ANNUAL_RATE
              FROM TB_PRM_RATE
             WHERE INSR_CLS_CD = :cls AND EFF_FROM <= :today AND (EFF_TO IS NULL OR EFF_TO >= :today)
             ORDER BY CVRG_CD
            """;

    /** 경험 기간 중(시작일 다음 날 ~ 종료일)에 시작한 요율·계수 개정과 오늘 이후로 예정된 개정 */
    private static final String REVISIONS = """
            SELECT 'RATE' AS KIND, CVRG_CD AS TARGET, EFF_FROM FROM TB_PRM_RATE
             WHERE INSR_CLS_CD = :cls AND (EFF_FROM > :fromDate AND EFF_FROM <= :toDate OR EFF_FROM > :today)
            UNION ALL
            SELECT 'FACTOR', FACTOR_TYPE, EFF_FROM FROM TB_PRM_FACTOR
             WHERE INSR_CLS_CD = :cls AND (EFF_FROM > :fromDate AND EFF_FROM <= :toDate OR EFF_FROM > :today)
             ORDER BY 3, 1, 2
            """;

    /** 오늘 유효한 계약과, 그 가운데 갱신 영향 기간 안에 만기가 오고 아직 갱신하지 않은 계약 (갱신 대상 화면과 같은 기준) */
    private static final String IN_FORCE = """
            SELECT %s
                   COUNT(DISTINCT L.CONTRACT_NO) AS CNT,
                   COALESCE(SUM(V.PREM), 0) AS PREM,
                   COUNT(DISTINCT CASE WHEN L.END_DATE <= :renewTo AND NOT EXISTS (
                           SELECT 1 FROM TB_CTR_LGD R WHERE R.RENEWED_FROM = L.CONTRACT_NO AND R.CTR_STAT_CD <> '90')
                         THEN L.CONTRACT_NO END) AS RCNT,
                   COALESCE(SUM(CASE WHEN L.END_DATE <= :renewTo AND NOT EXISTS (
                           SELECT 1 FROM TB_CTR_LGD R WHERE R.RENEWED_FROM = L.CONTRACT_NO AND R.CTR_STAT_CD <> '90')
                         THEN V.PREM ELSE 0 END), 0) AS RPREM
              FROM TB_CTR_LGD L
              JOIN TB_CTR_CVRG V ON V.CONTRACT_NO = L.CONTRACT_NO
             WHERE L.CTR_STAT_CD = '20' AND L.INSR_CLS_CD = :cls
               AND L.START_DATE <= :today AND L.END_DATE > :today
            %s
            """;

    private final LossRatioService lossRatioService;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /**
     * @throws BusinessException 기간 오류(E-STAT-001), 보험종목 없음·요율표 없음(E-STAT-003), 변경 내용 오류(E-STAT-004),
     *                           목표 손해율 범위 오류(E-STAT-005), 갱신 영향 기간 범위 오류(E-STAT-006)
     */
    @Transactional(readOnly = true)
    public RateImpactResponse simulate(RateImpactRequest request) {
        LocalDate today = LocalDate.now(clock);
        String cls = StringUtils.hasText(request.getInsrClsCd()) ? request.getInsrClsCd().trim() : null;
        if (cls == null) {
            throw new BusinessException("E-STAT-003", "보험종목 코드를 입력해 주세요.");
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("cls", cls).addValue("today", today);

        Map<String, Object[]> tariff = new LinkedHashMap<>();
        jdbc.query(CURRENT_TARIFF, params, rs -> {
            tariff.put(rs.getString("CVRG_CD"), new Object[] {rs.getString("CVRG_NM"), rs.getBigDecimal("ANNUAL_RATE")});
        });
        if (tariff.isEmpty()) {
            throw new BusinessException("E-STAT-003", "오늘 적용 중인 요율표가 없는 보험종목입니다: " + cls);
        }
        Map<String, BigDecimal> changes = validateChanges(request.getChanges(), tariff.keySet());
        BigDecimal target = request.getTargetLossRatio();
        if (target != null && (target.signum() <= 0 || target.compareTo(MAX_TARGET_LOSS_RATIO) > 0)) {
            throw new BusinessException("E-STAT-005", "목표 손해율은 0 보다 크고 1.5 이하여야 합니다.");
        }
        int window = request.getRenewalWindowDays() == null ? MAX_RENEWAL_WINDOW_DAYS : request.getRenewalWindowDays();
        if (window < 1 || window > MAX_RENEWAL_WINDOW_DAYS) {
            throw new BusinessException("E-STAT-006", "갱신 영향 기간은 1~365일이어야 합니다.");
        }
        LocalDate to = request.getToDate() == null ? today : request.getToDate();
        LocalDate from = request.getFromDate() == null ? to.minusDays(364) : request.getFromDate();
        LocalDate renewTo = today.plusDays(window);

        // 경험: 손해율 집계와 같은 정의 (기간 검증 E-STAT-001 도 거기서 한다)
        LossRatioResponse byCoverage = lossRatioService.lossRatio(new LossRatioRequest(from, to, "COVERAGE"));
        LossRatioResponse byClass = lossRatioService.lossRatio(new LossRatioRequest(from, to, "CLASS"));
        Map<String, LossRatioResponse.Row> experience = new HashMap<>();
        byCoverage.getRows().stream().filter(r -> cls.equals(r.getInsrClsCd())).forEach(r -> experience.put(r.getCvrgCd(), r));
        LossRatioResponse.Row classTotal = byClass.getRows().stream().filter(r -> cls.equals(r.getInsrClsCd())).findFirst().orElse(null);

        params.addValue("renewTo", renewTo).addValue("fromDate", from).addValue("toDate", to);
        Map<String, long[]> counts = new HashMap<>();
        Map<String, BigDecimal[]> premiums = new HashMap<>();
        jdbc.query(IN_FORCE.formatted("V.CVRG_CD,", "GROUP BY V.CVRG_CD"), params, rs -> {
            counts.put(rs.getString("CVRG_CD"), new long[] {rs.getLong("CNT"), rs.getLong("RCNT")});
            premiums.put(rs.getString("CVRG_CD"), new BigDecimal[] {rs.getBigDecimal("PREM"), rs.getBigDecimal("RPREM")});
        });

        List<RateImpactResponse.Row> rows = new ArrayList<>();
        BigDecimal simulatedEarnedTotal = BigDecimal.ZERO;
        BigDecimal earnedTotal = BigDecimal.ZERO;
        BigDecimal renewalChangeTotal = BigDecimal.ZERO;
        for (Map.Entry<String, Object[]> t : tariff.entrySet()) {
            String cvrg = t.getKey();
            BigDecimal pct = changes.getOrDefault(cvrg, BigDecimal.ZERO);
            BigDecimal factor = BigDecimal.ONE.add(pct.divide(HUNDRED, MC));
            LossRatioResponse.Row e = experience.get(cvrg);
            BigDecimal earned = e == null ? BigDecimal.ZERO : e.getEarnedPremium();
            BigDecimal incurred = e == null ? BigDecimal.ZERO : e.getIncurredLoss();
            long claims = e == null ? 0 : e.getClaimCount();
            long[] c = counts.getOrDefault(cvrg, new long[2]);
            BigDecimal[] p = premiums.getOrDefault(cvrg, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal renewalChange = p[1].multiply(pct).divide(HUNDRED, 0, RoundingMode.HALF_UP);

            earnedTotal = earnedTotal.add(earned);
            simulatedEarnedTotal = simulatedEarnedTotal.add(earned.multiply(factor));
            renewalChangeTotal = renewalChangeTotal.add(renewalChange);
            BigDecimal currentRate = (BigDecimal) t.getValue()[1];
            rows.add(row(cvrg, (String) t.getValue()[0], currentRate,
                    currentRate.multiply(factor).setScale(6, RoundingMode.HALF_UP), pct.setScale(2, RoundingMode.HALF_UP),
                    earned, earned.multiply(factor), incurred, claims, target, c[0], p[0], c[1], p[1], renewalChange));
        }

        // 전체: 경과보험료·손해액은 담보 합계와 같고, 계약·사고 건수는 중복 없이 다시 센다
        long[] totalCounts = new long[2];
        BigDecimal[] totalPremiums = {BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query(IN_FORCE.formatted("", ""), params, rs -> {
            totalCounts[0] = rs.getLong("CNT");
            totalCounts[1] = rs.getLong("RCNT");
            totalPremiums[0] = rs.getBigDecimal("PREM");
            totalPremiums[1] = rs.getBigDecimal("RPREM");
        });
        BigDecimal incurredTotal = classTotal == null ? BigDecimal.ZERO : classTotal.getIncurredLoss();
        long claimTotal = classTotal == null ? 0 : classTotal.getClaimCount();
        BigDecimal averagePct = earnedTotal.signum() == 0 ? null
                : simulatedEarnedTotal.divide(earnedTotal, MC).subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
        RateImpactResponse.Row total = row(null, null, null, null, averagePct, earnedTotal, simulatedEarnedTotal, incurredTotal,
                claimTotal, target, totalCounts[0], totalPremiums[0], totalCounts[1], totalPremiums[1], renewalChangeTotal);

        List<String> warnings = warnings(params, from, to, today, claimTotal);
        log.info("요율 영향 시뮬레이션: 종목={}, 경험 {} ~ {}, 변경 {}건, 손해율 {} → {}", cls, from, to, changes.size(),
                total.getLossRatio(), total.getSimulatedLossRatio());
        return RateImpactResponse.builder()
                .insrClsCd(cls)
                .fromDate(from)
                .toDate(to)
                .targetLossRatio(target)
                .renewalFrom(today)
                .renewalTo(renewTo)
                .fullCredibilityClaims(FULL_CREDIBILITY_CLAIMS)
                .rows(rows)
                .total(total)
                .warnings(warnings)
                .build();
    }

    private static Map<String, BigDecimal> validateChanges(List<RateImpactRequest.Change> requested, java.util.Set<String> coverages) {
        Map<String, BigDecimal> changes = new LinkedHashMap<>();
        if (requested == null) {
            return changes;
        }
        for (RateImpactRequest.Change change : requested) {
            if (change == null || !StringUtils.hasText(change.getCvrgCd()) || change.getRateChangePct() == null) {
                throw new BusinessException("E-STAT-004", "담보 코드와 변경률을 모두 입력해 주세요.");
            }
            String cvrg = change.getCvrgCd().trim();
            BigDecimal pct = change.getRateChangePct();
            if (!coverages.contains(cvrg)) {
                throw new BusinessException("E-STAT-004", "이 보험종목의 현재 요율표에 없는 담보입니다: " + cvrg);
            }
            if (pct.compareTo(MIN_CHANGE_PCT) < 0 || pct.compareTo(MAX_CHANGE_PCT) > 0 || pct.stripTrailingZeros().scale() > 2) {
                throw new BusinessException("E-STAT-004", "변경률은 -50 ~ +100% 사이, 소수 둘째 자리까지여야 합니다: " + cvrg);
            }
            if (changes.put(cvrg, pct) != null) {
                throw new BusinessException("E-STAT-004", "같은 담보를 두 번 넣었습니다: " + cvrg);
            }
        }
        return changes;
    }

    private static RateImpactResponse.Row row(String cvrg, String cvrgNm, BigDecimal currentRate, BigDecimal newRate, BigDecimal pct,
                                              BigDecimal earned, BigDecimal simulatedEarned, BigDecimal incurred, long claims,
                                              BigDecimal target, long inForce, BigDecimal inForcePremium,
                                              long renewals, BigDecimal renewalPremium, BigDecimal renewalChange) {
        BigDecimal lossRatio = earned.signum() == 0 ? null : incurred.divide(earned, MC);
        BigDecimal simulated = simulatedEarned.signum() == 0 ? null : incurred.divide(simulatedEarned, 4, RoundingMode.HALF_UP);
        BigDecimal z = BigDecimal.valueOf(claims).divide(BigDecimal.valueOf(FULL_CREDIBILITY_CLAIMS), MC).sqrt(MC).min(BigDecimal.ONE);
        BigDecimal indicated = lossRatio == null || target == null ? null
                : lossRatio.divide(target, MC).subtract(BigDecimal.ONE).multiply(HUNDRED);
        return RateImpactResponse.Row.builder()
                .cvrgCd(cvrg)
                .cvrgNm(cvrgNm)
                .currentRate(currentRate)
                .newRate(newRate)
                .rateChangePct(pct)
                .earnedPremium(earned.setScale(0, RoundingMode.HALF_UP))
                .incurredLoss(incurred)
                .claimCount(claims)
                .lossRatio(lossRatio == null ? null : lossRatio.setScale(4, RoundingMode.HALF_UP))
                .simulatedLossRatio(simulated)
                .indicatedChangePct(indicated == null ? null : indicated.setScale(2, RoundingMode.HALF_UP))
                .credibility(z.setScale(4, RoundingMode.HALF_UP))
                .credibilityWeightedChangePct(indicated == null ? null : indicated.multiply(z).setScale(2, RoundingMode.HALF_UP))
                .inForceContracts(inForce)
                .inForcePremium(inForcePremium)
                .renewalContracts(renewals)
                .renewalPremium(renewalPremium)
                .renewalPremiumChange(renewalChange)
                .build();
    }

    private List<String> warnings(MapSqlParameterSource params, LocalDate from, LocalDate to, LocalDate today, long claims) {
        List<String> warnings = new ArrayList<>();
        jdbc.query(REVISIONS, params, rs -> {
            String what = ("RATE".equals(rs.getString("KIND")) ? "담보 요율 " : "요율 계수 ") + rs.getString("TARGET");
            LocalDate effFrom = rs.getDate("EFF_FROM").toLocalDate();
            warnings.add(effFrom.isAfter(today)
                    ? what + " 개정이 " + effFrom + " 부터 예정되어 있습니다. 갱신 영향은 오늘 요율을 기준으로 계산했습니다."
                    : what + " 개정(" + effFrom + " 시작)이 경험 기간 안에 있습니다. 경과보험료를 현재 요율 수준으로 맞추지(온레벨) 않았으므로 손해율이 왜곡될 수 있습니다.");
        });
        if (!to.isBefore(today.minusDays(RECENT_REPORTING_DAYS))) {
            warnings.add("경험 기간이 최근 " + RECENT_REPORTING_DAYS + "일을 포함합니다. 아직 보고되지 않은 사고(IBNR)를 넣지 않아 손해율이 실제보다 낮게 나올 수 있습니다.");
        }
        if (claims < FULL_CREDIBILITY_CLAIMS) {
            warnings.add("사고 " + claims + "건으로 완전 신뢰 기준(" + FULL_CREDIBILITY_CLAIMS
                    + "건)에 못 미칩니다. 필요 변경률보다 신뢰도 반영 변경률을 참고하세요.");
        }
        return warnings;
    }
}
