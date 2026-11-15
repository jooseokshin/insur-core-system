package com.insur.insurcoresystem.batch;

import com.insur.insurcoresystem.common.security.AccountCipher;
import com.insur.insurcoresystem.domain.contract.ContractPayment;
import com.insur.insurcoresystem.ext.cms.CmsFileCodec;
import com.insur.insurcoresystem.ext.cms.CmsFileTransport;
import com.insur.insurcoresystem.mapper.cms.CmsMapper;
import com.insur.insurcoresystem.mapper.contract.ContractPaymentMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.batch.MyBatisCursorItemReader;
import org.mybatis.spring.batch.builder.MyBatisCursorItemReaderBuilder;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 보험료 자동이체(CMS) 일일 배치 (cmsDebitJob). 식별 파라미터는 출금일(debitDate) 하나: 출금일당 한 번만 완료되고,
 * 실패하면 같은 출금일로 다시 실행해 <b>실패한 단계부터</b> 재시작한다(이미 보낸 파일을 다시 보내지 않는다).
 * <pre>
 * 1 cmsRegisterSendStep   (태스크릿) 등록 대기(10) 출금 동의를 등록 의뢰 파일(EB11)로 보내고 의뢰함(20)
 * 2 cmsRegisterResultStep (태스크릿) 등록 결과 파일(EB12)을 받아 검증하고 등록 완료(30)·불능(80) 반영. 파일이 아직 없으면 실패
 * 3 cmsDebitSelectStep    (청크 100) 출금 대상 선정: 청약(10) 계약 + 등록 완료 + 남은 보험료 > 0 + 진행 중 출금 없음 + 실패 3번 미만 +
 *                                     마지막 시도가 이 출금일 전 → 출금 의뢰 대상(10). 조건에 안 맞는 건은 처리 단계에서 걸러낸다
 * 4 cmsDebitSendStep      (태스크릿) 출금 의뢰 파일(EB21)을 보내고 결과 대기(20)
 * 5 cmsDebitResultStep    (태스크릿) 출금 결과 파일(EB22)을 받아 검증하고 성공은 수납(CMS) 기록(30), 실패(잔액 부족 등)는 80
 * </pre>
 * 실무에서는 의뢰와 결과 사이에 하루 이상 걸려 의뢰 작업과 결과 작업을 나눈다. 여기서는 한 작업으로 두고, 결과 파일이 늦으면
 * 2·5 단계가 실패했다가 재시작으로 이어지는 방식으로 같은 상황을 다룬다. 영업일(휴일) 계산은 하지 않는다.
 */
@Slf4j
@Configuration
public class CmsDebitJobConfig {

    public static final String JOB = "cmsDebitJob";
    /** 같은 등록으로 출금을 시도하는 최대 횟수 (실패하면 다음 출금일에 다시) */
    public static final int MAX_ATTEMPTS = 3;
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 출금 대상 후보 */
    public record DueContract(String contractNo, long mandateId, BigDecimal remaining, int fails, LocalDate lastDebitDt) {
    }

    /** 출금 의뢰 대상 */
    public record DebitRow(String contractNo, long mandateId, BigDecimal amount, int attemptNo) {
    }

    @Bean
    Job cmsDebitJob(JobRepository jobRepository, Step cmsRegisterSendStep, Step cmsRegisterResultStep, Step cmsDebitSelectStep,
                    Step cmsDebitSendStep, Step cmsDebitResultStep) {
        return new JobBuilder(JOB, jobRepository)
                .start(cmsRegisterSendStep).next(cmsRegisterResultStep).next(cmsDebitSelectStep)
                .next(cmsDebitSendStep).next(cmsDebitResultStep).build();
    }

    // 1. 등록 의뢰 ------------------------------------------------------------------------------------------
    @Bean
    Step cmsRegisterSendStep(JobRepository jobRepository, PlatformTransactionManager tm, Tasklet cmsRegisterSendTasklet) {
        return new StepBuilder("cmsRegisterSendStep", jobRepository).tasklet(cmsRegisterSendTasklet, tm).build();
    }

