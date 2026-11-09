package com.insur.insurcoresystem.service.medical;

import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidAmount;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.claim.Claim;
import com.insur.insurcoresystem.domain.claim.ClaimCoverage;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.kcis.KcisClient;
import com.insur.insurcoresystem.ext.kcis.KcisLayouts;
import com.insur.insurcoresystem.ext.outbox.OutboxService;
import com.insur.insurcoresystem.ext.TraceNumbers;
import com.insur.insurcoresystem.service.claim.dto.ClaimRequests;
import com.insur.insurcoresystem.service.claim.dto.ClaimResponses;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 실손의료보험(05) 보상: 진료 내역 접수, 손해사정 자동 계산(4세대 공제·통원 1회 한도·연간 한도·비례보상), 지급정보 집중.
 * 보상의 상태·이력·멱등성은 {@code ClaimService} 가 그대로 맡고, 이 클래스는 실손에만 있는 부분만 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalClaimService {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_KIDI = "KIDI";
    private static final int MAX_ITEMS = 50;
    private static final Set<String> CARE_TYPES = Set.of("IN", "OUT");
    private static final Set<String> FACILITY_TYPES = Set.of("CLINIC", "HOSPITAL", "GENERAL", "TERTIARY");

    private final NamedParameterJdbcTemplate jdbc;
    private final KcisClient kcis;
    private final TraceNumbers traceNumbers;
    private final OutboxService outbox;
    private final Clock clock;

    /** 실손 계약인지 */
    public static boolean isMedical(Contract contract) {
        return Contract.INSR_CLS_MEDICAL.equals(contract.getInsrClsCd());
    }

    /**
     * 진료 내역 입력을 검사한다.
     *
     * @throws BusinessException 진료 내역 누락·형식 오류(E-MED-005), 금액 오류(E-CLM-005)
     */
    public static void validateItems(List<ClaimRequests.MedicalItem> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS || items.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException("E-MED-005", "실손 사고는 진료 내역을 1~" + MAX_ITEMS + "건 입력해야 합니다.");
        }
        for (ClaimRequests.MedicalItem item : items) {
            if (item.getTreatDt() == null || !CARE_TYPES.contains(String.valueOf(item.getCareType()))
                    || !FACILITY_TYPES.contains(String.valueOf(item.getFacilityType()))) {
                throw new BusinessException("E-MED-005", "진료일, 진료 구분(IN/OUT), 요양기관 종별(CLINIC/HOSPITAL/GENERAL/TERTIARY)을 입력해야 합니다.");
            }
            if (!StringUtils.hasText(item.getFacilityNm()) || item.getFacilityNm().trim().length() > 100) {
                throw new BusinessException("E-MED-005", "의료기관명은 1~100자로 입력해야 합니다.");
            }
            if (isInvalidAmount(item.getCoveredAmt()) || isInvalidAmount(item.getNonCoveredAmt())
                    || item.getCoveredAmt().add(item.getNonCoveredAmt()).signum() == 0) {
                throw new BusinessException("E-CLM-005", "진료비는 급여·비급여 각각 0 이상 15자리 이하 정수이고 합계가 0보다 커야 합니다.");
            }
        }
    }

    /** 가장 이른 진료일 (실손 사고의 사고일) */
    public static LocalDate firstTreatDate(List<ClaimRequests.MedicalItem> items) {
        return items.stream().map(ClaimRequests.MedicalItem::getTreatDt).min(Comparator.naturalOrder()).orElseThrow();
    }

    /**
     * 진료 내역에서 청구 담보를 만든다. 급여 본인부담금 합계는 ME001, 비급여 합계는 ME002(가입한 경우만, 0보다 클 때만).
     * 비급여 특약이 없으면 비급여 금액은 기록만 하고 청구하지 않는다.
     *
     * @throws BusinessException 청구할 담보가 없음(E-CLM-004)
     */
    public static List<ClaimCoverage> claimCoverages(List<ClaimRequests.MedicalItem> items, Set<String> held) {
        BigDecimal covered = items.stream().map(ClaimRequests.MedicalItem::getCoveredAmt).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal nonCovered = items.stream().map(ClaimRequests.MedicalItem::getNonCoveredAmt).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ClaimCoverage> list = new ArrayList<>();
        if (held.contains(MedicalClaimCalculator.COVERED) && covered.signum() > 0) {
            list.add(new ClaimCoverage(MedicalClaimCalculator.COVERED, covered));
        }
        if (held.contains(MedicalClaimCalculator.NON_COVERED) && nonCovered.signum() > 0) {
            list.add(new ClaimCoverage(MedicalClaimCalculator.NON_COVERED, nonCovered));
        }
        if (list.isEmpty()) {
            throw new BusinessException("E-CLM-004", "청구할 수 있는 금액이 없습니다. (급여 본인부담금이 없고 비급여 특약에 가입하지 않음)");
        }
        return list;
    }

    /** 접수한 진료 내역을 저장한다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveItems(String clmNo, List<ClaimRequests.MedicalItem> items, String sourceCd) {
        List<ClaimRequests.MedicalItem> sorted = items.stream()
                .sorted(Comparator.comparing(ClaimRequests.MedicalItem::getTreatDt)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            ClaimRequests.MedicalItem item = sorted.get(i);
            jdbc.update("""
                    INSERT INTO TB_CLM_MED_ITEM (CLM_NO, SEQ, TREAT_DT, CARE_TYPE, FACILITY_TYPE, FACILITY_NM, COVERED_AMT, NON_COVERED_AMT, SOURCE_CD)
                    VALUES (:clmNo, :seq, :treatDt, :careType, :facilityType, :facilityNm, :covered, :nonCovered, :source)
                    """, new MapSqlParameterSource().addValue("clmNo", clmNo).addValue("seq", i + 1)
                    .addValue("treatDt", item.getTreatDt()).addValue("careType", item.getCareType())
                    .addValue("facilityType", item.getFacilityType()).addValue("facilityNm", item.getFacilityNm().trim())
                    .addValue("covered", item.getCoveredAmt()).addValue("nonCovered", item.getNonCoveredAmt())
                    .addValue("source", sourceCd));
        }
    }

    /**
     * 실손 손해사정. 진료별 공제·지급을 계산하고, 신용정보원에서 다른 보험사 실손을 조회해 비례보상하고, 연간 한도(남은 가입금액)를 적용한다.
     * 계산 결과는 진료 내역(TB_CLM_MED_ITEM)과 비례보상 내역(TB_CLM_MED_SHARE)에 남기고, 담보별 결과를 돌려준다.
     *
     * @param adjustments 진료별 인정액 (없으면 청구액 그대로)
     * @param limits      담보별 남은 가입금액 (연간 한도)
     * @throws BusinessException 인정액 오류(E-MED-006), 신용정보원 장애로 비례보상 확인 불가(E-MED-004, 503)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, ClaimCoverage.Assessment> assess(Claim claim, Contract contract, Set<String> held,
                                                        List<ClaimRequests.MedicalAdjust> adjustments, Map<String, BigDecimal> limits) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT SEQ, CARE_TYPE, FACILITY_TYPE, COVERED_AMT, NON_COVERED_AMT FROM TB_CLM_MED_ITEM WHERE CLM_NO = :clmNo ORDER BY SEQ",
                new MapSqlParameterSource("clmNo", claim.getClmNo()));
        Map<Integer, ClaimRequests.MedicalAdjust> adjust = new HashMap<>();
        for (ClaimRequests.MedicalAdjust a : adjustments == null ? List.<ClaimRequests.MedicalAdjust>of() : adjustments) {
            if (a == null || a.getSeq() == null || adjust.put(a.getSeq(), a) != null) {
                throw new BusinessException("E-MED-006", "진료 인정액의 순번이 비었거나 중복되었습니다.");
            }
        }
        List<MedicalClaimCalculator.Item> items = new ArrayList<>();
        Set<Integer> seqs = new HashSet<>();
        for (Map<String, Object> r : rows) {
            int seq = ((Number) r.get("seq")).intValue();
            seqs.add(seq);
            BigDecimal covered = (BigDecimal) r.get("covered_amt");
            BigDecimal nonCovered = (BigDecimal) r.get("non_covered_amt");
            ClaimRequests.MedicalAdjust a = adjust.get(seq);
            if (a != null) {
                covered = recognized(a.getCoveredAmt(), covered, seq);
                nonCovered = recognized(a.getNonCoveredAmt(), nonCovered, seq);
            }
            items.add(new MedicalClaimCalculator.Item(seq, (String) r.get("care_type"), (String) r.get("facility_type"), covered, nonCovered));
        }
        if (!seqs.containsAll(adjust.keySet())) {
            throw new BusinessException("E-MED-006", "없는 진료 순번입니다: " + adjust.keySet().stream().filter(s -> !seqs.contains(s)).toList());
        }

        MedicalClaimCalculator.Result result = MedicalClaimCalculator.calculate(items, rules(contract.getPrdCd()), held);
        List<KcisClient.OtherContract> others = otherContracts(contract, claim.getAcdtDt());

        jdbc.update("DELETE FROM TB_CLM_MED_SHARE WHERE CLM_NO = :clmNo", new MapSqlParameterSource("clmNo", claim.getClmNo()));
        for (int i = 0; i < items.size(); i++) {
            MedicalClaimCalculator.Item item = items.get(i);
            MedicalClaimCalculator.ItemResult r = result.items().get(i);
            jdbc.update("""
                    UPDATE TB_CLM_MED_ITEM SET COVERED_ASSESSED = :ca, NON_COVERED_ASSESSED = :na, COVERED_DEDUCT = :cd, COVERED_PAY = :cp,
                           NON_COVERED_DEDUCT = :nd, NON_COVERED_PAY = :np
                     WHERE CLM_NO = :clmNo AND SEQ = :seq""", new MapSqlParameterSource().addValue("clmNo", claim.getClmNo())
                    .addValue("seq", item.seq()).addValue("ca", item.coveredAmt()).addValue("na", item.nonCoveredAmt())
                    .addValue("cd", r.coveredDeduct()).addValue("cp", r.coveredPay())
                    .addValue("nd", r.nonCoveredDeduct()).addValue("np", r.nonCoveredPay()));
        }

        Map<String, ClaimCoverage.Assessment> assessments = new HashMap<>();
        for (ClaimCoverage claimed : claim.getItems()) {
            String code = claimed.getCvrgCd();
            MedicalClaimCalculator.CoverageTotal total = result.coverages().get(code);
            List<KcisClient.OtherContract> sharing = others.stream()
                    .filter(o -> MedicalClaimCalculator.COVERED.equals(code) ? o.covered() : o.nonCovered()).toList();
            BigDecimal liability = total.liability();
            // 다른 회사 책임액은 우리와 같은 규칙이라고 본다 (MedicalClaimCalculator 설명 참고)
            List<BigDecimal> otherLiabilities = sharing.stream().map(o -> liability).toList();
            BigDecimal share = MedicalClaimCalculator.proportionalShare(liability, otherLiabilities);
            if (!sharing.isEmpty()) {
                insertShare(claim.getClmNo(), code, kcis.orgCode(), contract.getContractNo(), liability, share);
                for (KcisClient.OtherContract o : sharing) {
                    insertShare(claim.getClmNo(), code, o.insurerCode(), o.policyNo(), liability,
                            MedicalClaimCalculator.proportionalShare(liability, otherLiabilities));
                }
            }
            BigDecimal pay = share.min(limits.getOrDefault(code, BigDecimal.ZERO).max(BigDecimal.ZERO));
            assessments.put(code, new ClaimCoverage.Assessment(total.assessed(), total.assessed().subtract(liability), pay));
        }
        log.info("실손 손해사정: 사고번호={}, 다른 보험사 실손 {}건, 결과={}", claim.getClmNo(), others.size(),
                assessments.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().payAmt()).toList());
        return assessments;
    }

    /**
     * 보험금 지급정보 집중(R002)을 대기열에 쌓는다. 신용정보원은 이 정보로 보험사기 분석과 중복 청구 확인을 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reportPayment(Claim claim, Contract contract) {
        BigDecimal covered = BigDecimal.ZERO;
        BigDecimal nonCovered = BigDecimal.ZERO;
        for (ClaimCoverage item : claim.getItems()) {
            if (MedicalClaimCalculator.COVERED.equals(item.getCvrgCd())) {
                covered = item.getPayAmt();
            } else if (MedicalClaimCalculator.NON_COVERED.equals(item.getCvrgCd())) {
                nonCovered = item.getPayAmt();
            }
        }
        long coveredPaid = covered.longValueExact();
        long nonCoveredPaid = nonCovered.longValueExact();
        LocalDate paidDate = LocalDate.now(clock);
        outbox.enqueue(KcisLayouts.AGENCY, KcisLayouts.R002, claim.getClmNo(), traceNo -> kcis.buildPaymentReport(traceNo,
                claim.getClmNo(), contract.getContractNo(), contract.getInsuredId(), claim.getAcdtDt(), coveredPaid, nonCoveredPaid, paidDate));
    }

    /** 사고 상세의 진료 내역 */
    @Transactional(readOnly = true)
    public List<ClaimResponses.MedicalItem> items(String clmNo) {
        return jdbc.query("SELECT * FROM TB_CLM_MED_ITEM WHERE CLM_NO = :clmNo ORDER BY SEQ", new MapSqlParameterSource("clmNo", clmNo),
                (rs, i) -> ClaimResponses.MedicalItem.builder()
                        .seq(rs.getInt("SEQ"))
                        .treatDt(rs.getObject("TREAT_DT", LocalDate.class))
                        .careType(rs.getString("CARE_TYPE"))
                        .facilityType(rs.getString("FACILITY_TYPE"))
                        .facilityNm(rs.getString("FACILITY_NM"))
                        .coveredAmt(rs.getBigDecimal("COVERED_AMT"))
                        .nonCoveredAmt(rs.getBigDecimal("NON_COVERED_AMT"))
                        .coveredAssessed(rs.getBigDecimal("COVERED_ASSESSED"))
                        .nonCoveredAssessed(rs.getBigDecimal("NON_COVERED_ASSESSED"))
                        .coveredDeduct(rs.getBigDecimal("COVERED_DEDUCT"))
                        .coveredPay(rs.getBigDecimal("COVERED_PAY"))
                        .nonCoveredDeduct(rs.getBigDecimal("NON_COVERED_DEDUCT"))
                        .nonCoveredPay(rs.getBigDecimal("NON_COVERED_PAY"))
                        .sourceCd(rs.getString("SOURCE_CD"))
                        .build());
    }

    /** 사고 상세의 비례보상 내역 (우리 회사 행이 먼저) */
    @Transactional(readOnly = true)
    public List<ClaimResponses.MedicalShare> shares(String clmNo) {
        String own = kcis.orgCode();
        return jdbc.query("""
                SELECT CVRG_CD, INSURER_CD, POLICY_NO, LIABILITY_AMT, SHARE_AMT FROM TB_CLM_MED_SHARE WHERE CLM_NO = :clmNo
                 ORDER BY CVRG_CD, CASE WHEN INSURER_CD = :own THEN 0 ELSE 1 END, INSURER_CD, POLICY_NO""",
                new MapSqlParameterSource("clmNo", clmNo).addValue("own", own),
                (rs, i) -> ClaimResponses.MedicalShare.builder()
                        .cvrgCd(rs.getString("CVRG_CD"))
                        .insurerCd(rs.getString("INSURER_CD"))
                        .policyNo(rs.getString("POLICY_NO"))
                        .own(own.equals(rs.getString("INSURER_CD")))
                        .liabilityAmt(rs.getBigDecimal("LIABILITY_AMT"))
                        .shareAmt(rs.getBigDecimal("SHARE_AMT"))
                        .build());
    }

    private List<KcisClient.OtherContract> otherContracts(Contract contract, LocalDate accidentDate) {
        try {
            return kcis.findMedicalContracts(contract.getInsuredId(), accidentDate, traceNumbers.next()).stream()
                    .filter(o -> o.covers(accidentDate)).toList();
        } catch (ExternalAgencyException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "E-MED-004",
                    "신용정보원에서 다른 보험사 실손 가입 여부를 확인하지 못해 비례보상을 계산할 수 없습니다. 잠시 후 다시 손해사정해 주세요.");
        }
    }

    private List<MedicalClaimCalculator.Rule> rules(String prdCd) {
        return jdbc.query("SELECT * FROM TB_PRD_MED_RULE WHERE PRD_CD = :prdCd", new MapSqlParameterSource("prdCd", prdCd),
                (rs, i) -> new MedicalClaimCalculator.Rule(rs.getString("CVRG_CD"), rs.getString("CARE_TYPE"),
                        rs.getString("FACILITY_GRADE"), rs.getBigDecimal("COPAY_RATE"), rs.getBigDecimal("MIN_DEDUCT_AMT"),
                        rs.getBigDecimal("PER_VISIT_LIMIT")));
    }

    private void insertShare(String clmNo, String cvrgCd, String insurerCd, String policyNo, BigDecimal liability, BigDecimal share) {
        jdbc.update("""
                INSERT INTO TB_CLM_MED_SHARE (CLM_NO, CVRG_CD, INSURER_CD, POLICY_NO, LIABILITY_AMT, SHARE_AMT)
                VALUES (:clmNo, :cvrgCd, :insurer, :policyNo, :liability, :share)
                """, new MapSqlParameterSource().addValue("clmNo", clmNo).addValue("cvrgCd", cvrgCd).addValue("insurer", insurerCd)
                .addValue("policyNo", policyNo).addValue("liability", liability).addValue("share", share));
    }

    private static BigDecimal recognized(BigDecimal value, BigDecimal claimed, int seq) {
        if (value == null) {
            return claimed;
        }
        if (isInvalidAmount(value) || value.compareTo(claimed) > 0) {
            throw new BusinessException("E-MED-006", "진료 " + seq + "의 인정액은 0 이상 청구액(" + claimed.toPlainString() + "원) 이하 정수여야 합니다.");
        }
        return value;
    }
}
