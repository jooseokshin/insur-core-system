package com.insur.insurcoresystem.mapper.batch;

import com.insur.insurcoresystem.batch.BatchService;
import com.insur.insurcoresystem.batch.PayoutReconJobConfig;
import com.insur.insurcoresystem.service.stats.dto.IbnrResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * 배치 매퍼: 실행 이력 조회(스프링 배치 BATCH_*), 지급준비금 결산(TB_RSV_CLOSE, TB_RSV_CLOSE_DTL), 지급 대사(TB_PAY_RECON, TB_PAY_RECON_BANK).
 * 출금 배치의 SQL 은 {@link com.insur.insurcoresystem.mapper.cms.CmsMapper} 에 있다.
 */
public interface BatchMapper {

    // ---- 실행 이력 ----

    /** 작업 실행 번호 (최근 순). jobName 이 null 이면 전체 */
    List<Long> findExecutionIds(@Param("jobName") String jobName, @Param("limit") int limit);

    Optional<ExecutionHead> findExecution(long jobExecutionId);

    List<BatchService.StepSummary> findSteps(long jobExecutionId);

    /** 식별 파라미터 "이름=값" (이름 순) */
    List<String> findIdentifyingParams(long jobExecutionId);

    // ---- 지급준비금 결산 ----

    /** 유효·해지 계약이 있는 보험종목 */
    List<String> findClassesWithContracts();

    int deleteCloseDetails(@Param("closeDt") LocalDate closeDt, @Param("insrClsCd") String insrClsCd);

    int deleteClose(@Param("closeDt") LocalDate closeDt, @Param("insrClsCd") String insrClsCd);

    void insertClose(@Param("closeDt") LocalDate closeDt, @Param("insrClsCd") String insrClsCd, @Param("r") IbnrResponse result,
                     @Param("periodCount") int periodCount, @Param("jobExecutionId") long jobExecutionId, @Param("now") LocalDateTime now, @Param("empId") String empId);

    void insertCloseDetail(@Param("closeDt") LocalDate closeDt, @Param("insrClsCd") String insrClsCd, @Param("p") IbnrResponse.Period period);

    /** 결산 결과. closeDt 가 null 이면 최근 결산 8번 */
    List<BatchService.CloseRow> findCloses(@Param("closeDt") LocalDate closeDt);

    // ---- 지급 대사 ----

    int deleteRecon(LocalDate reconDt);

    int deleteReconBank(LocalDate reconDt);

    void insertReconBank(@Param("reconDt") LocalDate reconDt, @Param("traceNo") String traceNo, @Param("bankCd") String bankCd,
                         @Param("accountMask") String accountMask, @Param("amount") long amount, @Param("procDt") LocalDateTime procDt);

    /**
     * 대사 비교 대상 (커서로 읽는다): 그날 처음 보낸 지급과 은행 내역에 나온 지급을 은행 내역과 거래고유번호로 FULL OUTER JOIN.
     * 매개변수 reconDt, dayStart, dayEnd
     */
    List<PayoutReconJobConfig.ReconSource> reconSources(@Param("reconDt") LocalDate reconDt,
                                                        @Param("dayStart") LocalDateTime dayStart,
                                                        @Param("dayEnd") LocalDateTime dayEnd);

    void insertRecon(@Param("reconDt") LocalDate reconDt, @Param("r") PayoutReconJobConfig.ReconRow row,
                     @Param("jobExecutionId") long jobExecutionId, @Param("now") LocalDateTime now);

    /** 대사 결과 (차이 건이 먼저) */
    List<BatchService.ReconResult> findReconResults(@Param("reconDt") LocalDate reconDt, @Param("diffOnly") boolean diffOnly);

    record ExecutionHead(String jobName, String status, String exitCode, String exitMessage, LocalDateTime startTime,
                         LocalDateTime endTime) {
    }
}