    @Bean
    @StepScope
    Tasklet cmsRegisterSendTasklet(CmsMapper cmsMapper, CmsFileTransport transport, AccountCipher cipher, Clock clock,
                                   @Value("${app.ext.cms.org-code:9900000001}") String orgCode,
                                   @Value("#{jobParameters['debitDate']}") LocalDate debitDate,
                                   @Value("#{stepExecution.jobExecutionId}") Long execId) {
        return (contribution, ctx) -> {
            List<CmsMapper.PendingMandate> pending = cmsMapper.lockPendingMandates();
            if (pending.isEmpty()) {
                return RepeatStatus.FINISHED;
            }
            List<Map<String, Object>> records = new ArrayList<>();
            for (CmsMapper.PendingMandate m : pending) {
                Map<String, Object> r = new HashMap<>();
                r.put("payerNo", m.contractNo());
                r.put("bankCd", m.bankCd());
                r.put("accountNo", cipher.decrypt(m.accountEnc()));
                r.put("payerNm", m.payerNm());
                r.put("consent", m.consentMethod().substring(0, 1));
                r.put("consentDate", m.consentDt().toLocalDate().format(YMD));
                records.add(r);
            }
            String file = CmsFileCodec.build(CmsFileCodec.EB11, debitDate, orgCode, records);
            transport.send(CmsFileCodec.EB11, debitDate, file);
            logFile(cmsMapper, clock, CmsFileCodec.EB11, debitDate, "SEND", records.size(), 0, file, execId);
            cmsMapper.markMandatesSent(pending.stream().map(CmsMapper.PendingMandate::mandateId).toList());
            contribution.incrementReadCount();
            contribution.incrementWriteCount(records.size());
            return RepeatStatus.FINISHED;
        };
    }

    // 2. 등록 결과 ------------------------------------------------------------------------------------------
    @Bean
    Step cmsRegisterResultStep(JobRepository jobRepository, PlatformTransactionManager tm, Tasklet cmsRegisterResultTasklet) {
        return new StepBuilder("cmsRegisterResultStep", jobRepository).tasklet(cmsRegisterResultTasklet, tm).build();
    }

    @Bean
    @StepScope
    Tasklet cmsRegisterResultTasklet(CmsMapper cmsMapper, CmsFileTransport transport, Clock clock,
                                     @Value("#{jobParameters['debitDate']}") LocalDate debitDate,
                                     @Value("#{stepExecution.jobExecutionId}") Long execId) {
        return (contribution, ctx) -> {
            if (cmsMapper.countMandatesSent() == 0) {
                return RepeatStatus.FINISHED;
            }
            String content = transport.receive(CmsFileCodec.EB12, debitDate)
                    .orElseThrow(() -> new IllegalStateException("등록 결과 파일(EB12)이 아직 오지 않았습니다. 받은 뒤 같은 출금일로 다시 실행하면 이 단계부터 이어서 합니다."));
            CmsFileCodec.CmsFile file = CmsFileCodec.parse(CmsFileCodec.EB12, debitDate, content);
            logFile(cmsMapper, clock, CmsFileCodec.EB12, debitDate, "RECV", file.records().size(), 0, content, execId);
            LocalDateTime now = LocalDateTime.now(clock);
            for (Map<String, String> r : file.records()) {
                String code = r.get("resultCd");
                int n = cmsMapper.applyMandateResult(r.get("payerNo"), CmsFileCodec.OK.equals(code) ? "30" : "80", code,
                        "결제원 등록 " + CmsFileCodec.describe(code), now);
                if (n == 0) {
                    log.warn("등록 결과에 맞는 의뢰 건이 없습니다: 납부자번호={}", r.get("payerNo"));
                }
                contribution.incrementWriteCount(n);
            }
            contribution.incrementReadCount();
            return RepeatStatus.FINISHED;
        };
    }

