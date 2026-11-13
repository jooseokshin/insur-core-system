package com.insur.insurcoresystem.batch;

import com.insur.insurcoresystem.common.security.AccountCipher;
import com.insur.insurcoresystem.ext.bank.BankClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 일일 지급 대사 배치 (payoutReconJob). 은행이 실제로 이체한 내역과 우리 지급 원장을 거래고유번호로 맞춰 본다.
 * 은행 응답만 믿으면 놓치는 사고(응답 유실, 은행 쪽 중복 처리, 우리 쪽 상태 오류)를 하루 단위로 잡는 마지막 안전장치다.
 * 대사는 다시 해야 하는 일이 많아서(은행 내역 늦게 도착 등) 같은 대사일도 다시 실행할 수 있게 run.id 를 식별 파라미터로 붙인다.
 * <pre>
 * bankFetchStep (태스크릿): 그날 대사 결과와 은행 내역 중간 표를 지우고, 은행에서 그날 이체 내역을 받아 중간 표에 담는다 (계좌는 마스킹)
 * reconCompareStep (청크 100): 우리 지급(그날 처음 보낸 건 + 은행 내역에 나온 건)과 은행 내역을 거래고유번호로 FULL OUTER JOIN 해서
 *     MATCHED / OURS_ONLY(우리는 완료, 은행 없음) / BANK_ONLY / AMOUNT_DIFF / STATUS_DIFF(은행은 이체, 우리는 결과 불명·실패 = 이중 지급 위험)
 *     로 나눠 쓴다. 양쪽 모두 이체가 없는 건(실패·미처리)은 처리 단계에서 걸러낸다(FILTER_COUNT).
 * </pre>
 * 대사는 비교와 보고만 한다. 지급 상태는 바꾸지 않는다(결과 불명 해소는 결과 조회가 한다).
 */
@Configuration
public class PayoutReconJobConfig {

    public static final String JOB = "payoutReconJob";

    /** 비교 대상 한 줄 */
    public record ReconSource(String traceNo, Long payoutId, String ourStat, BigDecimal ourAmt, BigDecimal bankAmt) {
    }

    /** 대사 결과 한 줄 */
    public record ReconRow(String traceNo, Long payoutId, String result, String ourStat, BigDecimal ourAmt, BigDecimal bankAmt) {
    }

    @Bean
    Job payoutReconJob(JobRepository jobRepository, Step bankFetchStep, Step reconCompareStep) {
        return new JobBuilder(JOB, jobRepository).start(bankFetchStep).next(reconCompareStep).build();
    }

    @Bean
    Step bankFetchStep(JobRepository jobRepository, PlatformTransactionManager transactionManager, Tasklet bankFetchTasklet) {
        return new StepBuilder("bankFetchStep", jobRepository).tasklet(bankFetchTasklet, transactionManager).build();
    }

