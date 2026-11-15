package com.insur.insurcoresystem.service.stats;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.mapper.stats.StatsMapper;
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
    private static final Map<String, StatsMapper.GroupKeys> GROUP_KEYS = Map.of(
            "CLASS", new StatsMapper.GroupKeys(true, false, false),
            "PRODUCT", new StatsMapper.GroupKeys(true, true, false),
            "COVERAGE", new StatsMapper.GroupKeys(true, false, true));

    /** 전체 한 줄 (계약·사고를 중복 없이 센다) */
    private static final StatsMapper.GroupKeys TOTAL = new StatsMapper.GroupKeys(false, false, false);

    private final StatsMapper statsMapper;
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
        StatsMapper.GroupKeys keys = GROUP_KEYS.get(groupBy);
        if (keys == null) {
            throw new BusinessException("E-STAT-002", "묶는 단위는 CLASS, PRODUCT, COVERAGE 중 하나여야 합니다: " + groupBy);
        }
        LocalDate toExcl = to.plusDays(1);

        Map<String, LossRatioResponse.Row> rows = new LinkedHashMap<>();
        aggregate(keys, from, toExcl).forEach(r -> rows.put(key(r), r));
        List<LossRatioResponse.Row> sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparing((LossRatioResponse.Row r) -> Objects.toString(r.getInsrClsCd(), ""))
                .thenComparing(r -> Objects.toString(r.getPrdCd(), ""))
                .thenComparing(r -> Objects.toString(r.getCvrgCd(), "")));
        LossRatioResponse.Row total = aggregate(TOTAL, from, toExcl).stream().findFirst().orElseGet(() -> row(null, null, null,
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
    private List<LossRatioResponse.Row> aggregate(StatsMapper.GroupKeys keys, LocalDate from, LocalDate toExcl) {
        Map<String, StatsMapper.EarnedRow> earned = new LinkedHashMap<>();
        statsMapper.aggregateEarned(keys, from, toExcl).forEach(r -> earned.put(key(r.insrClsCd(), r.prdCd(), r.cvrgCd()), r));
        Map<String, StatsMapper.LossRow> losses = new LinkedHashMap<>();
        statsMapper.aggregateLosses(keys, from, toExcl).forEach(r -> losses.put(key(r.insrClsCd(), r.prdCd(), r.cvrgCd()), r));

        Map<String, String[]> allKeys = new LinkedHashMap<>();
        earned.forEach((k, r) -> allKeys.put(k, new String[] {r.insrClsCd(), r.prdCd(), r.cvrgCd()}));
        losses.forEach((k, r) -> allKeys.putIfAbsent(k, new String[] {r.insrClsCd(), r.prdCd(), r.cvrgCd()}));
        List<LossRatioResponse.Row> result = new ArrayList<>();
        allKeys.forEach((k, parts) -> {
            StatsMapper.EarnedRow e = earned.get(k);
            StatsMapper.LossRow l = losses.get(k);
            result.add(row(parts[0], parts[1], parts[2],
                    e == null ? 0 : e.cnt(),
                    e == null ? BigDecimal.ZERO : e.earned(),
                    l == null ? 0 : l.cnt(),
                    l == null ? BigDecimal.ZERO : l.paid(),
                    l == null ? BigDecimal.ZERO : l.outstanding()));
        });
        return result;
    }

    private static String key(String insrClsCd, String prdCd, String cvrgCd) {
        return Objects.toString(insrClsCd, "") + "|" + Objects.toString(prdCd, "") + "|" + Objects.toString(cvrgCd, "");
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
        return key(r.getInsrClsCd(), r.getPrdCd(), r.getCvrgCd());
    }
}
