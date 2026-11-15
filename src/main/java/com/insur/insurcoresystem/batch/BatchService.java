package com.insur.insurcoresystem.batch;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.mapper.batch.BatchMapper;
import com.insur.insurcoresystem.mapper.cms.CmsMapper;
import com.insur.insurcoresystem.service.stats.ReserveService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 배치 실행과 결과 조회. 화면·예약 작업 모두 이 서비스로 배치를 시작한다(동기 실행: 끝날 때까지 기다렸다가 결과를 돌려준다).
 * 실행 이력은 스프링 배치 표(BATCH_*)에서 읽는다.
 */
@Slf4j
@Service
public class BatchService {

    private final JobOperator jobOperator;
    private final Job reserveCloseJob;
    private final Job payoutReconJob;
    private final Job cmsDebitJob;
    private final BatchMapper batchMapper;
    private final CmsMapper cmsMapper;
    private final Clock clock;

    public BatchService(JobOperator jobOperator, @Qualifier("reserveCloseJob") Job reserveCloseJob,
                        @Qualifier("payoutReconJob") Job payoutReconJob, @Qualifier("cmsDebitJob") Job cmsDebitJob,
                        BatchMapper batchMapper, CmsMapper cmsMapper, Clock clock) {
        this.jobOperator = jobOperator;
        this.reserveCloseJob = reserveCloseJob;
        this.payoutReconJob = payoutReconJob;
        this.cmsDebitJob = cmsDebitJob;
        this.batchMapper = batchMapper;
        this.cmsMapper = cmsMapper;
        this.clock = clock;
    }

    /** 배치 실행 결과 */
    public record Execution(long executionId, String jobName, String params, String status, String exitCode, String exitMessage,
                            LocalDateTime startTime, LocalDateTime endTime, List<StepSummary> steps) {
    }

    public record StepSummary(String stepName, String status, long readCount, long writeCount, long filterCount, long commitCount) {
    }

    /** 결산 결과 한 줄 */
    public record CloseRow(LocalDate closeDt, String insrClsCd, BigDecimal paidAmt, BigDecimal caseRsv, BigDecimal ibnrCl,
                           BigDecimal ibnrBf, BigDecimal ibnrAmt, BigDecimal totalRsv, BigDecimal elr, long jobExecutionId,
                           LocalDateTime closeTm, String closeEmpId) {
    }

    /** 대사 결과 한 줄 */
    public record ReconResult(LocalDate reconDt, String traceNo, Long payoutId, String resultCd, String ourStatCd, BigDecimal ourAmt,
                              BigDecimal bankAmt, String srcType, String srcNo, long jobExecutionId) {
    }

    /**
     * 분기 지급준비금 결산을 실행한다.
     *
     * @throws BusinessException 분기 말일이 아니거나 미래(E-BAT-001), 이미 결산함(E-BAT-002, 409), 실행 중(E-BAT-003, 409)
     */
    public Execution runReserveClose(LocalDate closeDate, String empId) {
        if (closeDate == null || !ReserveService.isQuarterEnd(closeDate) || closeDate.isAfter(LocalDate.now(clock))) {
            throw new BusinessException("E-BAT-001", "결산 기준일은 오늘 이전의 분기 말일(3/31, 6/30, 9/30, 12/31)이어야 합니다.");
        }
        return launch(reserveCloseJob, new JobParametersBuilder()
                .addLocalDate("closeDate", closeDate)
                .addString("empId", empId, false)
                .toJobParameters(), "이미 결산한 기준일입니다. 확정한 결산은 고치지 않고 다음 결산에서 정정합니다.");
    }

    /**
     * 지급 대사를 실행한다. 같은 대사일도 다시 실행할 수 있다.
     *
     * @throws BusinessException 미래 날짜(E-BAT-001), 실행 중(E-BAT-003, 409)
     */
    public Execution runPayoutRecon(LocalDate reconDate, String empId) {
        if (reconDate == null || reconDate.isAfter(LocalDate.now(clock))) {
            throw new BusinessException("E-BAT-001", "대사일은 오늘 이전이어야 합니다.");
        }
        return launch(payoutReconJob, new JobParametersBuilder()
                .addLocalDate("reconDate", reconDate)
                .addLong("run.id", System.currentTimeMillis())
                .addString("empId", empId, false)
                .toJobParameters(), "이미 실행한 대사입니다.");
    }

