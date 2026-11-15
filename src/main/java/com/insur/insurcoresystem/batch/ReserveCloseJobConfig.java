package com.insur.insurcoresystem.batch;

import com.insur.insurcoresystem.mapper.batch.BatchMapper;
import com.insur.insurcoresystem.service.stats.ReserveService;
import com.insur.insurcoresystem.service.stats.dto.IbnrResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    ListItemReader<String> reserveClassReader(BatchMapper batchMapper) {
        return new ListItemReader<>(batchMapper.findClassesWithContracts());
    }

    @Bean
    @StepScope
    ItemProcessor<String, ClassClose> reserveCloseProcessor(ReserveService reserveService,
                                                            @Value("#{jobParameters['closeDate']}") LocalDate closeDate) {
        return cls -> new ClassClose(cls, reserveService.compute(cls, closeDate, ReserveService.QUARTER, PERIODS, BigDecimal.ONE, null));
    }

    @Bean
    @StepScope
    ItemWriter<ClassClose> reserveCloseWriter(BatchMapper batchMapper, Clock clock,
                                              @Value("#{jobParameters['closeDate']}") LocalDate closeDate,
                                              @Value("#{jobParameters['empId']}") String empId,
                                              @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        return chunk -> {
            for (ClassClose item : chunk.getItems()) {
                batchMapper.deleteCloseDetails(closeDate, item.insrClsCd());
                batchMapper.deleteClose(closeDate, item.insrClsCd());
                IbnrResponse r = item.result();
                batchMapper.insertClose(closeDate, item.insrClsCd(), r, r.getPeriods().size(), jobExecutionId, LocalDateTime.now(clock), empId);
                for (IbnrResponse.Period p : r.getPeriods()) {
                    batchMapper.insertCloseDetail(closeDate, item.insrClsCd(), p);
                }
            }
        };
    }
}
