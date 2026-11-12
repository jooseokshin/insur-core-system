package com.insur.insurcoresystem.batch;

import com.insur.insurcoresystem.service.stats.ReserveService;
import com.insur.insurcoresystem.service.stats.dto.IbnrResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 분기 지급준비금 결산 배치 (reserveCloseJob). 식별 파라미터는 결산 기준일(closeDate) 하나라서 같은 기준일은 한 번만 완료된다.
 * 다시 실행하면 스프링 배치가 거절하고(JobInstanceAlreadyComplete), 실패한 실행은 같은 파라미터로 다시 실행하면 재시작된다.
 * <pre>
 * reserveCloseStep (청크 1건 = 보험종목 1개)
 *   읽기: 유효·해지 계약이 있는 보험종목
 *   처리: 종목별 IBNR 산출 (분기 12개, 꼬리 1.0, 기대손해율은 케이프코드) — 화면의 IBNR 산출과 같은 계산
 *   쓰기: TB_RSV_CLOSE(종목 합계) + TB_RSV_CLOSE_DTL(사고 기간별). 재시작에 대비해 같은 기준일·종목 행을 지우고 쓴다
 * </pre>
 * 종목 하나가 한 청크라서 종목별로 커밋된다. 중간에 실패하면 이미 쓴 종목은 남고, 재시작하면 처음부터 다시 읽되 같은 값을 다시 쓴다.
 */
@Configuration
public class ReserveCloseJobConfig {

    public static final String JOB = "reserveCloseJob";
    static final int PERIODS = 12;

    /** 결산 처리 결과 (처리 → 쓰기 사이에 넘긴다) */
    public record ClassClose(String insrClsCd, IbnrResponse result) {
    }

    @Bean
    Job reserveCloseJob(JobRepository jobRepository, Step reserveCloseStep) {
        return new JobBuilder(JOB, jobRepository).start(reserveCloseStep).build();
    }

    @Bean
    Step reserveCloseStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                          ListItemReader<String> reserveClassReader, ItemProcessor<String, ClassClose> reserveCloseProcessor,
                          ItemWriter<ClassClose> reserveCloseWriter) {
        return new StepBuilder("reserveCloseStep", jobRepository)
                .<String, ClassClose>chunk(1)
                .transactionManager(transactionManager)
                .reader(reserveClassReader)
                .processor(reserveCloseProcessor)
                .writer(reserveCloseWriter)
                .build();
    }

    @Bean
    @StepScope
    ListItemReader<String> reserveClassReader(NamedParameterJdbcTemplate jdbc) {
        return new ListItemReader<>(jdbc.queryForList(
                "SELECT DISTINCT INSR_CLS_CD FROM TB_CTR_LGD WHERE CTR_STAT_CD IN ('20', '30') ORDER BY INSR_CLS_CD",
                new MapSqlParameterSource(), String.class));
    }

    @Bean
    @StepScope
    ItemProcessor<String, ClassClose> reserveCloseProcessor(ReserveService reserveService,
                                                            @Value("#{jobParameters['closeDate']}") LocalDate closeDate) {
        return cls -> new ClassClose(cls, reserveService.compute(cls, closeDate, ReserveService.QUARTER, PERIODS, BigDecimal.ONE, null));
    }

    @Bean
    @StepScope
    ItemWriter<ClassClose> reserveCloseWriter(NamedParameterJdbcTemplate jdbc, Clock clock,
                                              @Value("#{jobParameters['closeDate']}") LocalDate closeDate,
                                              @Value("#{jobParameters['empId']}") String empId,
                                              @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        return chunk -> {
            for (ClassClose item : chunk.getItems()) {
                MapSqlParameterSource key = new MapSqlParameterSource("closeDt", closeDate).addValue("cls", item.insrClsCd());
                jdbc.update("DELETE FROM TB_RSV_CLOSE_DTL WHERE CLOSE_DT = :closeDt AND INSR_CLS_CD = :cls", key);
                jdbc.update("DELETE FROM TB_RSV_CLOSE WHERE CLOSE_DT = :closeDt AND INSR_CLS_CD = :cls", key);
                IbnrResponse r = item.result();
                IbnrResponse.Total t = r.getTotal();
                jdbc.update("""
                        INSERT INTO TB_RSV_CLOSE (CLOSE_DT, INSR_CLS_CD, PERIOD_TYPE, PERIOD_COUNT, PAID_AMT, CASE_RSV, IBNR_CL, IBNR_BF,
                                                  IBNR_AMT, TOTAL_RSV, ELR, TAIL_FACTOR, JOB_EXECUTION_ID, CLOSE_TM, CLOSE_EMP_ID)
                        VALUES (:closeDt, :cls, :type, :count, :paid, :case, :cl, :bf, :ibnr, :total, :elr, :tail, :exec, :now, :emp)
                        """, new MapSqlParameterSource(key.getValues())
                        .addValue("type", r.getPeriodType()).addValue("count", r.getPeriods().size()).addValue("paid", t.getPaid())
                        .addValue("case", t.getCaseReserve()).addValue("cl", t.getClIbnr()).addValue("bf", t.getBfIbnr(), java.sql.Types.NUMERIC)
                        .addValue("ibnr", t.getIbnr()).addValue("total", t.getReserve())
                        .addValue("elr", r.getExpectedLossRatio(), java.sql.Types.NUMERIC).addValue("tail", r.getTailFactor())
                        .addValue("exec", jobExecutionId).addValue("now", LocalDateTime.now(clock)).addValue("emp", empId));
                List<IbnrResponse.Period> periods = r.getPeriods();
                for (IbnrResponse.Period p : periods) {
                    jdbc.update("""
                            INSERT INTO TB_RSV_CLOSE_DTL (CLOSE_DT, INSR_CLS_CD, ACDT_PERIOD, PERIOD_FROM, PERIOD_TO, AGE, EARNED_PREM, PAID_AMT,
                                                          CASE_RSV, CDF, CL_ULT, BF_ULT, METHOD, IBNR_AMT)
                            VALUES (:closeDt, :cls, :period, :from, :to, :age, :ep, :paid, :case, :cdf, :cl, :bf, :method, :ibnr)
                            """, new MapSqlParameterSource(key.getValues())
                            .addValue("period", p.getPeriod()).addValue("from", p.getPeriodFrom()).addValue("to", p.getPeriodTo())
                            .addValue("age", p.getAge()).addValue("ep", p.getEarnedPremium()).addValue("paid", p.getPaid())
                            .addValue("case", p.getCaseReserve()).addValue("cdf", p.getCdf()).addValue("cl", p.getClUltimate())
                            .addValue("bf", p.getBfUltimate(), java.sql.Types.NUMERIC).addValue("method", p.getMethod())
                            .addValue("ibnr", p.getIbnr()));
                }
            }
        };
    }
}
