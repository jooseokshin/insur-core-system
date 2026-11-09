package com.insur.insurcoresystem.service.stats;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.stats.dto.LossRatioRequest;
import com.insur.insurcoresystem.service.stats.dto.LossRatioResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 손해율 집계. 사고년도 기준을 단순화한 정의를 쓴다.
 * <ul>
 *     <li>경과보험료: 유효·해지 계약의 담보 보험료 × (집계 기간과 보장 기간이 겹친 일수 ÷ 보험기간 일수).
 *         보장 기간은 보험시기 ~ min(보험종기, 해지일) 이고 끝 날짜는 보장하지 않는다(해지 환급과 같은 기준).
 *         청약·청약취소 계약은 보장한 적이 없으므로 넣지 않는다.</li>
 *     <li>발생손해액: 사고일이 집계 기간 안인 사고의 지급 보험금(지급 완료) + 미지급 추산액(지급 대기는 사정 지급액, 접수는 청구액).
 *         면책·접수 취소는 넣지 않는다. 아직 보고되지 않은 손해(IBNR)는 추정하지 않는다.</li>
 *     <li>손해율 = 발생손해액 ÷ 경과보험료.</li>
 * </ul>
 * 여러 테이블을 기간으로 잘라 합하는 집계라 SQL 로 직접 계산한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LossRatioService {

    /** 한 번에 집계할 수 있는 최대 기간 (일) */
    static final long MAX_PERIOD_DAYS = 366L * 5;

    /** 묶는 단위별 그룹 컬럼 */
    private static final Map<String, List<String>> GROUP_KEYS = Map.of(
            "CLASS", List.of("INSR_CLS_CD"),
            "PRODUCT", List.of("INSR_CLS_CD", "PRD_CD"),
            "COVERAGE", List.of("INSR_CLS_CD", "CVRG_CD"));

    /** 담보별 경과 일수: 집계 기간 [from, toExcl) 과 보장 기간 [시기, min(종기, 해지일)) 이 겹친 일수 */
    private static final String EXPOSURE = """
            SELECT L.CONTRACT_NO, L.INSR_CLS_CD, L.PRD_CD, V.CVRG_CD, V.PREM,
                   (L.END_DATE - L.START_DATE) AS TERM_DAYS,
                   GREATEST(0, LEAST(L.END_DATE, COALESCE(L.TERM_DT, L.END_DATE), :toExcl)
                               - GREATEST(L.START_DATE, :fromDate)) AS EXPOSED_DAYS
              FROM TB_CTR_LGD L
              JOIN TB_CTR_CVRG V ON V.CONTRACT_NO = L.CONTRACT_NO
             WHERE L.CTR_STAT_CD IN ('20', '30')
            """;

    /** 사고일이 기간 안인 사고의 담보별 지급액·추산액 */
    private static final String LOSSES = """
            SELECT L.INSR_CLS_CD, L.PRD_CD, I.CVRG_CD, C.CLM_NO,
                   CASE WHEN C.CLM_STAT_CD = '40' THEN I.PAY_AMT ELSE 0 END AS PAID,
                   CASE WHEN C.CLM_STAT_CD = '20' THEN I.PAY_AMT
                        WHEN C.CLM_STAT_CD = '10' THEN I.CLAIM_AMT ELSE 0 END AS OUTSTANDING
              FROM TB_CLM C
              JOIN TB_CLM_CVRG I ON I.CLM_NO = C.CLM_NO
              JOIN TB_CTR_LGD L ON L.CONTRACT_NO = C.CONTRACT_NO
             WHERE C.CLM_STAT_CD IN ('10', '20', '40')
               AND C.ACDT_DT >= :fromDate AND C.ACDT_DT < :toExcl
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 기간과 묶는 단위로 손해율을 집계한다.
     *
     * @throws BusinessException 기간 오류(E-STAT-001), 묶는 단위 오류(E-STAT-002)
     */
    @Transactional(readOnly = true)
    public LossRatioResponse lossRatio(LossRatioRequest request) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = request.getFromDate() == null ? today.withDayOfYear(1) : request.getFromDate();
        LocalDate to = request.getToDate() == null ? today : request.getToDate();
        if (from.isAfter(to)) {
            throw new BusinessException("E-STAT-001", "집계 시작일은 종료일보다 클 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_PERIOD_DAYS) {
            throw new BusinessException("E-STAT-001", "집계 기간은 5년을 넘을 수 없습니다.");
        }
        String groupBy = StringUtils.hasText(request.getGroupBy()) ? request.getGroupBy() : "PRODUCT";
        List<String> keys = GROUP_KEYS.get(groupBy);
        if (keys == null) {
            throw new BusinessException("E-STAT-002", "묶는 단위는 CLASS, PRODUCT, COVERAGE 중 하나여야 합니다: " + groupBy);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fromDate", from)
                .addValue("toExcl", to.plusDays(1));

        Map<String, LossRatioResponse.Row> rows = new LinkedHashMap<>();
        aggregate(keys, params).forEach(r -> rows.put(key(r), r));
        List<LossRatioResponse.Row> sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparing((LossRatioResponse.Row r) -> Objects.toString(r.getInsrClsCd(), ""))
                .thenComparing(r -> Objects.toString(r.getPrdCd(), ""))
                .thenComparing(r -> Objects.toString(r.getCvrgCd(), "")));
        LossRatioResponse.Row total = aggregate(List.of(), params).stream().findFirst().orElseGet(() -> row(null, null, null,
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO));

        log.info("손해율 집계: {} ~ {}, 단위={}, {}행, 전체 손해율={}", from, to, groupBy, sorted.size(), total.getLossRatio());
        return LossRatioResponse.builder()
                .fromDate(from)
                .toDate(to)
                .groupBy(groupBy)
                .rows(sorted)
                .total(total)
                .build();
    }

    /**
     * 경과보험료와 손해액을 같은 그룹 컬럼으로 각각 집계해 합친다. 그룹 컬럼이 없으면 전체 합계 한 행이다.
     * (담보 단위로 묶은 행을 더하면 계약 수가 겹치므로, 전체 합계는 이 메서드를 그룹 없이 다시 불러 구한다)
     */
    private List<LossRatioResponse.Row> aggregate(List<String> keys, MapSqlParameterSource params) {
        String select = keys.isEmpty() ? "" : String.join(", ", keys) + ", ";
        String groupBy = keys.isEmpty() ? "" : " GROUP BY " + String.join(", ", keys);

        Map<String, Object[]> earned = new LinkedHashMap<>();
        jdbc.query("SELECT " + select
                + "COUNT(DISTINCT CONTRACT_NO) AS CNT, COALESCE(SUM(PREM * EXPOSED_DAYS / NULLIF(TERM_DAYS, 0)), 0) AS EARNED"
                + " FROM (" + EXPOSURE + ") E WHERE EXPOSED_DAYS > 0" + groupBy, params, rs -> {
            String[] k = readKeys(rs, keys);
            earned.put(String.join("|", k), new Object[] {k, rs.getLong("CNT"), rs.getBigDecimal("EARNED")});
        });
        Map<String, Object[]> losses = new LinkedHashMap<>();
        jdbc.query("SELECT " + select
                + "COUNT(DISTINCT CLM_NO) AS CNT, COALESCE(SUM(PAID), 0) AS PAID, COALESCE(SUM(OUTSTANDING), 0) AS OUTSTANDING"
                + " FROM (" + LOSSES + ") X" + groupBy, params, rs -> {
            String[] k = readKeys(rs, keys);
            losses.put(String.join("|", k), new Object[] {k, rs.getLong("CNT"), rs.getBigDecimal("PAID"), rs.getBigDecimal("OUTSTANDING")});
        });

        Map<String, String[]> allKeys = new LinkedHashMap<>();
        earned.forEach((k, v) -> allKeys.put(k, (String[]) v[0]));
        losses.forEach((k, v) -> allKeys.putIfAbsent(k, (String[]) v[0]));
        List<LossRatioResponse.Row> result = new ArrayList<>();
        allKeys.forEach((k, parts) -> {
            Object[] e = earned.get(k);
            Object[] l = losses.get(k);
            result.add(row(
                    keys.contains("INSR_CLS_CD") ? parts[keys.indexOf("INSR_CLS_CD")] : null,
                    keys.contains("PRD_CD") ? parts[keys.indexOf("PRD_CD")] : null,
                    keys.contains("CVRG_CD") ? parts[keys.indexOf("CVRG_CD")] : null,
                    e == null ? 0 : (long) e[1],
                    e == null ? BigDecimal.ZERO : (BigDecimal) e[2],
                    l == null ? 0 : (long) l[1],
                    l == null ? BigDecimal.ZERO : (BigDecimal) l[2],
                    l == null ? BigDecimal.ZERO : (BigDecimal) l[3]));
        });
        return result;
    }

    private static String[] readKeys(java.sql.ResultSet rs, List<String> keys) throws java.sql.SQLException {
        String[] parts = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            parts[i] = rs.getString(keys.get(i));
        }
        return parts;
    }

    private static LossRatioResponse.Row row(String insrClsCd, String prdCd, String cvrgCd, long contracts, BigDecimal earnedRaw,
                                             long claims, BigDecimal paid, BigDecimal outstanding) {
        BigDecimal earned = earnedRaw.setScale(0, RoundingMode.HALF_UP);
        BigDecimal incurred = paid.add(outstanding);
        return LossRatioResponse.Row.builder()
                .insrClsCd(insrClsCd)
                .prdCd(prdCd)
                .cvrgCd(cvrgCd)
                .contractCount(contracts)
                .earnedPremium(earned)
                .claimCount(claims)
                .paidLoss(paid)
                .outstandingLoss(outstanding)
                .incurredLoss(incurred)
                .lossRatio(earned.signum() == 0 ? null : incurred.divide(earned, 4, RoundingMode.HALF_UP))
                .build();
    }

    private static String key(LossRatioResponse.Row r) {
        return Objects.toString(r.getInsrClsCd(), "") + "|" + Objects.toString(r.getPrdCd(), "") + "|" + Objects.toString(r.getCvrgCd(), "");
    }
}
