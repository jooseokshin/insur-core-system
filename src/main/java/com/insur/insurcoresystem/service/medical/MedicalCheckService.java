package com.insur.insurcoresystem.service.medical;

import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.domain.contract.Coverage;
import com.insur.insurcoresystem.domain.contract.CoverageRepository;
import com.insur.insurcoresystem.domain.product.Product;
import com.insur.insurcoresystem.domain.product.ProductRepository;
import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.TraceNumbers;
import com.insur.insurcoresystem.ext.kcis.KcisClient;
import com.insur.insurcoresystem.ext.kcis.KcisLayouts;
import com.insur.insurcoresystem.ext.outbox.OutboxService;
import com.insur.insurcoresystem.service.medical.dto.MedicalCheckResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실손의료보험 청약·계약의 한국신용정보원 연계.
 * <ul>
 *     <li><b>청약 시 중복가입 조회</b>: 실손은 여러 개 들어도 비례보상이라 더 받지 못하므로, 판매할 때 다른 회사 실손이 있는지 조회해
 *         설명해야 한다. 조회는 개인신용정보 조회라서 동의를 받은 청약만 한다. 기관 장애로 조회하지 못해도 청약은 받되(80),
 *         다시 조회해서 끝나기 전에는 승인하지 않는다(E-MED-003).</li>
 *     <li><b>계약정보 집중</b>: 승인(성립)과 해지를 신용정보원에 알린다(R001). 다른 회사가 중복가입 조회·비례보상을 할 수 있게 하는
 *         업계 공동 정보이므로, 우리가 보내지 않으면 다른 회사의 계산이 틀린다. 대기열에 쌓아 보낸다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalCheckService {

    public static final String CHECK_DONE = "10";
    public static final String CHECK_FAILED = "80";

    private final NamedParameterJdbcTemplate jdbc;
    private final KcisClient kcis;
    private final TraceNumbers traceNumbers;
    private final OutboxService outbox;
    private final ContractRepository contractRepository;
    private final CoverageRepository coverageRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    /**
     * 청약 접수 때 동의를 기록하고 중복가입을 조회한다. 기관 장애면 조회 실패(80)로 남기고 청약은 계속한다.
     *
     * @param consentDt 동의 일시 (갱신은 원 계약의 동의 일시)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkAtApplication(Contract contract, LocalDateTime consentDt, String empId) {
        jdbc.update("""
                INSERT INTO TB_CTR_MED_CHECK (CONTRACT_NO, CONSENT_DT, CHECK_STAT_CD) VALUES (:contractNo, :consentDt, '80')
                """, new MapSqlParameterSource("contractNo", contract.getContractNo()).addValue("consentDt", consentDt));
        query(contract, empId);
    }

    /** 원 계약의 동의 일시 (갱신에서 잇는다). 원 계약에 동의 기록이 없으면 빈 값 */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> consentOf(String contractNo) {
        return jdbc.query("SELECT CONSENT_DT FROM TB_CTR_MED_CHECK WHERE CONTRACT_NO = :contractNo",
                new MapSqlParameterSource("contractNo", contractNo), (rs, i) -> rs.getObject(1, LocalDateTime.class)).stream().findFirst();
    }

    /**
     * 중복가입을 다시 조회한다 (조회 실패 건을 승인하려면 먼저 해야 한다). 청약(10) 상태에서만 한다.
     *
     * @throws BusinessException 계약 없음(E-CTR-010, 404), 실손이 아님·조회 기록 없음(E-MED-007), 청약 상태가 아님(E-MED-008, 409),
     *                           신용정보원 장애(E-MED-004, 503 - 실패 기록은 남는다)
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public MedicalCheckResponse recheck(String contractNo, String empId) {
        Contract contract = contractRepository.findForUpdateByContractNo(contractNo == null ? "" : contractNo)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "E-CTR-010", "해당 계약이 존재하지 않습니다."));
        if (!MedicalClaimService.isMedical(contract) || find(contractNo).isEmpty()) {
            throw new BusinessException("E-MED-007", "신용정보 조회 동의를 받은 실손의료보험 계약이 아닙니다.");
        }
        if (ContractStatus.fromCode(contract.getCtrStatCd()) != ContractStatus.APPLIED) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-MED-008", "중복가입 조회는 청약(10) 상태에서만 다시 할 수 있습니다.");
        }
        List<KcisClient.OtherContract> others = query(contract, empId);
        MedicalCheckResponse saved = find(contractNo).orElseThrow();
        if (others == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "E-MED-004",
                    "신용정보원 연계 장애로 중복가입을 조회하지 못했습니다. 잠시 후 다시 조회해 주세요. (" + saved.getLastError() + ")");
        }
        return MedicalCheckResponse.builder()
                .contractNo(saved.getContractNo()).consentDt(saved.getConsentDt()).checkStatCd(saved.getCheckStatCd())
                .otherCount(saved.getOtherCount()).checkedDt(saved.getCheckedDt()).checkEmpId(saved.getCheckEmpId())
                .otherContracts(others.stream().map(o -> MedicalCheckResponse.OtherContract.builder()
                        .insurerCd(o.insurerCode()).policyNo(o.policyNo()).kindCd(o.kind()).startDate(o.startDate())
                        .endDate(o.endDate()).covered(o.covered()).nonCovered(o.nonCovered()).build()).toList())
                .build();
    }

    /**
     * 승인 전에 중복가입 조회가 끝났는지 확인한다.
     *
     * @throws BusinessException 조회 기록 없음·조회 실패(E-MED-003, 409)
     */
    public void requireChecked(Contract contract) {
        Optional<MedicalCheckResponse> check = find(contract.getContractNo());
        if (check.isEmpty() || !CHECK_DONE.equals(check.get().getCheckStatCd())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-MED-003",
                    "신용정보원 중복가입 조회가 끝나지 않아 승인할 수 없습니다. 중복가입을 다시 조회해 주세요.");
        }
    }

    /** 조회 기록 */
    @Transactional(readOnly = true)
    public Optional<MedicalCheckResponse> find(String contractNo) {
        return jdbc.query("SELECT * FROM TB_CTR_MED_CHECK WHERE CONTRACT_NO = :contractNo",
                new MapSqlParameterSource("contractNo", contractNo), (rs, i) -> MedicalCheckResponse.builder()
                        .contractNo(rs.getString("CONTRACT_NO"))
                        .consentDt(rs.getObject("CONSENT_DT", LocalDateTime.class))
                        .checkStatCd(rs.getString("CHECK_STAT_CD"))
                        .otherCount((Integer) rs.getObject("OTHER_COUNT"))
                        .checkedDt(rs.getObject("CHECKED_DT", LocalDateTime.class))
                        .checkEmpId(rs.getString("CHECK_EMP_ID"))
                        .lastError(rs.getString("LAST_ERROR"))
                        .build()).stream().findFirst();
    }

    /**
     * 계약정보 집중(R001)을 대기열에 쌓는다. 성립(승인)은 신규, 해지는 해지(보장 종료 = 해지일).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reportContract(Contract contract, boolean newContract) {
        boolean nonCovered = coverageRepository.findByContractContractNo(contract.getContractNo()).stream()
                .map(Coverage::getCvrgCd).anyMatch(MedicalClaimCalculator.NON_COVERED::equals);
        String productName = contract.getPrdCd() == null ? "실손의료보험"
                : productRepository.findById(contract.getPrdCd()).map(Product::getPrdNm).orElse("실손의료보험");
        LocalDate end = !newContract && contract.getTermDate() != null ? contract.getTermDate() : contract.getEndDate();
        outbox.enqueue(KcisLayouts.AGENCY, KcisLayouts.R001, contract.getContractNo() + (newContract ? "-N" : "-T"),
                traceNo -> kcis.buildContractReport(traceNo, newContract, contract.getContractNo(), contract.getInsuredId(),
                        contract.getStartDate(), end, nonCovered, productName));
    }

    /** 조회해서 결과를 기록한다. 장애면 null */
    private List<KcisClient.OtherContract> query(Contract contract, String empId) {
        LocalDateTime now = LocalDateTime.now(clock);
        MapSqlParameterSource params = new MapSqlParameterSource("contractNo", contract.getContractNo())
                .addValue("now", now).addValue("empId", normalizeEmpId(empId));
        try {
            List<KcisClient.OtherContract> others = kcis.findMedicalContracts(contract.getInsuredId(), contract.getStartDate(),
                    traceNumbers.next());
            jdbc.update("""
                    UPDATE TB_CTR_MED_CHECK SET CHECK_STAT_CD = '10', OTHER_COUNT = :count, CHECKED_DT = :now, CHECK_EMP_ID = :empId,
                           LAST_ERROR = NULL WHERE CONTRACT_NO = :contractNo""", params.addValue("count", others.size()));
            log.info("실손 중복가입 조회: 증권번호={}, 다른 보험사 실손 {}건", contract.getContractNo(), others.size());
            return others;
        } catch (ExternalAgencyException e) {
            String reason = e.getMessage() == null ? "신용정보원 연계 실패" : e.getMessage();
            jdbc.update("""
                    UPDATE TB_CTR_MED_CHECK SET CHECK_STAT_CD = '80', OTHER_COUNT = NULL, CHECKED_DT = :now, CHECK_EMP_ID = :empId,
                           LAST_ERROR = :error WHERE CONTRACT_NO = :contractNo""",
                    params.addValue("error", reason.length() > 200 ? reason.substring(0, 200) : reason));
            log.warn("실손 중복가입 조회 실패(승인 보류): 증권번호={}, 사유={}", contract.getContractNo(), reason);
            return null;
        }
    }
}