    // 3. 출금 대상 선정 ---------------------------------------------------------------------------------------
    @Bean
    Step cmsDebitSelectStep(JobRepository jobRepository, PlatformTransactionManager tm, MyBatisCursorItemReader<DueContract> cmsDueReader,
                            ItemProcessor<DueContract, DebitRow> cmsDueProcessor, ItemWriter<DebitRow> cmsDebitWriter) {
        return new StepBuilder("cmsDebitSelectStep", jobRepository)
                .<DueContract, DebitRow>chunk(100)
                .transactionManager(tm)
                .reader(cmsDueReader)
                .processor(cmsDueProcessor)
                .writer(cmsDebitWriter)
                .build();
    }

    @Bean
    @StepScope
    MyBatisCursorItemReader<DueContract> cmsDueReader(SqlSessionFactory sqlSessionFactory) {
        return new MyBatisCursorItemReaderBuilder<DueContract>()
                .sqlSessionFactory(sqlSessionFactory)
                .queryId("com.insur.insurcoresystem.mapper.cms.CmsMapper.dueContracts")
                .build();
    }

    @Bean
    @StepScope
    ItemProcessor<DueContract, DebitRow> cmsDueProcessor(@Value("#{jobParameters['debitDate']}") LocalDate debitDate) {
        return due -> {
            if (due.remaining().signum() <= 0 || due.fails() >= MAX_ATTEMPTS
                    || (due.lastDebitDt() != null && !due.lastDebitDt().isBefore(debitDate))) {
                return null;   // 완납, 재출금 한도 초과, 이미 이 날짜(또는 이후)에 시도함
            }
            return new DebitRow(due.contractNo(), due.mandateId(), due.remaining(), due.fails() + 1);
        };
    }

    @Bean
    @StepScope
    ItemWriter<DebitRow> cmsDebitWriter(CmsMapper cmsMapper, Clock clock, @Value("#{jobParameters['debitDate']}") LocalDate debitDate,
                                        @Value("#{stepExecution.jobExecutionId}") Long execId) {
        return chunk -> {
            LocalDateTime now = LocalDateTime.now(clock);
            for (DebitRow d : chunk.getItems()) {
                cmsMapper.insertDebit(debitDate, d, execId, now);
            }
        };
    }

    // 4. 출금 의뢰 ------------------------------------------------------------------------------------------
    @Bean
    Step cmsDebitSendStep(JobRepository jobRepository, PlatformTransactionManager tm, Tasklet cmsDebitSendTasklet) {
        return new StepBuilder("cmsDebitSendStep", jobRepository).tasklet(cmsDebitSendTasklet, tm).build();
    }

    @Bean
    @StepScope
    Tasklet cmsDebitSendTasklet(CmsMapper cmsMapper, CmsFileTransport transport, AccountCipher cipher, Clock clock,
                                @Value("${app.ext.cms.org-code:9900000001}") String orgCode,
                                @Value("#{jobParameters['debitDate']}") LocalDate debitDate,
                                @Value("#{stepExecution.jobExecutionId}") Long execId) {
        return (contribution, ctx) -> {
            List<CmsMapper.DebitToSend> rows = cmsMapper.lockDebitsToSend(debitDate);
            if (rows.isEmpty()) {
                return RepeatStatus.FINISHED;
            }
            List<Map<String, Object>> records = new ArrayList<>();
            long total = 0;
            for (CmsMapper.DebitToSend r : rows) {
                long amount = r.amount().longValueExact();
                total += amount;
                records.add(Map.of("payerNo", r.contractNo(), "bankCd", r.bankCd(), "accountNo", cipher.decrypt(r.accountEnc()),
                        "amount", amount));
            }
            String file = CmsFileCodec.build(CmsFileCodec.EB21, debitDate, orgCode, records);
            transport.send(CmsFileCodec.EB21, debitDate, file);
            logFile(cmsMapper, clock, CmsFileCodec.EB21, debitDate, "SEND", records.size(), total, file, execId);
            cmsMapper.markDebitsSent(debitDate);
            contribution.incrementReadCount();
            contribution.incrementWriteCount(records.size());
            log.info("CMS 출금 의뢰: 출금일={}, {}건, {}원", debitDate, records.size(), total);
            return RepeatStatus.FINISHED;
        };
    }

