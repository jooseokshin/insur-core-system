package com.insur.insurcoresystem.mapper.sim;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * 모의 기관(개발·시험 전용) 표 매퍼: 신용정보원(TB_SIM_KCIS_*), 은행(TB_SIM_BANK_*), 금융결제원 CMS(TB_SIM_CMS_*).
 */
public interface SimulatorMapper {

    // ---- 신용정보원 ----

    /** 피보험자의 기준일 유효 실손 계약 (보험사, 증권번호 순) */
    List<KcisContract> findKcisContracts(@Param("insuredId") String insuredId, @Param("baseDate") LocalDate baseDate);

    /** 계약정보 집중(신규). 같은 (보험사, 증권번호)가 있으면 무시 */
    int insertKcisContract(@Param("insuredId") String insuredId, @Param("c") KcisContract contract);

    int updateKcisContractEnd(@Param("insurerCd") String insurerCd, @Param("policyNo") String policyNo,
                              @Param("endDate") LocalDate endDate);

    void insertKcisReceipt(@Param("txType") String txType, @Param("traceNo") String traceNo, @Param("message") String message,
                           @Param("receivedDt") LocalDateTime receivedDt);

    // ---- 은행 ----

    Optional<BankAccount> findBankAccount(@Param("bankCd") String bankCd, @Param("accountNo") String accountNo);

    Optional<BankTransfer> findBankTransfer(String traceNo);

    void insertBankTransfer(BankTransfer transfer);

    /** 하루 이체 내역 (추적번호 순, LIMIT·OFFSET) */
    List<BankTransfer> findBankTransfers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                         @Param("limit") int limit, @Param("offset") int offset);

    int debitBankAccount(@Param("bankCd") String bankCd, @Param("accountNo") String accountNo, @Param("amount") long amount);

    // ---- 금융결제원 CMS ----

    /** 출금 동의 등록. 같은 (이용기관, 납부자번호)면 계좌를 바꾼다 */
    void upsertCmsMandate(@Param("orgCode") String orgCode, @Param("payerNo") String payerNo, @Param("bankCd") String bankCd,
                          @Param("accountNo") String accountNo, @Param("regDt") LocalDateTime regDt);

    boolean existsCmsMandate(@Param("orgCode") String orgCode, @Param("payerNo") String payerNo, @Param("bankCd") String bankCd,
                             @Param("accountNo") String accountNo);

    /** 결과 파일 저장. 같은 (종류, 일자)면 덮어쓴다 */
    void upsertCmsFile(@Param("fileType") String fileType, @Param("fileDt") LocalDate fileDt, @Param("content") String content,
                       @Param("createdDt") LocalDateTime createdDt);

    Optional<String> findCmsFile(@Param("fileType") String fileType, @Param("fileDt") LocalDate fileDt);

    record KcisContract(String insurerCd, String policyNo, String kindCd, LocalDate startDate, LocalDate endDate,
                        boolean coveredYn, boolean nonCoveredYn) {
    }

    /** 모의 계좌. statCd: NORMAL, CLOSED, FROZEN */
    record BankAccount(String holderNm, String statCd, Long balance) {
    }

    record BankTransfer(String traceNo, String bankCd, String accountNo, long amount, String recipientNm, LocalDateTime procDt) {
    }
}
