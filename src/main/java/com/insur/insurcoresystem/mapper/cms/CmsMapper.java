package com.insur.insurcoresystem.mapper.cms;

import com.insur.insurcoresystem.service.cms.dto.AutoDebitResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import org.apache.ibatis.annotations.Param;

/**
 * 보험료 자동이체(CMS) 매퍼: 출금 동의(TB_CMS_MANDATE), 출금(TB_CMS_DEBIT), 파일 기록(TB_CMS_FILE).
 */
public interface CmsMapper {

    // ---- 출금 동의 ----

    /** 등록 거래 UUID 로 이미 받은 동의의 증권번호 (재전송 판별) */
    Optional<String> findMandateContractByTx(String globalTxId);

    /** 대기(10)·의뢰(20)·등록(30) 동의가 있는지 */
    boolean existsActiveMandate(String contractNo);

    void insertMandate(NewMandate mandate);

    /** 진행 중인 동의를 해지(90)한다 */
    int cancelActiveMandate(@Param("contractNo") String contractNo, @Param("now") LocalDateTime now, @Param("empId") String empId);

    /** 계약의 최근 동의 */
    Optional<AutoDebitResponse.Mandate> findLatestMandate(String contractNo);

    /** 계약의 최근 출금 20건 */
    List<AutoDebitResponse.Debit> findRecentDebits(String contractNo);

    /** 진행 중인 출금(의뢰 대상·결과 대기) 건수 */
    long countDebitsInFlight(String contractNo);

    /**
     * 원 계약의 등록 완료(30) 동의를 갱신 계약에 대기(10)로 잇는다. 거래 UUID 를 'INH-갱신증권번호' 로 정해 두어 재전송에도 한 번만 만든다.
     *
     * @return 만든 건수 (이미 있으면 0)
     */
    int inheritMandate(@Param("sourceContractNo") String sourceContractNo, @Param("renewalContractNo") String renewalContractNo,
                       @Param("empId") String empId, @Param("now") LocalDateTime now);

    // ---- 출금 배치 (cmsDebitJob) ----

    /** 등록 대기(10) 동의를 잠그고 가져온다 */
    List<PendingMandate> lockPendingMandates();

    /** 등록 대기(10) 중 주어진 동의를 의뢰함(20)으로 */
    int markMandatesSent(@Param("mandateIds") List<Long> mandateIds);

    long countMandatesSent();

    /** 결제원 등록 결과 반영 (의뢰함(20) 건만) */
    int applyMandateResult(@Param("contractNo") String contractNo, @Param("statCd") String statCd, @Param("resultCd") String resultCd,
                           @Param("resultMsg") String resultMsg, @Param("now") LocalDateTime now);

    /**
     * 출금 대상 후보 (커서로 읽는다): 청약(10) 계약 + 등록 완료(30) 동의 + 진행 중 출금 없음. 남은 보험료·실패 횟수·마지막 출금일은 처리 단계가 본다.
     */
    List<com.insur.insurcoresystem.batch.CmsDebitJobConfig.DueContract> dueContracts();

    /** 출금 의뢰 대상(10)을 넣는다. 같은 (계약, 출금일)이 있으면 무시 */
    int insertDebit(@Param("debitDt") LocalDate debitDt, @Param("d") com.insur.insurcoresystem.batch.CmsDebitJobConfig.DebitRow debit,
                    @Param("jobExecutionId") long jobExecutionId, @Param("now") LocalDateTime now);

    /** 출금일의 의뢰 대상(10)을 잠그고 계좌와 함께 가져온다 */
    List<DebitToSend> lockDebitsToSend(LocalDate debitDt);

    int markDebitsSent(LocalDate debitDt);

    long countDebitsSent(LocalDate debitDt);

    /** 결과 대기(20) 출금 한 건을 잠그고 가져온다 */
    Optional<SentDebit> lockSentDebit(@Param("debitDt") LocalDate debitDt, @Param("contractNo") String contractNo);

    int markDebitPaid(@Param("debitId") long debitId, @Param("resultCd") String resultCd, @Param("resultMsg") String resultMsg,
                      @Param("paymentId") long paymentId, @Param("now") LocalDateTime now);

    int markDebitFailed(@Param("debitId") long debitId, @Param("resultCd") String resultCd, @Param("resultMsg") String resultMsg,
                        @Param("now") LocalDateTime now);

    /** 결제원 파일 기록 (본문은 남기지 않고 건수·합계·크기·SHA-256 만) */
    void insertFileLog(@Param("fileType") String fileType, @Param("fileDt") LocalDate fileDt, @Param("direction") String direction,
                       @Param("recordCount") int recordCount, @Param("totalAmt") long totalAmt, @Param("bytes") int bytes,
                       @Param("fileHash") String fileHash, @Param("jobExecutionId") long jobExecutionId,
                       @Param("createdDt") LocalDateTime createdDt);

    /** 출금일 현황: 출금 건 (처리 중·실패 먼저) */
    List<com.insur.insurcoresystem.batch.BatchService.CmsDebitRow> findDayDebits(LocalDate debitDt);

    /** 출금일 현황: 파일 기록 */
    List<com.insur.insurcoresystem.batch.BatchService.CmsFileRow> findDayFiles(LocalDate debitDt);

    record PendingMandate(long mandateId, String contractNo, String bankCd, String accountEnc, String payerNm, String consentMethod,
                          LocalDateTime consentDt) {
    }

    record DebitToSend(long debitId, String contractNo, BigDecimal amount, String bankCd, String accountEnc) {
    }

    record SentDebit(long debitId, BigDecimal amount) {
    }

    @Builder
    record NewMandate(String contractNo, String payerNm, String bankCd, String accountEnc, String accountMask, String consentMethod,
                      LocalDateTime now, String consentRef, String regEmpId, String globalTxId) {
    }
}