    // 5. 출금 결과 ------------------------------------------------------------------------------------------
    @Bean
    Step cmsDebitResultStep(JobRepository jobRepository, PlatformTransactionManager tm, Tasklet cmsDebitResultTasklet) {
        return new StepBuilder("cmsDebitResultStep", jobRepository).tasklet(cmsDebitResultTasklet, tm).build();
    }

    @Bean
    @StepScope
    Tasklet cmsDebitResultTasklet(CmsMapper cmsMapper, ContractPaymentMapper paymentMapper, CmsFileTransport transport, Clock clock,
                                  @Value("#{jobParameters['debitDate']}") LocalDate debitDate,
                                  @Value("#{stepExecution.jobExecutionId}") Long execId) {
        return (contribution, ctx) -> {
            if (cmsMapper.countDebitsSent(debitDate) == 0) {
                return RepeatStatus.FINISHED;
            }
            String content = transport.receive(CmsFileCodec.EB22, debitDate)
                    .orElseThrow(() -> new IllegalStateException("출금 결과 파일(EB22)이 아직 오지 않았습니다. 받은 뒤 같은 출금일로 다시 실행하면 이 단계부터 이어서 합니다."));
            CmsFileCodec.CmsFile file = CmsFileCodec.parse(CmsFileCodec.EB22, debitDate, content);
            logFile(cmsMapper, clock, CmsFileCodec.EB22, debitDate, "RECV", file.records().size(), file.totalAmount(), content, execId);
            LocalDateTime now = LocalDateTime.now(clock);
            int paid = 0;
            for (Map<String, String> r : file.records()) {
                var debit = cmsMapper.lockSentDebit(debitDate, r.get("payerNo"));
                if (debit.isEmpty()) {
                    log.warn("출금 결과에 맞는 의뢰 건이 없습니다: 납부자번호={}", r.get("payerNo"));
                    continue;
                }
                long debitId = debit.get().debitId();
                long debited = Long.parseLong(r.get("debited"));
                String code = r.get("resultCd");
                if (CmsFileCodec.OK.equals(code) && debited > 0) {
                    // 출금된 돈은 이미 우리 계좌에 들어왔으므로 수납으로 남긴다 (계약 상태가 바뀌었어도 남기고 환불은 따로 한다)
                    ContractPayment payment = ContractPayment.builder()
                            .contractNo(r.get("payerNo")).payAmt(BigDecimal.valueOf(debited)).payMethod(ContractPayment.METHOD_CMS)
                            .empId("cms").globalTxId("CMS-" + debitId).paidDt(now)
                            .build();
                    paymentMapper.insert(payment);
                    cmsMapper.markDebitPaid(debitId, code,
                            debited == debit.get().amount().longValue() ? "출금 성공" : "부분 출금 " + debited + "원",
                            payment.getPaymentId(), now);
                    paid++;
                } else {
                    cmsMapper.markDebitFailed(debitId, code, "출금 실패: " + CmsFileCodec.describe(code), now);
                }
                contribution.incrementWriteCount(1);
            }
            contribution.incrementReadCount();
            log.info("CMS 출금 결과: 출금일={}, {}건 중 성공 {}건, 출금 합계 {}원", debitDate, file.records().size(), paid, file.totalAmount());
            return RepeatStatus.FINISHED;
        };
    }

    private static void logFile(CmsMapper cmsMapper, Clock clock, String type, LocalDate date, String direction, int count,
                                long total, String content, Long execId) {
        byte[] bytes = content.getBytes(com.insur.insurcoresystem.ext.FixedLengthCodec.CHARSET);
        cmsMapper.insertFileLog(type, date, direction, count, total, bytes.length, sha256(bytes), execId, LocalDateTime.now(clock));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