    /**
     * 보험료 자동이체(CMS) 배치를 실행한다. 출금일당 한 번 완료되고, 실패한 실행은 같은 출금일로 다시 실행하면 실패한 단계부터 재시작한다.
     *
     * @throws BusinessException 날짜 없음·미래(E-BAT-001), 이미 완료(E-BAT-002, 409), 실행 중(E-BAT-003, 409)
     */
    public Execution runCmsDebit(LocalDate debitDate, String empId) {
        if (debitDate == null || debitDate.isAfter(LocalDate.now(clock))) {
            throw new BusinessException("E-BAT-001", "출금일은 오늘 이전이어야 합니다.");
        }
        return launch(cmsDebitJob, new JobParametersBuilder()
                .addLocalDate("debitDate", debitDate)
                .addString("empId", empId, false)
                .toJobParameters(), "이미 처리한 출금일입니다. 실패한 계약은 다음 출금일에 다시 출금합니다.");
    }

    /** 자동이체 하루 현황: 출금 건과 결제원 파일 기록 */
    public record CmsDay(LocalDate debitDate, List<CmsDebitRow> debits, List<CmsFileRow> files) {
    }

    public record CmsDebitRow(long debitId, String contractNo, BigDecimal amount, int attemptNo, String statCd, String resultCd,
                              String resultMsg, Long paymentId, String bankCd, String accountMask, String payerNm) {
    }

    public record CmsFileRow(String fileType, String direction, int recordCount, BigDecimal totalAmt, int bytes, String fileHash,
                             long jobExecutionId, LocalDateTime createdDt) {
    }

    /**
     * 출금일의 자동이체 현황.
     *
     * @throws BusinessException 날짜 없음(E-BAT-001)
     */
    public CmsDay cmsDay(LocalDate debitDate) {
        if (debitDate == null) {
            throw new BusinessException("E-BAT-001", "출금일을 입력해야 합니다.");
        }
        List<CmsDebitRow> debits = cmsMapper.findDayDebits(debitDate);
        List<CmsFileRow> files = cmsMapper.findDayFiles(debitDate);
        return new CmsDay(debitDate, debits, files);
    }

    /** 실행 이력 (최근순) */
    public List<Execution> executions(String jobName, Integer limit) {
        List<Long> ids = batchMapper.findExecutionIds(StringUtils.hasText(jobName) ? jobName : null,
                limit == null ? 20 : Math.max(1, Math.min(limit, 100)));
        return ids.stream().map(this::execution).toList();
    }

    public Execution execution(long id) {
        List<StepSummary> steps = batchMapper.findSteps(id);
        String params = String.join(", ", batchMapper.findIdentifyingParams(id));
        BatchMapper.ExecutionHead head = batchMapper.findExecution(id).orElseThrow();
        String msg = head.exitMessage();
        return new Execution(id, head.jobName(), params, head.status(), head.exitCode(),
                msg == null || msg.isBlank() ? null : msg.length() > 500 ? msg.substring(0, 500) : msg,
                head.startTime(), head.endTime(), steps);
    }

    /** 결산 결과 (기준일을 주지 않으면 최근 결산 8번) */
    public List<CloseRow> reserveCloses(LocalDate closeDate) {
        return batchMapper.findCloses(closeDate);
    }

    /**
     * 대사 결과 (차이 건이 먼저).
     *
     * @throws BusinessException 날짜 없음(E-BAT-001)
     */
    public List<ReconResult> reconResults(LocalDate reconDate, boolean diffOnly) {
        if (reconDate == null) {
            throw new BusinessException("E-BAT-001", "대사일을 입력해야 합니다.");
        }
        return batchMapper.findReconResults(reconDate, diffOnly);
    }

    private Execution launch(Job job, org.springframework.batch.core.job.parameters.JobParameters params, String alreadyMessage) {
        try {
            JobExecution execution = jobOperator.start(job, params);
            log.info("배치 실행: 작업={}, 실행={}, 상태={}", job.getName(), execution.getId(), execution.getStatus());
            return execution(execution.getId());
        } catch (JobInstanceAlreadyCompleteException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-BAT-002", alreadyMessage);
        } catch (JobExecutionAlreadyRunningException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-BAT-003", "같은 배치가 실행 중입니다. 끝난 뒤 다시 실행해 주세요.");
        } catch (Exception e) {
            throw new IllegalStateException("배치를 시작하지 못했습니다: " + e.getMessage(), e);
        }
    }
}
