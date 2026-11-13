package com.insur.insurcoresystem.service.cms;

import static com.insur.insurcoresystem.service.contract.ContractSupport.isConstraintViolation;
import static com.insur.insurcoresystem.service.contract.ContractSupport.isReadable;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.validateGlobalTxId;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.security.AccountCipher;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.bank.BankClient;
import com.insur.insurcoresystem.ext.bank.BankLayouts;
import com.insur.insurcoresystem.service.cms.dto.AutoDebitRequests;
import com.insur.insurcoresystem.service.cms.dto.AutoDebitResponse;
import com.insur.insurcoresystem.service.contract.ContractSupport;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

    private final NamedParameterJdbcTemplate jdbc;
    private final ContractRepository contractRepository;
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
        List<Map<String, Object>> replay = jdbc.queryForList("SELECT CONTRACT_NO FROM TB_CMS_MANDATE WHERE GLOBAL_TX_ID = :tx",
                new MapSqlParameterSource("tx", globalTxId));
        if (!replay.isEmpty()) {
            if (!request.getContractNo().equals(replay.get(0).get("contract_no"))) {
                throw new BusinessException(HttpStatus.CONFLICT, "E-CMS-007", "같은 거래 UUID(globalTxId)로 다른 자동이체 등록이 들어왔습니다.");
            }
            return inquiry(ownerEmpId, request.getContractNo());
        }
        Contract contract = contractRepository.findForUpdateByContractNo(request.getContractNo())
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
            jdbc.update("""
                    INSERT INTO TB_CMS_MANDATE (CONTRACT_NO, PAYER_NM, BANK_CD, ACCOUNT_ENC, ACCOUNT_MASK, CONSENT_METHOD, CONSENT_DT, CONSENT_REF,
                                                STAT_CD, REG_EMP_ID, REG_DT, GLOBAL_TX_ID)
                    VALUES (:contractNo, :payer, :bank, :enc, :mask, :consent, :now, :ref, '10', :emp, :now, :tx)
                    """, new MapSqlParameterSource("contractNo", contract.getContractNo()).addValue("payer", verified.holderNm().trim())
                    .addValue("bank", request.getBankCd()).addValue("enc", cipher.encrypt(request.getAccountNo()))
                    .addValue("mask", AccountCipher.mask(request.getAccountNo())).addValue("consent", request.getConsentMethod())
                    .addValue("now", now).addValue("ref", request.getConsentRef().trim()).addValue("emp", normalizeEmpId(empId))
                    .addValue("tx", globalTxId));
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
        Contract contract = contractRepository.findForUpdateByContractNo(contractNo == null ? "" : contractNo)
                .filter(c -> isReadable(c, ownerEmpId)).orElseThrow(ContractSupport::contractNotFound);
        MapSqlParameterSource p = new MapSqlParameterSource("c", contract.getContractNo());
        Integer inFlight = jdbc.queryForObject("SELECT COUNT(*) FROM TB_CMS_DEBIT WHERE CONTRACT_NO = :c AND STAT_CD IN ('10', '20')", p, Integer.class);
        if (inFlight != null && inFlight > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CMS-005", "출금이 진행 중이라 해지할 수 없습니다. 출금 결과가 나온 뒤 해지해 주세요.");
        }
        int updated = jdbc.update("""
                UPDATE TB_CMS_MANDATE SET STAT_CD = '90', CANCEL_DT = :now, CANCEL_EMP_ID = :emp
                 WHERE CONTRACT_NO = :c AND STAT_CD IN ('10', '20', '30')""",
                p.addValue("now", LocalDateTime.now(clock)).addValue("emp", normalizeEmpId(empId)));
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
        Contract contract = contractRepository.findById(contractNo == null ? "" : contractNo)
                .filter(c -> isReadable(c, ownerEmpId)).orElseThrow(ContractSupport::contractNotFound);
        MapSqlParameterSource p = new MapSqlParameterSource("c", contract.getContractNo());
        AutoDebitResponse.Mandate mandate = jdbc.query("SELECT * FROM TB_CMS_MANDATE WHERE CONTRACT_NO = :c ORDER BY MANDATE_ID DESC LIMIT 1", p,
                (rs, i) -> AutoDebitResponse.Mandate.builder()
                        .mandateId(rs.getLong("MANDATE_ID")).statCd(rs.getString("STAT_CD")).payerNm(rs.getString("PAYER_NM"))
                        .bankCd(rs.getString("BANK_CD")).accountMask(rs.getString("ACCOUNT_MASK"))
                        .consentMethod(rs.getString("CONSENT_METHOD")).consentDt(rs.getObject("CONSENT_DT", LocalDateTime.class))
                        .consentRef(rs.getString("CONSENT_REF")).resultCd(rs.getString("RESULT_CD")).resultMsg(rs.getString("RESULT_MSG"))
                        .inheritedFrom(rs.getString("INHERITED_FROM")).regEmpId(rs.getString("REG_EMP_ID"))
                        .regDt(rs.getObject("REG_DT", LocalDateTime.class)).doneDt(rs.getObject("DONE_DT", LocalDateTime.class))
                        .cancelDt(rs.getObject("CANCEL_DT", LocalDateTime.class)).build()).stream().findFirst().orElse(null);
        List<AutoDebitResponse.Debit> debits = jdbc.query("SELECT * FROM TB_CMS_DEBIT WHERE CONTRACT_NO = :c ORDER BY DEBIT_ID DESC LIMIT 20", p,
                (rs, i) -> AutoDebitResponse.Debit.builder()
                        .debitId(rs.getLong("DEBIT_ID")).debitDt(rs.getObject("DEBIT_DT", LocalDate.class)).amount(rs.getBigDecimal("AMOUNT"))
                        .attemptNo(rs.getInt("ATTEMPT_NO")).statCd(rs.getString("STAT_CD")).resultCd(rs.getString("RESULT_CD"))
                        .resultMsg(rs.getString("RESULT_MSG")).paymentId((Long) rs.getObject("PAYMENT_ID")).build());
        return AutoDebitResponse.builder().contractNo(contract.getContractNo()).mandate(mandate).debits(debits).build();
    }

    /**
     * 갱신 계약에 원 계약의 자동이체(등록 완료)를 이어 준다. 동의와 계좌는 그대로, 결제원 등록은 새 납부자번호로 다시 의뢰(대기 10)한다.
     * 갱신 재전송에도 한 번만 만들어지도록 거래 UUID 를 정해 두고 충돌은 무시한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void inheritForRenewal(String sourceContractNo, String renewalContractNo, String empId) {
        int n = jdbc.update("""
                INSERT INTO TB_CMS_MANDATE (CONTRACT_NO, PAYER_NM, BANK_CD, ACCOUNT_ENC, ACCOUNT_MASK, CONSENT_METHOD, CONSENT_DT, CONSENT_REF,
                                            STAT_CD, INHERITED_FROM, REG_EMP_ID, REG_DT, GLOBAL_TX_ID)
                SELECT :renewal, PAYER_NM, BANK_CD, ACCOUNT_ENC, ACCOUNT_MASK, CONSENT_METHOD, CONSENT_DT, CONSENT_REF,
                       '10', CONTRACT_NO, :emp, :now, 'INH-' || :renewal
                  FROM TB_CMS_MANDATE WHERE CONTRACT_NO = :source AND STAT_CD = '30'
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource("renewal", renewalContractNo).addValue("source", sourceContractNo)
                .addValue("emp", normalizeEmpId(empId)).addValue("now", LocalDateTime.now(clock)));
        if (n > 0) {
            log.info("갱신 계약에 자동이체 승계: {} → {}", sourceContractNo, renewalContractNo);
        }
    }

    private boolean activeExists(String contractNo) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM TB_CMS_MANDATE WHERE CONTRACT_NO = :c AND STAT_CD IN ('10', '20', '30')",
                new MapSqlParameterSource("c", contractNo), Integer.class);
        return n != null && n > 0;
    }
}
