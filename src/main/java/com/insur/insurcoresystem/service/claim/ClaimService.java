package com.insur.insurcoresystem.service.claim;

import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidAmount;
import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidCode;
import static com.insur.insurcoresystem.service.contract.ContractSupport.isConstraintViolation;
import static com.insur.insurcoresystem.service.contract.ContractSupport.isReadable;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.pageRequest;
import static com.insur.insurcoresystem.service.contract.ContractSupport.validateGlobalTxId;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.domain.claim.Claim;
import com.insur.insurcoresystem.domain.claim.ClaimCoverage;
import com.insur.insurcoresystem.domain.claim.ClaimHistory;
import com.insur.insurcoresystem.domain.claim.ClaimHistoryRepository;
import com.insur.insurcoresystem.domain.claim.ClaimRepository;
import com.insur.insurcoresystem.domain.claim.ClaimStatus;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.domain.contract.Coverage;
import com.insur.insurcoresystem.domain.contract.CoverageRepository;
import com.insur.insurcoresystem.domain.product.Product;
import com.insur.insurcoresystem.domain.product.ProductCoverage;
import com.insur.insurcoresystem.domain.product.ProductRepository;
import com.insur.insurcoresystem.service.claim.dto.ClaimRequests;
import com.insur.insurcoresystem.service.claim.dto.ClaimResponses;
import com.insur.insurcoresystem.service.contract.ContractSupport;
import com.insur.insurcoresystem.service.sequence.ContractNumberGenerator;
import com.insur.insurcoresystem.service.sequence.ProductType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 보상 업무: 사고 접수, 손해사정, 종결(지급·면책·접수 취소), 조회.
 * <ul>
 *     <li>접수: 유효(20) 또는 해지(30) 계약의 보장 기간(보험시기 ~ min(보험종기, 해지일), 끝 날짜 제외) 안에 난 사고만 받는다.
 *         청구 담보는 그 계약에 가입한 담보여야 한다. 설계사는 본인이 접수한 계약에만 접수할 수 있다.</li>
 *     <li>손해사정: 지급액 = min(max(사정 손해액 − 자기부담금, 0), 남은 가입금액). 자기부담금은 상품이 정하고(상품 없는 계약은 0),
 *         남은 가입금액은 그 계약·담보에 다른 사고로 이미 지급한 금액을 뺀 것이다. 지급 대기 중에는 다시 사정할 수 있다.</li>
 *     <li>지급: 지급액 합계가 {@value #HIGH_VALUE_THRESHOLD}원을 넘으면 관리자(ADMIN)만 지급할 수 있다. 지급 직전에 남은 가입금액을 다시 확인한다.</li>
 *     <li>같은 계약의 사정·지급은 계약 행을 잠가 차례로 처리해, 두 사고가 같은 한도를 동시에 쓰지 못하게 한다.</li>
 *     <li>접수는 거래 UUID 로, 사정·종결은 (사고번호, 거래 UUID) 로 한 번만 처리된다. 재전송은 다시 처리하지 않고 현재 사고를 돌려준다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimService {

    /** 이 금액을 넘는 지급은 관리자 승인이 필요하다 */
    public static final long HIGH_VALUE_THRESHOLD = 10_000_000L;
    private static final BigDecimal THRESHOLD = BigDecimal.valueOf(HIGH_VALUE_THRESHOLD);

    /** 접수 거래 UUID 유니크 제약명 (V21) */
    private static final String GLOBAL_TX_ID_CONSTRAINT = "uk_clm_global_tx_id";

    private final ClaimRepository claimRepository;
    private final ClaimHistoryRepository historyRepository;
    private final ContractRepository contractRepository;
    private final CoverageRepository coverageRepository;
    private final ProductRepository productRepository;
    private final ContractNumberGenerator numberGenerator;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 사고를 접수한다.
     *
     * @param ownerEmpId 접수를 접수자 본인의 계약으로 제한할 사번 (AGENT 만 가진 사용자). null 이면 제한 없음
     * @throws BusinessException 입력 오류(E-CLM-001, E-CTR-008), 계약 없음·권한 밖(E-CTR-010, 404), 보상 대상이 아닌 계약 상태(E-CLM-002, 409),
     *                           보장 기간 밖이거나 미래 사고일(E-CLM-003), 계약에 없는 담보·중복(E-CLM-004), 금액 오류(E-CLM-005),
     *                           같은 거래 UUID 로 다른 내용(E-CLM-009, 409), 동시 접수 충돌(E-CLM-014, 409)
     */
    @Transactional
    public ClaimResponses.Detail report(String ownerEmpId, String globalTxId, String empId, ClaimRequests.Report request) {
        validateGlobalTxId(globalTxId);
        if (isInvalidCode(request.getContractNo(), 30) || request.getAcdtDt() == null) {
            throw new BusinessException("E-CLM-001", "증권번호와 사고일을 입력해야 합니다.");
        }
        if (!StringUtils.hasText(request.getAcdtDesc()) || request.getAcdtDesc().length() > 500) {
            throw new BusinessException("E-CLM-001", "사고 내용은 1~500자로 입력해야 합니다.");
        }
        if (request.getClaimantId() != null && request.getClaimantId().length() > 20) {
            throw new BusinessException("E-CLM-001", "청구인 ID 는 20자 이하여야 합니다.");
        }
        List<ClaimRequests.ClaimAmount> coverages = requireAmounts(request.getCoverages(), false);
        String requestHash = fingerprint(request);

        Optional<Claim> replay = claimRepository.findByGlobalTxId(globalTxId);
        if (replay.isPresent()) {
            Claim claim = replay.get();
            if (!claim.getReqHash().equals(requestHash)) {
                throw new BusinessException(HttpStatus.CONFLICT, "E-CLM-009",
                        "같은 거래 UUID(globalTxId)로 이전과 다른 사고 접수가 들어왔습니다. 새 거래 UUID 로 다시 요청해 주세요.");
            }
            return detail(claim, readableContract(claim.getContractNo(), ownerEmpId));
        }

        Contract contract = readableContract(request.getContractNo(), ownerEmpId);
        ContractStatus contractStatus = ContractStatus.fromCode(contract.getCtrStatCd());
        if (contractStatus != ContractStatus.IN_FORCE && contractStatus != ContractStatus.TERMINATED) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CLM-002", "유효하거나 해지된 계약만 사고를 접수할 수 있습니다. (계약상태 "
                    + contract.getCtrStatCd() + ")");
        }
        LocalDate coverageEnd = coverageEnd(contract);
        LocalDate today = LocalDate.now(clock);
        if (request.getAcdtDt().isAfter(today)) {
            throw new BusinessException("E-CLM-003", "사고일은 오늘 이후일 수 없습니다.");
        }
        if (request.getAcdtDt().isBefore(contract.getStartDate()) || !request.getAcdtDt().isBefore(coverageEnd)) {
            throw new BusinessException("E-CLM-003", "사고일이 보장 기간(" + contract.getStartDate() + " ~ " + coverageEnd.minusDays(1)
                    + ") 밖입니다.");
        }
        Set<String> insured = coverageRepository.findByContractContractNo(contract.getContractNo()).stream()
                .map(Coverage::getCvrgCd).collect(Collectors.toSet());
        for (ClaimRequests.ClaimAmount c : coverages) {
            if (!insured.contains(c.getCvrgCd())) {
                throw new BusinessException("E-CLM-004", "계약에 가입하지 않은 담보입니다: " + c.getCvrgCd());
            }
        }

        String clmNo = numberGenerator.generateClaimNo(ProductType.fromInsrClsCd(contract.getInsrClsCd()));
        LocalDateTime now = LocalDateTime.now(clock);
        Claim claim = Claim.report(clmNo, contract.getContractNo(), request.getAcdtDt(), request.getAcdtDesc().trim(),
                StringUtils.hasText(request.getClaimantId()) ? request.getClaimantId().trim() : contract.getInsuredId(),
                coverages.stream().map(c -> new ClaimCoverage(c.getCvrgCd(), c.getAmount())).toList(),
                normalizeEmpId(empId), globalTxId, requestHash, now);
        try {
            claim = claimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            if (!isConstraintViolation(e, GLOBAL_TX_ID_CONSTRAINT)) {
                throw e;
            }
            throw new BusinessException(HttpStatus.CONFLICT, "E-CLM-014",
                    "같은 거래(globalTxId)가 동시에 처리 중입니다. 잠시 후 같은 globalTxId 로 다시 요청해 주세요.");
        }
        record(claim, null, globalTxId, empId, null, now);
        log.info("사고 접수: 사고번호={}, 증권번호={}, 담보 {}건, 청구 {}", clmNo, contract.getContractNo(), coverages.size(),
                claim.getTotalClaimAmt());
        return detail(claim, contract);
    }

    /**
     * 손해사정 결과를 기록한다. 담보별 지급액을 계산해 손해사정 완료(지급 대기)로 바꾼다.
     *
     * @throws BusinessException 입력 오류(E-CLM-001, E-CTR-008), 사고 없음(E-CLM-010, 404), 사정할 수 없는 상태(E-CLM-006, 409),
     *                           청구 담보와 다른 사정 항목(E-CLM-013), 금액 오류(E-CLM-005)
     */
    @Transactional
    public ClaimResponses.Detail assess(String globalTxId, String empId, ClaimRequests.Assess request) {
        validateGlobalTxId(globalTxId);
        Claim claim = lockClaim(request.getClmNo());
        if (historyRepository.existsByClmNoAndGlobalTxId(claim.getClmNo(), globalTxId)) {
            return detail(claim, contractRepository.findById(claim.getContractNo()).orElseThrow());
        }
        ClaimStatus before = claim.status();
        if (!before.canChangeTo(ClaimStatus.ASSESSED)) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CLM-006", "손해사정은 접수 또는 손해사정 완료 상태에서만 할 수 있습니다. (상태 "
                    + claim.getClmStatCd() + ")");
        }
        String memo = optionalMemo(request.getMemo());
        Map<String, BigDecimal> assessed = requireAmounts(request.getCoverages(), true).stream()
                .collect(Collectors.toMap(ClaimRequests.ClaimAmount::getCvrgCd, ClaimRequests.ClaimAmount::getAmount));
        Set<String> claimed = claim.getItems().stream().map(ClaimCoverage::getCvrgCd).collect(Collectors.toSet());
        if (!assessed.keySet().equals(claimed)) {
            throw new BusinessException("E-CLM-013", "청구한 담보 전부(" + String.join(", ", claimed.stream().sorted().toList())
                    + ")의 사정 손해액을 입력해야 합니다.");
        }

        // 같은 계약의 다른 사고와 한도를 동시에 쓰지 않도록 계약 행을 잠근다
        Contract contract = contractRepository.findForUpdateByContractNo(claim.getContractNo()).orElseThrow();
        Map<String, BigDecimal> deductibles = deductibles(contract);
        Map<String, BigDecimal> limits = remainingLimits(contract, claim);
        Map<String, ClaimCoverage.Assessment> results = new HashMap<>();
        for (String code : claimed) {
            BigDecimal amount = assessed.get(code);
            BigDecimal deductible = deductibles.getOrDefault(code, BigDecimal.ZERO);
            BigDecimal pay = amount.subtract(deductible).max(BigDecimal.ZERO).min(limits.get(code).max(BigDecimal.ZERO));
            results.put(code, new ClaimCoverage.Assessment(amount, deductible, pay));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        claim.assess(results, normalizeEmpId(empId), now);
        record(claim, before, globalTxId, empId, memo, now);
        log.info("손해사정: 사고번호={}, 지급액 합계={}", claim.getClmNo(), claim.getTotalPayAmt());
        return detail(claim, contract);
    }

    /**
     * 사고를 종결한다. PAY(지급)·DENY(면책)는 보상 담당(CLAIM)과 ADMIN, WITHDRAW(접수 취소)는 접수 화면을 쓰는 설계사도 할 수 있다.
     *
     * @param ownerEmpId 설계사(AGENT 만 가진 사용자)면 본인 접수 계약의 사고만. null 이면 제한 없음
     * @param roles      사용자 역할 (ROLE_ 접두사 없이)
     * @throws BusinessException 입력 오류(E-CLM-001, E-CTR-008), 권한 없음(E-AUTH-002, 403), 사고 없음(E-CLM-010, 404),
     *                           할 수 없는 상태(E-CLM-006, 409), 면책 사유 누락(E-CLM-007), 고액 지급의 관리자 승인 필요(E-CLM-008, 403),
     *                           지급액 0원(E-CLM-011), 남은 가입금액이 줄어 재사정 필요(E-CLM-012, 409)
     */
    @Transactional
    public ClaimResponses.Detail decide(String ownerEmpId, Set<String> roles, String globalTxId, String empId,
                                        ClaimRequests.Decide request) {
        validateGlobalTxId(globalTxId);
        String decision = request.getDecision() == null ? "" : request.getDecision();
        if (!Set.of("PAY", "DENY", "WITHDRAW").contains(decision)) {
            throw new BusinessException("E-CLM-001", "처리는 PAY, DENY, WITHDRAW 중 하나여야 합니다: " + request.getDecision());
        }
        boolean handler = roles.contains(Roles.CLAIM) || roles.contains(Roles.ADMIN);
        if (!decision.equals("WITHDRAW") && !handler) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "E-AUTH-002", "지급·면책은 보상 담당자만 처리할 수 있습니다.");
        }
        Claim claim = lockClaim(request.getClmNo());
        readableContract(claim.getContractNo(), ownerEmpId, ClaimService::claimNotFound);
        if (historyRepository.existsByClmNoAndGlobalTxId(claim.getClmNo(), globalTxId)) {
            return detail(claim, contractRepository.findById(claim.getContractNo()).orElseThrow());
        }
        String memo = optionalMemo(request.getMemo());
        ClaimStatus before = claim.status();
        ClaimStatus next = switch (decision) {
            case "PAY" -> ClaimStatus.PAID;
            case "DENY" -> ClaimStatus.DENIED;
            default -> ClaimStatus.WITHDRAWN;
        };
        if (!before.canChangeTo(next)) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CLM-006", "보상 상태 " + before.getCode() + "에서는 "
                    + decision + " 처리를 할 수 없습니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Contract contract;
        switch (next) {
            case PAID -> {
                if (claim.getTotalPayAmt().signum() == 0) {
                    throw new BusinessException("E-CLM-011", "지급액이 0원입니다. 면책(DENY)으로 종결해 주세요.");
                }
                if (claim.getTotalPayAmt().compareTo(THRESHOLD) > 0 && !roles.contains(Roles.ADMIN)) {
                    throw new BusinessException(HttpStatus.FORBIDDEN, "E-CLM-008", "지급액이 " + HIGH_VALUE_THRESHOLD
                            + "원을 넘는 고액 건은 관리자 승인이 필요합니다.");
                }
                contract = contractRepository.findForUpdateByContractNo(claim.getContractNo()).orElseThrow();
                Map<String, BigDecimal> limits = remainingLimits(contract, claim);
                for (ClaimCoverage item : claim.getItems()) {
                    if (item.getPayAmt().compareTo(limits.get(item.getCvrgCd()).max(BigDecimal.ZERO)) > 0) {
                        throw new BusinessException(HttpStatus.CONFLICT, "E-CLM-012", "손해사정 이후 담보 " + item.getCvrgCd()
                                + "의 남은 가입금액이 줄었습니다. 다시 손해사정해 주세요.");
                    }
                }
                claim.pay(normalizeEmpId(empId), memo, now);
            }
            case DENIED -> {
                if (memo == null) {
                    throw new BusinessException("E-CLM-007", "면책 사유를 입력해야 합니다.");
                }
                contract = contractRepository.findById(claim.getContractNo()).orElseThrow();
                claim.deny(normalizeEmpId(empId), memo, now);
            }
            default -> {
                contract = contractRepository.findById(claim.getContractNo()).orElseThrow();
                claim.withdraw(normalizeEmpId(empId), memo, now);
            }
        }
        record(claim, before, globalTxId, empId, memo, now);
        log.info("보상 종결: 사고번호={}, 처리={}, 지급액={}", claim.getClmNo(), decision, claim.getTotalPayAmt());
        return detail(claim, contract);
    }

    /**
     * 사고 상세를 조회한다.
     *
     * @throws BusinessException 사고번호 누락(E-CLM-001), 사고 없음·권한 밖(E-CLM-010, 404)
     */
    @Transactional(readOnly = true)
    public ClaimResponses.Detail inquiry(String ownerEmpId, String clmNo) {
        if (!StringUtils.hasText(clmNo)) {
            throw new BusinessException("E-CLM-001", "사고번호를 입력해야 합니다.");
        }
        Claim claim = claimRepository.findById(clmNo).orElseThrow(ClaimService::claimNotFound);
        return detail(claim, readableContract(claim.getContractNo(), ownerEmpId, ClaimService::claimNotFound));
    }

    /**
     * 사고 목록을 최근 접수순으로 조회한다.
     *
     * @throws BusinessException 조건 오류(E-CLM-001), 페이징 값 오류(E-CTR-016)
     */
    @Transactional(readOnly = true)
    public ClaimResponses.Page list(String ownerEmpId, ClaimRequests.ListCond cond) {
        PageRequest pageable = pageRequest(cond.getPage(), cond.getSize(), Sort.unsorted());
        if (StringUtils.hasText(cond.getClmStatCd()) && ClaimStatus.findByCode(cond.getClmStatCd()).isEmpty()) {
            throw new BusinessException("E-CLM-001", "없는 보상 상태코드입니다: " + cond.getClmStatCd());
        }
        if (cond.getRptDateFrom() != null && cond.getRptDateTo() != null && cond.getRptDateFrom().isAfter(cond.getRptDateTo())) {
            throw new BusinessException("E-CLM-001", "접수일 시작은 종료보다 클 수 없습니다.");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("owner", ownerEmpId == null ? null : normalizeEmpId(ownerEmpId))
                .addValue("stat", cond.getClmStatCd())
                .addValue("contractNo", cond.getContractNo())
                .addValue("from", cond.getRptDateFrom() == null ? null : cond.getRptDateFrom().atStartOfDay())
                .addValue("to", cond.getRptDateTo() == null ? null : cond.getRptDateTo().plusDays(1).atStartOfDay())
                .addValue("threshold", THRESHOLD)
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (ownerEmpId != null) where.append(" AND L.REG_EMP_ID = :owner");
        if (StringUtils.hasText(cond.getClmStatCd())) where.append(" AND C.CLM_STAT_CD = :stat");
        if (StringUtils.hasText(cond.getContractNo())) where.append(" AND C.CONTRACT_NO = :contractNo");
        if (cond.getRptDateFrom() != null) where.append(" AND C.RPT_DT >= :from");
        if (cond.getRptDateTo() != null) where.append(" AND C.RPT_DT < :to");
        if (Boolean.TRUE.equals(cond.getHighValueOnly())) where.append(" AND C.CLM_STAT_CD = '20' AND C.TOTAL_PAY_AMT > :threshold");
        String from = " FROM TB_CLM C JOIN TB_CTR_LGD L ON L.CONTRACT_NO = C.CONTRACT_NO" + where;

        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, params, Long.class);
        List<ClaimResponses.Summary> items = jdbc.query("""
                SELECT C.CLM_NO, C.CONTRACT_NO, L.INSR_CLS_CD, C.CLM_STAT_CD, C.ACDT_DT, C.CLAIMANT_ID, C.TOTAL_CLAIM_AMT,
                       C.TOTAL_PAY_AMT, C.RPT_DT, C.RPT_EMP_ID""" + from + """
                 ORDER BY C.RPT_DT DESC, C.CLM_NO DESC
                 LIMIT :limit OFFSET :offset
                """, params, (rs, i) -> {
            BigDecimal pay = rs.getBigDecimal("TOTAL_PAY_AMT");
            return ClaimResponses.Summary.builder()
                    .clmNo(rs.getString("CLM_NO"))
                    .contractNo(rs.getString("CONTRACT_NO"))
                    .insrClsCd(rs.getString("INSR_CLS_CD"))
                    .clmStatCd(rs.getString("CLM_STAT_CD"))
                    .acdtDt(rs.getObject("ACDT_DT", LocalDate.class))
                    .claimantId(rs.getString("CLAIMANT_ID"))
                    .totalClaimAmt(rs.getBigDecimal("TOTAL_CLAIM_AMT"))
                    .totalPayAmt(pay)
                    .highValue(pay != null && pay.compareTo(THRESHOLD) > 0)
                    .rptDt(rs.getObject("RPT_DT", LocalDateTime.class))
                    .rptEmpId(rs.getString("RPT_EMP_ID"))
                    .build();
        });
        long count = total == null ? 0 : total;
        return ClaimResponses.Page.builder()
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(count)
                .totalPages((int) ((count + pageable.getPageSize() - 1) / pageable.getPageSize()))
                .items(items)
                .build();
    }

    /** 보장이 끝나는 날(그날은 보장하지 않음): 보험종기와 해지일 중 이른 날 */
    static LocalDate coverageEnd(Contract contract) {
        return contract.getTermDate() != null && contract.getTermDate().isBefore(contract.getEndDate())
                ? contract.getTermDate() : contract.getEndDate();
    }

    private Claim lockClaim(String clmNo) {
        if (!StringUtils.hasText(clmNo)) {
            throw new BusinessException("E-CLM-001", "사고번호를 입력해야 합니다.");
        }
        return claimRepository.findForUpdateByClmNo(clmNo).orElseThrow(ClaimService::claimNotFound);
    }

    private Contract readableContract(String contractNo, String ownerEmpId) {
        return readableContract(contractNo, ownerEmpId, ContractSupport::contractNotFound);
    }

    private Contract readableContract(String contractNo, String ownerEmpId, java.util.function.Supplier<BusinessException> notFound) {
        return contractRepository.findById(contractNo).filter(c -> isReadable(c, ownerEmpId)).orElseThrow(notFound);
    }

    /** 담보별 자기부담금 (상품이 정한 값. 상품 없는 계약은 모두 0) */
    private Map<String, BigDecimal> deductibles(Contract contract) {
        if (contract.getPrdCd() == null) {
            return Map.of();
        }
        return productRepository.findById(contract.getPrdCd()).map(Product::getCoverages).orElse(List.of()).stream()
                .collect(Collectors.toMap(ProductCoverage::getCvrgCd, ProductCoverage::getDeductibleAmt));
    }

    /** 담보별 남은 가입금액 = 가입금액 − 이 계약의 다른 사고로 이미 지급한 금액 */
    private Map<String, BigDecimal> remainingLimits(Contract contract, Claim claim) {
        Map<String, BigDecimal> insured = coverageRepository.findByContractContractNo(contract.getContractNo()).stream()
                .collect(Collectors.toMap(Coverage::getCvrgCd, Coverage::getIsrdAmt));
        return claim.getItems().stream().collect(Collectors.toMap(ClaimCoverage::getCvrgCd, item ->
                insured.getOrDefault(item.getCvrgCd(), BigDecimal.ZERO)
                        .subtract(claimRepository.sumPaid(contract.getContractNo(), item.getCvrgCd(), claim.getClmNo()))));
    }

    private void record(Claim claim, ClaimStatus before, String globalTxId, String empId, String memo, LocalDateTime now) {
        historyRepository.save(ClaimHistory.builder()
                .clmNo(claim.getClmNo())
                .beforeStatCd(before == null ? null : before.getCode())
                .afterStatCd(claim.getClmStatCd())
                .empId(normalizeEmpId(empId))
                .memo(memo)
                .globalTxId(globalTxId)
                .chgDt(now)
                .build());
    }

    private ClaimResponses.Detail detail(Claim claim, Contract contract) {
        Map<String, BigDecimal> insured = coverageRepository.findByContractContractNo(contract.getContractNo()).stream()
                .collect(Collectors.toMap(Coverage::getCvrgCd, Coverage::getIsrdAmt));
        BigDecimal pay = claim.getTotalPayAmt();
        return ClaimResponses.Detail.builder()
                .clmNo(claim.getClmNo())
                .clmStatCd(claim.getClmStatCd())
                .acdtDt(claim.getAcdtDt())
                .acdtDesc(claim.getAcdtDesc())
                .claimantId(claim.getClaimantId())
                .totalClaimAmt(claim.getTotalClaimAmt())
                .totalPayAmt(pay)
                .highValue(pay != null && pay.compareTo(THRESHOLD) > 0)
                .highValueThreshold(THRESHOLD)
                .rptDt(claim.getRptDt())
                .rptEmpId(claim.getRptEmpId())
                .adjEmpId(claim.getAdjEmpId())
                .adjDt(claim.getAdjDt())
                .decideEmpId(claim.getDecideEmpId())
                .decideDt(claim.getDecideDt())
                .decideMemo(claim.getDecideMemo())
                .contract(ClaimResponses.ContractBrief.builder()
                        .contractNo(contract.getContractNo())
                        .insrClsCd(contract.getInsrClsCd())
                        .prdCd(contract.getPrdCd())
                        .ctrStatCd(contract.getCtrStatCd())
                        .policyholderId(contract.getPolicyholderId())
                        .insuredId(contract.getInsuredId())
                        .startDate(contract.getStartDate())
                        .endDate(contract.getEndDate())
                        .termDate(contract.getTermDate())
                        .build())
                .items(claim.getItems().stream().sorted(Comparator.comparing(ClaimCoverage::getCvrgCd))
                        .map(i -> ClaimResponses.Item.builder()
                                .cvrgCd(i.getCvrgCd())
                                .isrdAmt(insured.get(i.getCvrgCd()))
                                .paidByOthers(claimRepository.sumPaid(contract.getContractNo(), i.getCvrgCd(), claim.getClmNo()))
                                .claimAmt(i.getClaimAmt())
                                .assessedAmt(i.getAssessedAmt())
                                .deductibleAmt(i.getDeductibleAmt())
                                .payAmt(i.getPayAmt())
                                .build())
                        .toList())
                .histories(historyRepository.findByClmNoOrderByHistId(claim.getClmNo()).stream()
                        .map(h -> ClaimResponses.History.builder().beforeStatCd(h.getBeforeStatCd()).afterStatCd(h.getAfterStatCd())
                                .empId(h.getEmpId()).memo(h.getMemo()).chgDt(h.getChgDt()).build())
                        .toList())
                .build();
    }

    /** 담보 금액 목록 검증: 1건 이상, 코드 중복 없음, 금액은 정수 (접수는 0 초과, 사정은 0 이상) */
    private static List<ClaimRequests.ClaimAmount> requireAmounts(List<ClaimRequests.ClaimAmount> list, boolean zeroAllowed) {
        if (list == null || list.isEmpty() || list.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException("E-CLM-004", "담보를 하나 이상 입력해야 합니다.");
        }
        Set<String> seen = new HashSet<>();
        for (ClaimRequests.ClaimAmount c : list) {
            if (isInvalidCode(c.getCvrgCd(), 10) || !seen.add(c.getCvrgCd())) {
                throw new BusinessException("E-CLM-004", "담보코드가 비었거나 중복되었습니다: " + c.getCvrgCd());
            }
            if (isInvalidAmount(c.getAmount()) || (!zeroAllowed && c.getAmount().signum() == 0)) {
                throw new BusinessException("E-CLM-005", "담보 " + c.getCvrgCd() + "의 금액은 "
                        + (zeroAllowed ? "0 이상" : "0보다 큰") + " 15자리 이하 정수여야 합니다.");
            }
        }
        return list;
    }

    private static String optionalMemo(String memo) {
        if (!StringUtils.hasText(memo)) {
            return null;
        }
        if (memo.length() > 500) {
            throw new BusinessException("E-CLM-001", "메모는 500자 이하여야 합니다.");
        }
        return memo.trim();
    }

    /** 접수 요청 내용의 SHA-256 지문. 담보 순서와 금액 표기(150000 / 150000.00)는 결과에 영향이 없다 */
    private static String fingerprint(ClaimRequests.Report request) {
        StringBuilder canonical = new StringBuilder();
        Function<Object, String> v = o -> o == null ? "\u0000null" : o.toString();
        for (Object part : new Object[] {request.getContractNo(), request.getAcdtDt(), request.getAcdtDesc(), request.getClaimantId()}) {
            String text = v.apply(part);
            canonical.append(text.length()).append(':').append(text).append(';');
        }
        request.getCoverages().stream().sorted(Comparator.comparing(ClaimRequests.ClaimAmount::getCvrgCd)).forEach(c -> {
            String text = c.getCvrgCd() + "=" + c.getAmount().stripTrailingZeros().toPlainString();
            canonical.append(text.length()).append(':').append(text).append(';');
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException claimNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "E-CLM-010", "해당 사고가 존재하지 않습니다.");
    }
}
