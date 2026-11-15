package com.insur.insurcoresystem.service.cms;

import static com.insur.insurcoresystem.service.contract.ContractSupport.isConstraintViolation;
import static com.insur.insurcoresystem.service.contract.ContractSupport.isReadable;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.validateGlobalTxId;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.security.AccountCipher;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.bank.BankClient;
import com.insur.insurcoresystem.ext.bank.BankLayouts;
import com.insur.insurcoresystem.mapper.cms.CmsMapper;
import com.insur.insurcoresystem.mapper.contract.ContractMapper;
import com.insur.insurcoresystem.service.cms.dto.AutoDebitRequests;
import com.insur.insurcoresystem.service.cms.dto.AutoDebitResponse;
import com.insur.insurcoresystem.service.contract.ContractSupport;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 보험료 자동이체 등록(출금 동의)·해지·조회와 갱신 승계.
 * <ul>
 *     <li>출금 동의는 예금주에게서 받는다(계약자와 달라도 된다: 가족 계좌 등). 동의 방식(서면·녹취·ARS·전자서명)과 증빙 관리번호를 남긴다.
 *         동의 없는 출금(CMS 부당 인출)은 실제 사고 사례가 있어 증빙이 핵심이다.</li>
 *     <li>등록할 때 은행 예금주 조회로 계좌와 예금주를 바로 확인한다(입력 실수를 다음 날 결제원 결과까지 기다리지 않게). 결제원 등록은 배치가 한다.</li>
 *     <li>계약당 살아 있는 등록은 하나. 출금이 진행 중(의뢰 대상·결과 대기)이면 해지할 수 없다.</li>
 *     <li>갱신 계약은 원 계약의 등록(완료)을 이어받는다. 납부자번호(증권번호)가 바뀌므로 결제원에는 새로 등록 의뢰한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CmsMandateService {

    private static final Set<String> CONSENTS = Set.of("PAPER", "RECORDING", "ARS", "E_SIGN");
    private static final String ACTIVE_INDEX = "uk_cms_mandate_active";

    private final CmsMapper cmsMapper;
    private final ContractMapper contractMapper;
    private final BankClient bank;
    private final AccountCipher cipher;
    private final Clock clock;

    /**
     * 자동이체를 등록한다.
     *
     * @param ownerEmpId 본인 계약으로 제한할 사번(설계사·채널). null 이면 제한 없음
     * @throws BusinessException 입력 오류(E-CMS-001), 계약 없음(E-CTR-010, 404), 청약·유효 계약이 아님(E-CMS-002, 409),
     *                           계좌 오류·예금주 불일치(E-CMS-003), 이미 등록됨(E-CMS-004, 409), 은행 장애(E-CMS-006, 503),
     *                           같은 거래 UUID 로 다른 요청(E-CMS-007, 409), 암호화 키 없음(E-PAY-009, 503)
     */
    @Transactional
    public AutoDebitResponse register(String ownerEmpId, String globalTxId, String empId, AutoDebitRequests.Register request) {
        validateGlobalTxId(globalTxId);
        if (!StringUtils.hasText(request.getContractNo()) || request.getBankCd() == null || !request.getBankCd().matches("\\d{3}")
                || request.getAccountNo() == null || !request.getAccountNo().matches("\\d{10,16}") || !StringUtils.hasText(request.getPayerNm())
                || request.getPayerNm().trim().length() > 20 || !CONSENTS.contains(String.valueOf(request.getConsentMethod()))
                || !StringUtils.hasText(request.getConsentRef()) || request.getConsentRef().length() > 100) {
            throw new BusinessException("E-CMS-001", "증권번호, 은행(3자리), 계좌(10~16자리), 예금주(20자 이하), 동의 방식(PAPER/RECORDING/ARS/E_SIGN), "
                    + "동의 증빙 관리번호(100자 이하)를 입력해야 합니다.");
        }
        Optional<String> replay = cmsMapper.findMandateContractByTx(globalTxId);
        if (replay.isPresent()) {
            if (!request.getContractNo().equals(replay.get())) {
                throw new BusinessException(HttpStatus.CONFLICT, "E-CMS-007", "같은 거래 UUID(globalTxId)로 다른 자동이체 등록이 들어왔습니다.");
            }
            return inquiry(ownerEmpId, request.getContractNo());
        }
        Contract contract = contractMapper.findForUpdateByContractNo(request.getContractNo())
                .filter(c -> isReadable(c, ownerEmpId)).orElseThrow(ContractSupport::contractNotFound);
        if (!List.of("10", "20").contains(contract.getCtrStatCd())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CMS-002", "청약(10) 또는 유효(20) 계약만 자동이체를 등록할 수 있습니다.");
        }
        if (activeExists(contract.getContractNo())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CMS-004", "이미 자동이체가 등록되어 있습니다. 바꾸려면 먼저 해지해 주세요.");
        }
        BankClient.VerifyResult verified;
        try {
            verified = bank.verify(request.getBankCd(), request.getAccountNo());
        } catch (ExternalAgencyException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "E-CMS-006", "은행 예금주 조회를 하지 못했습니다. 잠시 후 다시 해 주세요.");
        }
        if (!verified.ok()) {
            throw new BusinessException("E-CMS-003", "출금할 수 없는 계좌입니다: " + BankLayouts.describe(verified.code()));
        }
        if (!verified.holderNm().replaceAll("\\s", "").equals(request.getPayerNm().replaceAll("\\s", ""))) {
            throw new BusinessException("E-CMS-003", "예금주가 다릅니다. 출금 동의는 예금주에게서 받아야 합니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            cmsMapper.insertMandate(CmsMapper.NewMandate.builder()
                    .contractNo(contract.getContractNo()).payerNm(verified.holderNm().trim()).bankCd(request.getBankCd())
                    .accountEnc(cipher.encrypt(request.getAccountNo())).accountMask(AccountCipher.mask(request.getAccountNo()))
                    .consentMethod(request.getConsentMethod()).now(now).consentRef(request.getConsentRef().trim())
                    .regEmpId(normalizeEmpId(empId)).globalTxId(globalTxId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, ACTIVE_INDEX)) {
                throw new BusinessException(HttpStatus.CONFLICT, "E-CMS-004", "같은 계약의 자동이체가 동시에 등록되었습니다.");
            }
            throw e;
        }
        log.info("자동이체 등록: 증권번호={}, 계좌={} {}, 동의={}", contract.getContractNo(), request.getBankCd(),
                AccountCipher.mask(request.getAccountNo()), request.getConsentMethod());
        return inquiry(ownerEmpId, contract.getContractNo());
    }

    /**
     * 자동이체를 해지한다.
     *
     * @throws BusinessException 계약 없음(E-CTR-010, 404), 등록 없음(E-CMS-008, 404), 출금 진행 중(E-CMS-005, 409)
     */
    @Transactional
    public AutoDebitResponse cancel(String ownerEmpId, String empId, String contractNo) {
        Contract contract = contractMapper.findForUpdateByContractNo(contractNo == null ? "" : contractNo)
                .filter(c -> isReadable(c, ownerEmpId)).orElseThrow(ContractSupport::contractNotFound);
        if (cmsMapper.countDebitsInFlight(contract.getContractNo()) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CMS-005", "출금이 진행 중이라 해지할 수 없습니다. 출금 결과가 나온 뒤 해지해 주세요.");
        }
        int updated = cmsMapper.cancelActiveMandate(contract.getContractNo(), LocalDateTime.now(clock), normalizeEmpId(empId));
        if (updated == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "E-CMS-008", "해지할 자동이체 등록이 없습니다.");
        }
        log.info("자동이체 해지: 증권번호={}", contract.getContractNo());
        return inquiry(ownerEmpId, contract.getContractNo());
    }

    /**
     * 자동이체 현황 (가장 최근 등록과 출금 내역).
     *
     * @throws BusinessException 계약 없음·권한 밖(E-CTR-010, 404)
     */
    @Transactional(readOnly = true)
    public AutoDebitResponse inquiry(String ownerEmpId, String contractNo) {
        Contract contract = contractMapper.findById(contractNo == null ? "" : contractNo)
                .filter(c -> isReadable(c, ownerEmpId)).orElseThrow(ContractSupport::contractNotFound);
        AutoDebitResponse.Mandate mandate = cmsMapper.findLatestMandate(contract.getContractNo()).orElse(null);
        List<AutoDebitResponse.Debit> debits = cmsMapper.findRecentDebits(contract.getContractNo());
        return AutoDebitResponse.builder().contractNo(contract.getContractNo()).mandate(mandate).debits(debits).build();
    }

    /**
     * 갱신 계약에 원 계약의 자동이체(등록 완료)를 이어 준다. 동의와 계좌는 그대로, 결제원 등록은 새 납부자번호로 다시 의뢰(대기 10)한다.
     * 갱신 재전송에도 한 번만 만들어지도록 거래 UUID 를 정해 두고 충돌은 무시한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void inheritForRenewal(String sourceContractNo, String renewalContractNo, String empId) {
        int n = cmsMapper.inheritMandate(sourceContractNo, renewalContractNo, normalizeEmpId(empId), LocalDateTime.now(clock));
        if (n > 0) {
            log.info("갱신 계약에 자동이체 승계: {} → {}", sourceContractNo, renewalContractNo);
        }
    }

    private boolean activeExists(String contractNo) {
        return cmsMapper.existsActiveMandate(contractNo);
    }
}