    @Bean
    @StepScope
    Tasklet bankFetchTasklet(NamedParameterJdbcTemplate jdbc, BankClient bank, @Value("#{jobParameters['reconDate']}") LocalDate reconDate) {
        return (contribution, chunkContext) -> {
            MapSqlParameterSource day = new MapSqlParameterSource("d", reconDate);
            jdbc.update("DELETE FROM TB_PAY_RECON WHERE RECON_DT = :d", day);
            jdbc.update("DELETE FROM TB_PAY_RECON_BANK WHERE RECON_DT = :d", day);
            for (BankClient.BankTransfer t : bank.transfers(reconDate)) {
                jdbc.update("""
                        INSERT INTO TB_PAY_RECON_BANK (RECON_DT, TRACE_NO, BANK_CD, ACCOUNT_MASK, AMOUNT, PROC_DT)
                        VALUES (:d, :trace, :bank, :mask, :amount, :proc)
                        """, new MapSqlParameterSource(day.getValues()).addValue("trace", t.traceNo()).addValue("bank", t.bankCd())
                        .addValue("mask", AccountCipher.mask(t.accountNo())).addValue("amount", t.amount()).addValue("proc", t.procDt()));
                contribution.incrementReadCount();
                contribution.incrementWriteCount(1);
            }
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    Step reconCompareStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                          JdbcCursorItemReader<ReconSource> reconReader, ItemProcessor<ReconSource, ReconRow> reconProcessor,
                          ItemWriter<ReconRow> reconWriter) {
        return new StepBuilder("reconCompareStep", jobRepository)
                .<ReconSource, ReconRow>chunk(100)
                .transactionManager(transactionManager)
                .reader(reconReader)
                .processor(reconProcessor)
                .writer(reconWriter)
                .build();
    }

    @Bean
    @StepScope
    JdbcCursorItemReader<ReconSource> reconReader(DataSource dataSource, @Value("#{jobParameters['reconDate']}") LocalDate reconDate) {
        return new JdbcCursorItemReaderBuilder<ReconSource>()
                .name("reconReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT COALESCE(P.TRACE_NO, B.TRACE_NO) AS TRACE_NO, P.PAYOUT_ID, P.STAT_CD, P.PAY_AMT, B.AMOUNT AS BANK_AMT
                          FROM (SELECT * FROM TB_PAY_OUT
                                 WHERE (SENT_DT >= ? AND SENT_DT < ?)
                                    OR TRACE_NO IN (SELECT TRACE_NO FROM TB_PAY_RECON_BANK WHERE RECON_DT = ?)) P
                          FULL OUTER JOIN (SELECT * FROM TB_PAY_RECON_BANK WHERE RECON_DT = ?) B ON B.TRACE_NO = P.TRACE_NO
                         ORDER BY 1
                        """)
                .preparedStatementSetter(ps -> {
                    ps.setObject(1, reconDate.atStartOfDay());
                    ps.setObject(2, reconDate.plusDays(1).atStartOfDay());
                    ps.setObject(3, reconDate);
                    ps.setObject(4, reconDate);
                })
                .rowMapper((rs, i) -> new ReconSource(rs.getString("TRACE_NO"), (Long) rs.getObject("PAYOUT_ID"), rs.getString("STAT_CD"),
                        rs.getBigDecimal("PAY_AMT"), rs.getBigDecimal("BANK_AMT")))
                .build();
    }

    @Bean
    ItemProcessor<ReconSource, ReconRow> reconProcessor() {
        return s -> {
            String result;
            if (s.payoutId() == null) {
                result = "BANK_ONLY";
            } else if (s.bankAmt() == null) {
                if (!"30".equals(s.ourStat())) {
                    return null;   // 양쪽 모두 이체 없음 (실패·미처리·결과 불명): 대사할 차이가 없다
                }
                result = "OURS_ONLY";
            } else if (s.ourAmt().compareTo(s.bankAmt()) != 0) {
                result = "AMOUNT_DIFF";
            } else {
                result = "30".equals(s.ourStat()) ? "MATCHED" : "STATUS_DIFF";
            }
            return new ReconRow(s.traceNo(), s.payoutId(), result, s.ourStat(), s.ourAmt(), s.bankAmt());
        };
    }

    @Bean
    @StepScope
    ItemWriter<ReconRow> reconWriter(NamedParameterJdbcTemplate jdbc, Clock clock, @Value("#{jobParameters['reconDate']}") LocalDate reconDate,
                                     @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        return chunk -> {
            LocalDateTime now = LocalDateTime.now(clock);
            for (ReconRow r : chunk.getItems()) {
                jdbc.update("""
                        INSERT INTO TB_PAY_RECON (RECON_DT, TRACE_NO, PAYOUT_ID, RESULT_CD, OUR_STAT_CD, OUR_AMT, BANK_AMT, JOB_EXECUTION_ID, CREATED_DT)
                        VALUES (:d, :trace, :payout, :result, :stat, :ours, :bank, :exec, :now)
                        """, new MapSqlParameterSource("d", reconDate).addValue("trace", r.traceNo())
                        .addValue("payout", r.payoutId(), java.sql.Types.BIGINT).addValue("result", r.result())
                        .addValue("stat", r.ourStat(), java.sql.Types.VARCHAR).addValue("ours", r.ourAmt(), java.sql.Types.NUMERIC)
                        .addValue("bank", r.bankAmt(), java.sql.Types.NUMERIC).addValue("exec", jobExecutionId).addValue("now", now));
            }
        };
    }
}
