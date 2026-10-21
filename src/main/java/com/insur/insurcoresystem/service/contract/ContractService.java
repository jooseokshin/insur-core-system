package com.insur.insurcoresystem.service.contract;

import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidAmount;
import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidCode;
import static com.insur.insurcoresystem.service.contract.ContractSupport.isConstraintViolation;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.validateGlobalTxId;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.web.LogSanitizer;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.domain.contract.Coverage;
import com.insur.insurcoresystem.domain.contract.CoverageRepository;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyResponse;
import com.insur.insurcoresystem.service.contract.dto.CoverageRequest;
import com.insur.insurcoresystem.service.premium.PremiumService;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteRequest.QuoteCoverageRequest;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteRequest;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteResponse.QuoteCoverageResponse;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteResponse;
import com.insur.insurcoresystem.service.sequence.ContractNumberGenerator;
import com.insur.insurcoresystem.service.sequence.ContractNumbers;
import com.insur.insurcoresystem.service.sequence.ProductType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 청약 접수 서비스. 조회는 {@link ContractInquiryService}, 상태 변경은 {@link ContractStatusService},
 * 변경(배서)은 {@link ContractEndorsementService}, 갱신은 {@link ContractRenewalService} 가 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    /** 거래 UUID 유니크 제약명 (V2 마이그레이션). PostgreSQL 은 따옴표 없는 이름을 소문자로 저장한다. */
    private static final String GLOBAL_TX_ID_CONSTRAINT = "uk_ctr_lgd_global_tx_id";

    /** 갱신 원 계약 유니크 인덱스명 (V17 마이그레이션) */
    private static final String RENEWED_FROM_CONSTRAINT = "uk_ctr_lgd_renewed_from";

    private final ContractNumberGenerator contractNumberGenerator;

    private final ContractRepository contractRepository;

    private final CoverageRepository coverageRepository;

    /** 청약 접수의 최초 상태 이력 기록 */
    private final ContractHistoryRecorder historyRecorder;

    /** 청약 보험료를 요율표 기준 산출 보험료와 대조하는 데 쓴다 */
    private final PremiumService premiumService;

    /** 업무 시간대(Asia/Seoul) 기준 현재 시각 */
    private final Clock clock;

    /**
     * 계약 청약을 처리한다. 하나의 트랜잭션으로 아래 순서로 수행하며, 중간에 실패하면 채번을 포함해 전체가 롤백된다.
     * <ol>
     *     <li>보험종목코드 검증 및 상품구분 매핑</li>
     *     <li>담보보험료 합계와 총보험료 정합성 검증</li>
     *     <li>보험기간, 계약자/피보험자 ID, 담보별 입력값 검증 (DB 제약 위반을 사전에 업무 오류로 변환)</li>
     *     <li>동일 globalTxId 로 이미 접수된 청약이면 새로 저장하지 않고 기존 결과를 반환 (멱등성)</li>
     *     <li>담보별 보험료가 요율표로 산출한 보험료와 일치하는지 검증 (새로 접수하는 청약만. 재전송은 요율이 바뀌었어도 기존 결과를 돌려준다)</li>
     *     <li>증권번호/청약번호 채번</li>
     *     <li>계약 원장(청약 상태) 및 담보 저장</li>
     *     <li>계약상태 변경 이력에 최초 상태(청약) 기록</li>
     * </ol>
     * 동일 globalTxId 가 동시에 두 번 들어오면 DB 유니크 제약으로 한쪽만 저장되고, 나머지는 채번까지 롤백한 뒤 충돌 오류(E-CTR-011, HTTP 409)로 응답한다.
     * 충돌 응답을 받은 쪽이 같은 globalTxId 로 재전송하면 먼저 접수된 결과를 받는다.
     * 재전송은 요청 내용(업무 필드의 지문)이 처음 접수한 것과 같을 때만 기존 결과를 돌려주고, 내용이 다르면 거절한다(E-CTR-019, 409).
     * 지문이 없는 이전 계약은 내용을 비교하지 않는다. 또한 처음 접수한 사번과 다른 사번이
     * 같은 globalTxId 로 재전송하면 그 계약의 번호를 알려주지 않고 거절한다(E-CTR-015, 409).
     *
     * @param globalTxId 거래 UUID (멱등성 키)
     * @param empId      처리 사번 (이력에 기록)
     * @param request    청약 요청 바디
     * @return 채번된 증권번호/청약번호, 계약상태, 총보험료, 담보 건수
     * @throws BusinessException 지원하지 않는 보험종목(E-SEQ-001), 담보/보험료/입력값 검증 실패(E-CTR-001~008),
     *                           동일 거래 동시 접수 충돌(E-CTR-011, 409), 다른 사용자의 거래 UUID(E-CTR-015, 409),
     *                           같은 거래 UUID 로 다른 내용의 재전송(E-CTR-019, 409),
     *                           요율표에 없는 담보/담보코드 중복/요율 계수 오류(E-PRM-001~004), 산출 보험료와 불일치(E-CTR-020),
     *                           채번 한도 초과(E-SEQ-002)
     */
    @Transactional
    public ContractApplyResponse applyContract(String globalTxId, String empId, ContractApplyRequest request) {
        return applyContract(globalTxId, empId, request, null);
    }

    /**
     * 청약 접수의 실제 처리. 갱신으로 만드는 계약이면 원 계약의 증권번호를 함께 저장한다. 나머지 규칙은
     * {@link #applyContract(String, String, ContractApplyRequest)} 와 같다.
     *
     * @param renewedFrom 갱신의 원 계약 증권번호. 일반 청약이면 null
     * @throws BusinessException 위 청약 오류 외에, 같은 원 계약의 갱신이 동시에 저장되어 충돌한 경우(E-CTR-025, 409)
     */
    @Transactional
    public ContractApplyResponse applyContract(String globalTxId, String empId, ContractApplyRequest request,
            String renewedFrom) {
        ProductType productType = ProductType.fromInsrClsCd(request.getInsrClsCd());
        validatePremium(request);
        validateContractInfo(request);
        request.getCoverages().forEach(this::validateCoverage);
        validateGlobalTxId(globalTxId);

        log.info("청약 접수 시작: 보험종목={}, 담보 {}건, 총보험료={}",
                request.getInsrClsCd(), request.getCoverages().size(), request.getTotalPremium());

        String requestHash = RequestFingerprint.of(request);
        Optional<Contract> applied = contractRepository.findByGlobalTxId(globalTxId);
        if (applied.isPresent()) {
            Contract contract = applied.get();
            if (contract.getRegEmpId() != null && !contract.getRegEmpId().equals(normalizeEmpId(empId))) {
                // 다른 사용자가 접수한 거래 UUID 로는 그 계약의 번호를 돌려받을 수 없다
                log.warn("다른 사용자가 접수한 거래 UUID 로 재전송이 들어왔다: 접수 사번={}, 요청 사번={}",
                        LogSanitizer.clean(contract.getRegEmpId()), LogSanitizer.clean(empId));
                throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-015", "이미 다른 사용자가 사용한 거래 UUID(globalTxId)입니다.");
            }
            if (contract.getReqHash() != null && !contract.getReqHash().equals(requestHash)) {
                log.warn("같은 거래 UUID 로 다른 내용의 청약이 들어왔다: 증권번호={}", contract.getContractNo());
                throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-019",
                        "같은 거래 UUID(globalTxId)로 이전과 다른 내용의 청약이 들어왔습니다. 새 거래 UUID 로 다시 요청해 주세요.");
            }
            log.info("이미 접수된 거래의 재전송이므로 기존 결과를 반환한다: 증권번호={}", contract.getContractNo());
            return toResponse(contract, coverageRepository.findByContractContractNo(contract.getContractNo()).size());
        }

        verifyAgainstTariff(request);

        ContractNumbers numbers = contractNumberGenerator.generate(productType);

        Contract contract = saveContract(Contract.builder()
                .contractNo(numbers.contractNo())
                .applNo(numbers.applNo())
                .globalTxId(globalTxId)
                .insrClsCd(request.getInsrClsCd())
                .ctrStatCd(Contract.CTR_STAT_APPLIED)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .policyholderId(request.getPolicyholderId())
                .insuredId(request.getInsuredId())
                .totalPremium(request.getTotalPremium())
                .sysRegDt(LocalDateTime.now(clock))
                .regEmpId(normalizeEmpId(empId))
                .reqHash(requestHash)
                .driverAge(request.getRating() == null ? null : request.getRating().getDriverAge())
                .discountGrade(request.getRating() == null ? null : request.getRating().getDiscountGrade())
                .structureGrade(request.getRating() == null ? null : request.getRating().getStructureGrade())
                .renewedFrom(renewedFrom)
                .build());

        List<Coverage> coverages = request.getCoverages().stream()
                .map(cvrg -> Coverage.builder()
                        .contract(contract)
                        .cvrgCd(cvrg.getCvrgCd())
                        .isrdAmt(cvrg.getIsrdAmt())
                        .prem(cvrg.getPrem())
                        .build())
                .toList();
        coverageRepository.saveAll(coverages);
        historyRecorder.record(contract.getContractNo(), null, ContractStatus.APPLIED, globalTxId, empId);

        log.info("청약 접수 완료: 증권번호={}, 청약번호={}, 담보 {}건",
                contract.getContractNo(), contract.getApplNo(), coverages.size());
        return toResponse(contract, coverages.size());
    }

    /**
     * 담보별 보험료가 요율표로 산출한 보험료와 같은지 확인한다. 클라이언트가 보험료를 임의로 바꿔 보내는 것을 막는다.
     * 산출은 보험료 산출 API 와 같은 계산(PremiumService)을 쓰므로, 산출 API 의 결과를 그대로 넣으면 항상 통과한다.
     * 담보별 보험료가 모두 같으면 합계인 총보험료도 같으므로 총보험료는 따로 대조하지 않는다.
     *
     * @throws BusinessException 요율표에 없는 담보(E-PRM-001), 담보코드 중복(E-PRM-002), 산출 보험료와 다른 경우(E-CTR-020)
     */
    private void verifyAgainstTariff(ContractApplyRequest request) {
        PremiumQuoteResponse quote = premiumService.quote(PremiumQuoteRequest.builder()
                .insrClsCd(request.getInsrClsCd())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .rating(request.getRating())
                .coverages(request.getCoverages().stream()
                        .map(cvrg -> QuoteCoverageRequest.builder()
                                .cvrgCd(cvrg.getCvrgCd()).isrdAmt(cvrg.getIsrdAmt()).build())
                        .toList())
                .build());
        Map<String, BigDecimal> expected = quote.getCoverages().stream()
                .collect(Collectors.toMap(QuoteCoverageResponse::getCvrgCd, QuoteCoverageResponse::getPrem));
        for (CoverageRequest cvrg : request.getCoverages()) {
            BigDecimal expectedPrem = expected.get(cvrg.getCvrgCd());
            if (expectedPrem.compareTo(cvrg.getPrem()) != 0) {
                throw new BusinessException("E-CTR-020", "담보 " + cvrg.getCvrgCd() + "의 보험료(" + cvrg.getPrem()
                        + ")가 산출 보험료(" + expectedPrem + ")와 일치하지 않습니다.");
            }
        }
    }

    /**
     * 계약 원장을 즉시 저장(flush)한다. 같은 globalTxId 로 먼저 접수한 트랜잭션이 있으면 유니크 제약 위반이 이 시점에 발생하므로,
     * 그 경우만 충돌 오류(E-CTR-011, HTTP 409)로 바꾼다. 담보를 저장하기 전에 실패시켜 불필요한 쓰기를 막는 목적도 있다.
     * 다른 제약 위반은 예상하지 못한 오류이므로 그대로 던진다.
     */
    private Contract saveContract(Contract contract) {
        try {
            return contractRepository.saveAndFlush(contract);
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, RENEWED_FROM_CONSTRAINT)) {
                throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-025", "이미 갱신된 계약입니다.");
            }
            if (!isGlobalTxIdConflict(e)) {
                throw e;
            }
            log.warn("같은 거래의 동시 접수가 충돌했다: globalTxId={}", LogSanitizer.clean(contract.getGlobalTxId()));
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-011",
                    "같은 거래(globalTxId)가 동시에 처리 중입니다. 잠시 후 같은 globalTxId 로 다시 요청해 주세요.");
        }
    }

    /**
     * 데이터 무결성 오류가 거래 UUID 유니크 제약(uk_ctr_lgd_global_tx_id) 위반인지 판단한다.
     * 오류 메시지 문장이 아니라 Hibernate 가 DB 오류에서 뽑아 둔 제약명({@link ConstraintViolationException#getConstraintName()})으로
     * 비교하므로 DB 메시지 형식이나 언어 설정이 바뀌어도 영향받지 않는다. 제약명을 알 수 없으면 충돌로 보지 않고 일반 오류로 남긴다.
     * 제약명은 스키마가 붙어 올 수 있고 대소문자가 다를 수 있어 소문자로 바꿔 끝부분을 비교한다.
     */
    private static boolean isGlobalTxIdConflict(DataIntegrityViolationException e) {
        return isConstraintViolation(e, GLOBAL_TX_ID_CONSTRAINT);
    }

    /** 계약 원장과 담보 건수로 청약 응답 바디를 만든다. 신규 접수와 재전송 응답이 같은 형태를 갖도록 공통으로 사용한다. */
    private static ContractApplyResponse toResponse(Contract contract, int coverageCount) {
        return ContractApplyResponse.builder()
                .contractNo(contract.getContractNo())
                .applNo(contract.getApplNo())
                .ctrStatCd(contract.getCtrStatCd())
                .totalPremium(contract.getTotalPremium())
                .coverageCount(coverageCount)
                .build();
    }

    /**
     * 담보별 보험료 합계와 총보험료의 정합성을 검증한다.
     */
    private void validatePremium(ContractApplyRequest request) {
        List<CoverageRequest> coverages = request.getCoverages();
        if (coverages == null || coverages.isEmpty()) {
            throw new BusinessException("E-CTR-001", "담보가 존재하지 않습니다.");
        }
        if (coverages.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException("E-CTR-001", "담보 목록에 빈 항목이 있습니다.");
        }
        if (request.getTotalPremium() == null || coverages.stream().anyMatch(c -> c.getPrem() == null)) {
            throw new BusinessException("E-CTR-002", "보험료가 입력되지 않았습니다.");
        }

        BigDecimal coverageSum = coverages.stream()
                .map(CoverageRequest::getPrem)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (coverageSum.compareTo(request.getTotalPremium()) != 0) {
            throw new BusinessException("E-CTR-003",
                    "총보험료(" + request.getTotalPremium() + ")와 담보보험료 합계(" + coverageSum + ")가 일치하지 않습니다.");
        }
    }

    /**
     * 계약 단위 입력값을 검증한다. 보험시기/종기 필수 및 선후 관계, 계약자/피보험자 ID 필수 및 컬럼 길이(20)를 확인한다.
     */
    private void validateContractInfo(ContractApplyRequest request) {
        if (request.getStartDate() == null || request.getEndDate() == null
                || !request.getEndDate().isAfter(request.getStartDate())) {
            throw new BusinessException("E-CTR-004", "보험기간이 올바르지 않습니다. 보험종기는 보험시기 이후여야 합니다.");
        }
        if (isInvalidCode(request.getPolicyholderId(), 20) || isInvalidCode(request.getInsuredId(), 20)) {
            throw new BusinessException("E-CTR-005", "계약자/피보험자 ID는 필수이며 20자 이하여야 합니다.");
        }
        if (isInvalidAmount(request.getTotalPremium())) {
            throw new BusinessException("E-CTR-007", "총보험료는 0 이상의 15자리 이하 정수여야 합니다.");
        }
    }

    /**
     * 담보 단위 입력값을 검증한다. 담보코드 필수 및 컬럼 길이(10), 가입금액/담보보험료의 금액 형식을 확인한다.
     */
    private void validateCoverage(CoverageRequest coverage) {
        if (isInvalidCode(coverage.getCvrgCd(), 10)) {
            throw new BusinessException("E-CTR-006", "담보코드는 필수이며 10자 이하여야 합니다.");
        }
        if (isInvalidAmount(coverage.getIsrdAmt()) || isInvalidAmount(coverage.getPrem())) {
            throw new BusinessException("E-CTR-007",
                    "가입금액/담보보험료는 0 이상의 15자리 이하 정수여야 합니다. (담보코드: " + coverage.getCvrgCd() + ")");
        }
    }
}
