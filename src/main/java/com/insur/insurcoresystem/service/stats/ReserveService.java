package com.insur.insurcoresystem.service.stats;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.sequence.ProductType;
import com.insur.insurcoresystem.service.stats.dto.IbnrRequest;
import com.insur.insurcoresystem.service.stats.dto.IbnrResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IBNR(미보고발생손해액) 산출. 손해율 분석(LossRatioService)은 지금까지 접수된 사고만 세므로 최근 기간일수록 손해가 작게 보인다.
 * 사고는 났는데 아직 접수되지 않았거나, 접수됐어도 지급이 덜 끝난 몫을 과거의 진전 양상으로 추정하는 것이 IBNR 이다.
 *
 * <h3>삼각형을 만드는 기준</h3>
 * <ul>
 *     <li>사고 기간: 사고일(ACDT_DT) 기준 분기 또는 연. 감독 기준도 손해진전계수는 사고일(원인사고일) 기준을 원칙으로 한다.</li>
 *     <li>경과: 지급일(지급 결정 DECIDE_DT)이 사고 기간 이후 몇 번째 기간인지 (사고 기간 안에서 지급하면 1).</li>
 *     <li>지급액: 지급 결정(40)된 사고의 지급액 합계. 평가 기준일 이후 지급은 넣지 않는다.</li>
 *     <li>개별추산액: 기준일까지 접수됐고 기준일에 아직 종결되지 않은 사고. 기준일 전에 손해사정했으면 지급 예정액, 아니면 청구액.
 *         지금 값으로 과거 상태를 되짚으므로 재사정·면책으로 금액이 바뀐 사고는 근사값이다(사정 이력 금액을 따로 남기지 않음).</li>
 *     <li>경과보험료: 손해율 분석과 같은 정의(유효·해지 계약의 담보 보험료 × 사고 기간 안에서 보장한 일수 ÷ 보험기간 일수, 기준일까지).</li>
 * </ul>
 * 계산식은 {@link ChainLadder}. 추세·물가, 대형 사고 분리, 손해조사비, 할인과 위험조정(IFRS17)은 넣지 않았다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReserveService {

    public static final String QUARTER = "QUARTER";
    public static final String YEAR = "YEAR";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /** 사고 기간 하나 */
    record AccidentPeriod(String label, LocalDate from, LocalDate to, int index) {
    }

    /**
     * IBNR 을 산출한다.
     *
     * @throws BusinessException 조건 오류(E-RSV-001)
     */
    @Transactional(readOnly = true)
    public IbnrResponse ibnr(IbnrRequest request) {
        if (!StringUtils.hasText(request.getInsrClsCd())) {
            throw new BusinessException("E-RSV-001", "보험종목코드를 입력해야 합니다.");
        }
        try {
            ProductType.fromInsrClsCd(request.getInsrClsCd());
        } catch (BusinessException e) {
            throw new BusinessException("E-RSV-001", "없는 보험종목코드입니다: " + request.getInsrClsCd());
        }
        String type = StringUtils.hasText(request.getPeriodType()) ? request.getPeriodType() : QUARTER;
        if (!QUARTER.equals(type) && !YEAR.equals(type)) {
            throw new BusinessException("E-RSV-001", "사고 기간 단위는 QUARTER 또는 YEAR 입니다: " + type);
        }
        int max = QUARTER.equals(type) ? 40 : 10;
        int count = request.getPeriods() == null ? (QUARTER.equals(type) ? 12 : 5) : request.getPeriods();
        if (count < 2 || count > max) {
            throw new BusinessException("E-RSV-001", "사고 기간 수는 2~" + max + " 이어야 합니다.");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate eval = request.getEvaluationDate() == null ? previousPeriodEnd(today, type) : request.getEvaluationDate();
        if (eval.isAfter(today)) {
            throw new BusinessException("E-RSV-001", "평가 기준일은 오늘 이후일 수 없습니다.");
        }
        BigDecimal tail = request.getTailFactor() == null ? BigDecimal.ONE : request.getTailFactor();
        if (tail.compareTo(BigDecimal.ONE) < 0 || tail.compareTo(BigDecimal.valueOf(3)) > 0) {
            throw new BusinessException("E-RSV-001", "꼬리 진전계수는 1 이상 3 이하여야 합니다.");
        }
        BigDecimal elr = request.getExpectedLossRatio();
        if (elr != null && (elr.signum() <= 0 || elr.compareTo(BigDecimal.valueOf(3)) > 0)) {
            throw new BusinessException("E-RSV-001", "기대손해율은 0 초과 3 이하여야 합니다.");
        }
        return compute(request.getInsrClsCd(), eval, type, count, tail, elr);
    }

    /** 검증을 마친 조건으로 계산한다 (결산 배치도 이 메서드를 쓴다) */
    public IbnrResponse compute(String insrClsCd, LocalDate eval, String type, int count, BigDecimal tail, BigDecimal elr) {
        List<AccidentPeriod> periods = periods(eval, type, count);
        LocalDate from = periods.get(0).from();
        int evalIndex = index(eval, type);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cls", insrClsCd).addValue("from", from).addValue("eval", eval)
                .addValue("evalExcl", eval.plusDays(1).atStartOfDay());

        // 사고 기간·경과별 증분 지급액
        BigDecimal[][] incremental = new BigDecimal[count][];
        for (int i = 0; i < count; i++) {
            incremental[i] = new BigDecimal[evalIndex - periods.get(i).index() + 1];
            Arrays.fill(incremental[i], BigDecimal.ZERO);
        }
        jdbc.query("""
                SELECT C.ACDT_DT, CAST(C.DECIDE_DT AS DATE) AS PAID_DT, C.TOTAL_PAY_AMT
                  FROM TB_CLM C JOIN TB_CTR_LGD L ON L.CONTRACT_NO = C.CONTRACT_NO
                 WHERE L.INSR_CLS_CD = :cls AND C.CLM_STAT_CD = '40' AND C.TOTAL_PAY_AMT > 0
                   AND C.ACDT_DT >= :from AND C.ACDT_DT <= :eval AND C.DECIDE_DT < :evalExcl
                """, params, rs -> {
            int i = index(rs.getObject("ACDT_DT", LocalDate.class), type) - periods.get(0).index();
            int age = index(rs.getObject("PAID_DT", LocalDate.class), type) - periods.get(i).index();
            // 사고일보다 먼저 지급된 자료는 없지만 혹시 있으면 경과 1에 넣는다
            age = Math.max(0, Math.min(age, incremental[i].length - 1));
            incremental[i][age] = incremental[i][age].add(rs.getBigDecimal("TOTAL_PAY_AMT"));
        });

        // 기준일의 개별추산액
        BigDecimal[] caseReserve = new BigDecimal[count];
        Arrays.fill(caseReserve, BigDecimal.ZERO);
        jdbc.query("""
                SELECT C.ACDT_DT,
                       CASE WHEN C.ADJ_DT < :evalExcl AND C.TOTAL_PAY_AMT IS NOT NULL AND C.CLM_STAT_CD IN ('20', '40')
                            THEN C.TOTAL_PAY_AMT ELSE C.TOTAL_CLAIM_AMT END AS RSV
                  FROM TB_CLM C JOIN TB_CTR_LGD L ON L.CONTRACT_NO = C.CONTRACT_NO
                 WHERE L.INSR_CLS_CD = :cls AND C.ACDT_DT >= :from AND C.ACDT_DT <= :eval AND C.RPT_DT < :evalExcl
                   AND (C.DECIDE_DT IS NULL OR C.DECIDE_DT >= :evalExcl)
                """, params, rs -> {
            int i = index(rs.getObject("ACDT_DT", LocalDate.class), type) - periods.get(0).index();
            caseReserve[i] = caseReserve[i].add(rs.getBigDecimal("RSV"));
        });

        // 사고 기간별 경과보험료 (손해율 분석과 같은 정의)
        List<ChainLadder.Period> input = new ArrayList<>();
        List<IbnrResponse.TriangleRow> triangle = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AccidentPeriod p = periods.get(i);
            BigDecimal earned = jdbc.queryForObject("SELECT COALESCE(SUM(PREM * EXPOSED_DAYS / NULLIF(TERM_DAYS, 0)), 0) FROM ("
                            + LossRatioService.EXPOSURE + ") E WHERE EXPOSED_DAYS > 0 AND INSR_CLS_CD = :cls",
                    new MapSqlParameterSource("fromDate", p.from()).addValue("toExcl", (p.to().isAfter(eval) ? eval : p.to()).plusDays(1))
                            .addValue("cls", insrClsCd), BigDecimal.class);
            BigDecimal[] cumulative = new BigDecimal[incremental[i].length];
            BigDecimal running = BigDecimal.ZERO;
            for (int k = 0; k < cumulative.length; k++) {
                running = running.add(incremental[i][k]);
                cumulative[k] = running;
            }
            input.add(new ChainLadder.Period(p.label(), cumulative, earned == null ? BigDecimal.ZERO : earned.setScale(0, RoundingMode.HALF_UP),
                    caseReserve[i]));
            triangle.add(IbnrResponse.TriangleRow.builder().period(p.label()).cells(List.of(cumulative)).build());
        }

        ChainLadder.Result result = ChainLadder.run(input, tail, elr);
        List<String> warnings = new ArrayList<>(result.warnings());
        if (!eval.equals(periodEnd(eval, type))) {
            warnings.add("평가 기준일이 사고 기간 말일이 아니라 마지막 대각선이 덜 찬 기간입니다. 진전계수가 작게 나올 수 있어 기간 말일로 평가하는 것이 좋습니다.");
        }
        long payments = input.stream().filter(p -> p.cumulative().length > 0 && p.cumulative()[p.cumulative().length - 1].signum() > 0).count();
        if (payments < count / 2) {
            warnings.add("지급이 있는 사고 기간이 적어(" + payments + "/" + count + ") 진전계수의 신뢰도가 낮습니다.");
        }
        warnings.add("지급 기준 삼각형입니다. 접수 지연(미보고)과 지급 지연을 함께 추정하며, 추세·대형 사고·할인·위험조정은 반영하지 않습니다.");

        List<IbnrResponse.Period> rows = new ArrayList<>();
        BigDecimal ep = BigDecimal.ZERO, paid = BigDecimal.ZERO, cr = BigDecimal.ZERO, cl = BigDecimal.ZERO, bf = BigDecimal.ZERO, sel = BigDecimal.ZERO;
        boolean bfAvailable = result.elr() != null;
        for (int i = 0; i < count; i++) {
            ChainLadder.PeriodResult r = result.periods().get(i);
            AccidentPeriod p = periods.get(i);
            rows.add(IbnrResponse.Period.builder()
                    .period(p.label()).periodFrom(p.from()).periodTo(p.to()).age(r.age())
                    .earnedPremium(r.earnedPremium()).paid(r.paid()).caseReserve(r.caseReserve())
                    .cdf(r.cdf()).developed(r.developed())
                    .clUltimate(r.clUltimate()).clIbnr(r.clIbnr()).bfUltimate(r.bfUltimate()).bfIbnr(r.bfIbnr())
                    .method(r.method()).ibnr(r.selectedIbnr())
                    .ultimateLossRatio(r.earnedPremium().signum() == 0 ? null
                            : r.selectedUltimate().divide(r.earnedPremium(), 4, RoundingMode.HALF_UP))
                    .build());
            ep = ep.add(r.earnedPremium());
            paid = paid.add(r.paid());
            cr = cr.add(r.caseReserve());
            cl = cl.add(r.clIbnr().max(BigDecimal.ZERO));
            if (bfAvailable) {
                bf = bf.add(r.bfIbnr().max(BigDecimal.ZERO));
            }
            sel = sel.add(r.selectedIbnr());
        }
        log.info("IBNR 산출: 종목={}, 기준일={}, {} {}개, 개별추산액={}, IBNR={}", insrClsCd, eval, type, count, cr, sel);
        return IbnrResponse.builder()
                .insrClsCd(insrClsCd)
                .evaluationDate(eval)
                .periodType(type)
                .tailFactor(tail)
                .expectedLossRatio(result.elr())
                .elrEstimated(result.elrEstimated())
                .matureThreshold(ChainLadder.MATURE_THRESHOLD)
                .factors(result.factors().stream().map(f -> IbnrResponse.Factor.builder()
                        .age(f.age()).factor(f.factor()).cdf(f.cdf()).dataPoints(f.dataPoints()).build()).toList())
                .triangle(triangle)
                .periods(rows)
                .total(IbnrResponse.Total.builder().earnedPremium(ep).paid(paid).caseReserve(cr).clIbnr(cl)
                        .bfIbnr(bfAvailable ? bf : null).ibnr(sel).reserve(cr.add(sel)).build())
                .warnings(warnings)
                .build();
    }

    /** 기준일이 든 기간을 마지막으로 하는 사고 기간 count 개 (오래된 것부터) */
    static List<AccidentPeriod> periods(LocalDate eval, String type, int count) {
        List<AccidentPeriod> list = new ArrayList<>();
        LocalDate start = periodStart(eval, type);
        for (int i = count - 1; i >= 0; i--) {
            LocalDate s = QUARTER.equals(type) ? start.minusMonths(3L * i) : start.minusYears(i);
            LocalDate e = periodEnd(s, type);
            String label = QUARTER.equals(type) ? s.getYear() + "Q" + s.get(IsoFields.QUARTER_OF_YEAR) : String.valueOf(s.getYear());
            list.add(new AccidentPeriod(label, s, e, index(s, type)));
        }
        return list;
    }

    /** 기간 번호 (연속 정수). 같은 기간이면 같은 값 */
    static int index(LocalDate date, String type) {
        return QUARTER.equals(type) ? date.getYear() * 4 + (date.getMonthValue() - 1) / 3 : date.getYear();
    }

    static LocalDate periodStart(LocalDate date, String type) {
        return QUARTER.equals(type) ? LocalDate.of(date.getYear(), ((date.getMonthValue() - 1) / 3) * 3 + 1, 1) : LocalDate.of(date.getYear(), 1, 1);
    }

    static LocalDate periodEnd(LocalDate date, String type) {
        LocalDate start = periodStart(date, type);
        return QUARTER.equals(type) ? start.plusMonths(3).minusDays(1) : start.plusYears(1).minusDays(1);
    }

    /** 오늘이 기간 말일이면 오늘, 아니면 직전 기간 말일 */
    static LocalDate previousPeriodEnd(LocalDate today, String type) {
        return today.equals(periodEnd(today, type)) ? today : periodStart(today, type).minusDays(1);
    }

    /** 분기 말일인지 (결산 기준일 확인용) */
    public static boolean isQuarterEnd(LocalDate date) {
        return date.equals(periodEnd(date, QUARTER));
    }
}
