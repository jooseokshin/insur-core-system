package com.insur.insurcoresystem.batch;

import com.insur.insurcoresystem.common.exception.BusinessException;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public BatchService(JobOperator jobOperator, @Qualifier("reserveCloseJob") Job reserveCloseJob,
                        @Qualifier("payoutReconJob") Job payoutReconJob, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jobOperator = jobOperator;
        this.reserveCloseJob = reserveCloseJob;
        this.payoutReconJob = payoutReconJob;
        this.jdbc = jdbc;
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

    /** 실행 이력 (최근순) */
    public List<Execution> executions(String jobName, Integer limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit == null ? 20 : Math.max(1, Math.min(limit, 100)));
        String where = "";
        if (StringUtils.hasText(jobName)) {
            where = " WHERE I.JOB_NAME = :job";
            p.addValue("job", jobName);
        }
        List<Long> ids = jdbc.queryForList("SELECT E.JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION E JOIN BATCH_JOB_INSTANCE I"
                + " ON I.JOB_INSTANCE_ID = E.JOB_INSTANCE_ID" + where + " ORDER BY E.JOB_EXECUTION_ID DESC LIMIT :limit", p, Long.class);
        return ids.stream().map(this::execution).toList();
    }

    public Execution execution(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<StepSummary> steps = jdbc.query("""
                SELECT STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, FILTER_COUNT, COMMIT_COUNT FROM BATCH_STEP_EXECUTION
                 WHERE JOB_EXECUTION_ID = :id ORDER BY STEP_EXECUTION_ID""", p,
                (rs, i) -> new StepSummary(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)));
        String params = String.join(", ", jdbc.queryForList("""
                SELECT PARAMETER_NAME || '=' || PARAMETER_VALUE FROM BATCH_JOB_EXECUTION_PARAMS
                 WHERE JOB_EXECUTION_ID = :id AND IDENTIFYING = 'Y' ORDER BY PARAMETER_NAME""", p, String.class));
        return jdbc.query("""
                SELECT I.JOB_NAME, E.STATUS, E.EXIT_CODE, E.EXIT_MESSAGE, E.START_TIME, E.END_TIME
                  FROM BATCH_JOB_EXECUTION E JOIN BATCH_JOB_INSTANCE I ON I.JOB_INSTANCE_ID = E.JOB_INSTANCE_ID
                 WHERE E.JOB_EXECUTION_ID = :id""", p, (rs, i) -> {
            String msg = rs.getString("EXIT_MESSAGE");
            return new Execution(id, rs.getString("JOB_NAME"), params, rs.getString("STATUS"), rs.getString("EXIT_CODE"),
                    msg == null || msg.isBlank() ? null : msg.length() > 500 ? msg.substring(0, 500) : msg,
                    rs.getObject("START_TIME", LocalDateTime.class), rs.getObject("END_TIME", LocalDateTime.class), steps);
        }).get(0);
    }

    /** 결산 결과 (기준일을 주지 않으면 최근 결산 8번) */
    public List<CloseRow> reserveCloses(LocalDate closeDate) {
        MapSqlParameterSource p = new MapSqlParameterSource("d", closeDate);
        String where = closeDate == null
                ? " WHERE CLOSE_DT IN (SELECT DISTINCT CLOSE_DT FROM TB_RSV_CLOSE ORDER BY CLOSE_DT DESC LIMIT 8)"
                : " WHERE CLOSE_DT = :d";
        return jdbc.query("SELECT * FROM TB_RSV_CLOSE" + where + " ORDER BY CLOSE_DT DESC, INSR_CLS_CD", p, (rs, i) -> new CloseRow(
                rs.getObject("CLOSE_DT", LocalDate.class), rs.getString("INSR_CLS_CD"), rs.getBigDecimal("PAID_AMT"), rs.getBigDecimal("CASE_RSV"),
                rs.getBigDecimal("IBNR_CL"), rs.getBigDecimal("IBNR_BF"), rs.getBigDecimal("IBNR_AMT"), rs.getBigDecimal("TOTAL_RSV"),
                rs.getBigDecimal("ELR"), rs.getLong("JOB_EXECUTION_ID"), rs.getObject("CLOSE_TM", LocalDateTime.class), rs.getString("CLOSE_EMP_ID")));
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
        return jdbc.query("""
                SELECT R.*, P.SRC_TYPE, P.SRC_NO FROM TB_PAY_RECON R LEFT JOIN TB_PAY_OUT P ON P.PAYOUT_ID = R.PAYOUT_ID
                 WHERE R.RECON_DT = :d""" + (diffOnly ? " AND R.RESULT_CD <> 'MATCHED'" : "")
                + " ORDER BY CASE WHEN R.RESULT_CD = 'MATCHED' THEN 1 ELSE 0 END, R.TRACE_NO",
                new MapSqlParameterSource("d", reconDate), (rs, i) -> new ReconResult(rs.getObject("RECON_DT", LocalDate.class),
                        rs.getString("TRACE_NO"), (Long) rs.getObject("PAYOUT_ID"), rs.getString("RESULT_CD"), rs.getString("OUR_STAT_CD"),
                        rs.getBigDecimal("OUR_AMT"), rs.getBigDecimal("BANK_AMT"), rs.getString("SRC_TYPE"), rs.getString("SRC_NO"),
                        rs.getLong("JOB_EXECUTION_ID")));
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
