package com.insur.insurcoresystem.batch;

import com.insur.insurcoresystem.common.security.AccountCipher;
import com.insur.insurcoresystem.ext.cms.CmsFileCodec;
import com.insur.insurcoresystem.ext.cms.CmsFileTransport;
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
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
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
    Tasklet cmsRegisterSendTasklet(NamedParameterJdbcTemplate jdbc, CmsFileTransport transport, AccountCipher cipher, Clock clock,
                                   @Value("${app.ext.cms.org-code:9900000001}") String orgCode,
                                   @Value("#{jobParameters['debitDate']}") LocalDate debitDate,
                                   @Value("#{stepExecution.jobExecutionId}") Long execId) {
        return (contribution, ctx) -> {
            List<Map<String, Object>> pending = jdbc.queryForList(
                    "SELECT * FROM TB_CMS_MANDATE WHERE STAT_CD = '10' ORDER BY MANDATE_ID FOR UPDATE", new MapSqlParameterSource());
            if (pending.isEmpty()) {
                return RepeatStatus.FINISHED;
            }
            List<Map<String, Object>> records = new ArrayList<>();
            for (Map<String, Object> m : pending) {
                Map<String, Object> r = new HashMap<>();
                r.put("payerNo", m.get("contract_no"));
                r.put("bankCd", m.get("bank_cd"));
                r.put("accountNo", cipher.decrypt((String) m.get("account_enc")));
                r.put("payerNm", m.get("payer_nm"));
                r.put("consent", ((String) m.get("consent_method")).substring(0, 1));
                r.put("consentDate", ((java.sql.Timestamp) m.get("consent_dt")).toLocalDateTime().toLocalDate().format(YMD));
                records.add(r);
            }
            String file = CmsFileCodec.build(CmsFileCodec.EB11, debitDate, orgCode, records);
            transport.send(CmsFileCodec.EB11, debitDate, file);
            logFile(jdbc, clock, CmsFileCodec.EB11, debitDate, "SEND", records.size(), 0, file, execId);
            jdbc.update("UPDATE TB_CMS_MANDATE SET STAT_CD = '20' WHERE STAT_CD = '10' AND MANDATE_ID IN (:ids)",
                    new MapSqlParameterSource("ids", pending.stream().map(m -> m.get("mandate_id")).toList()));
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
    Tasklet cmsRegisterResultTasklet(NamedParameterJdbcTemplate jdbc, CmsFileTransport transport, Clock clock,
                                     @Value("#{jobParameters['debitDate']}") LocalDate debitDate,
                                     @Value("#{stepExecution.jobExecutionId}") Long execId) {
        return (contribution, ctx) -> {
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM TB_CMS_MANDATE WHERE STAT_CD = '20'", new MapSqlParameterSource(), Integer.class);
            if (waiting == null || waiting == 0) {
                return RepeatStatus.FINISHED;
            }
            String content = transport.receive(CmsFileCodec.EB12, debitDate)
                    .orElseThrow(() -> new IllegalStateException("등록 결과 파일(EB12)이 아직 오지 않았습니다. 받은 뒤 같은 출금일로 다시 실행하면 이 단계부터 이어서 합니다."));
            CmsFileCodec.CmsFile file = CmsFileCodec.parse(CmsFileCodec.EB12, debitDate, content);
            logFile(jdbc, clock, CmsFileCodec.EB12, debitDate, "RECV", file.records().size(), 0, content, execId);
            LocalDateTime now = LocalDateTime.now(clock);
            for (Map<String, String> r : file.records()) {
                String code = r.get("resultCd");
                int n = jdbc.update("""
                        UPDATE TB_CMS_MANDATE SET STAT_CD = :stat, RESULT_CD = :code, RESULT_MSG = :msg, DONE_DT = :now
                         WHERE CONTRACT_NO = :payer AND STAT_CD = '20'""", new MapSqlParameterSource("payer", r.get("payerNo"))
                        .addValue("stat", CmsFileCodec.OK.equals(code) ? "30" : "80").addValue("code", code)
                        .addValue("msg", "결제원 등록 " + CmsFileCodec.describe(code)).addValue("now", now));
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
    Step cmsDebitSelectStep(JobRepository jobRepository, PlatformTransactionManager tm, JdbcCursorItemReader<DueContract> cmsDueReader,
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
    JdbcCursorItemReader<DueContract> cmsDueReader(DataSource dataSource) {
        return new JdbcCursorItemReaderBuilder<DueContract>()
                .name("cmsDueReader")
                .dataSource(dataSource)
                .sql("""
                        SELECT L.CONTRACT_NO, M.MANDATE_ID,
                               L.TOTAL_PREMIUM - COALESCE((SELECT SUM(P.PAY_AMT) FROM TB_CTR_PAYMENT P WHERE P.CONTRACT_NO = L.CONTRACT_NO), 0) AS REMAINING,
                               (SELECT COUNT(*) FROM TB_CMS_DEBIT D WHERE D.MANDATE_ID = M.MANDATE_ID AND D.STAT_CD = '80') AS FAILS,
                               (SELECT MAX(D.DEBIT_DT) FROM TB_CMS_DEBIT D WHERE D.MANDATE_ID = M.MANDATE_ID) AS LAST_DT
                          FROM TB_CTR_LGD L
                          JOIN TB_CMS_MANDATE M ON M.CONTRACT_NO = L.CONTRACT_NO AND M.STAT_CD = '30'
                         WHERE L.CTR_STAT_CD = '10'
                           AND NOT EXISTS (SELECT 1 FROM TB_CMS_DEBIT D WHERE D.CONTRACT_NO = L.CONTRACT_NO AND D.STAT_CD IN ('10', '20'))
                         ORDER BY L.CONTRACT_NO
                        """)
                .rowMapper((rs, i) -> new DueContract(rs.getString("CONTRACT_NO"), rs.getLong("MANDATE_ID"), rs.getBigDecimal("REMAINING"),
                        rs.getInt("FAILS"), rs.getObject("LAST_DT", LocalDate.class)))
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
    ItemWriter<DebitRow> cmsDebitWriter(NamedParameterJdbcTemplate jdbc, Clock clock, @Value("#{jobParameters['debitDate']}") LocalDate debitDate,
                                        @Value("#{stepExecution.jobExecutionId}") Long execId) {
        return chunk -> {
            LocalDateTime now = LocalDateTime.now(clock);
            for (DebitRow d : chunk.getItems()) {
                jdbc.update("""
                        INSERT INTO TB_CMS_DEBIT (DEBIT_DT, MANDATE_ID, CONTRACT_NO, AMOUNT, ATTEMPT_NO, STAT_CD, JOB_EXECUTION_ID, CREATED_DT)
                        VALUES (:d, :m, :c, :amount, :attempt, '10', :exec, :now)
                        ON CONFLICT (CONTRACT_NO, DEBIT_DT) DO NOTHING
                        """, new MapSqlParameterSource("d", debitDate).addValue("m", d.mandateId()).addValue("c", d.contractNo())
                        .addValue("amount", d.amount()).addValue("attempt", d.attemptNo()).addValue("exec", execId).addValue("now", now));
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
    Tasklet cmsDebitSendTasklet(NamedParameterJdbcTemplate jdbc, CmsFileTransport transport, AccountCipher cipher, Clock clock,
                                @Value("${app.ext.cms.org-code:9900000001}") String orgCode,
                                @Value("#{jobParameters['debitDate']}") LocalDate debitDate,
                                @Value("#{stepExecution.jobExecutionId}") Long execId) {
        return (contribution, ctx) -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT D.DEBIT_ID, D.CONTRACT_NO, D.AMOUNT, M.BANK_CD, M.ACCOUNT_ENC
                      FROM TB_CMS_DEBIT D JOIN TB_CMS_MANDATE M ON M.MANDATE_ID = D.MANDATE_ID
                     WHERE D.DEBIT_DT = :d AND D.STAT_CD = '10' ORDER BY D.DEBIT_ID FOR UPDATE OF D
                    """, new MapSqlParameterSource("d", debitDate));
            if (rows.isEmpty()) {
                return RepeatStatus.FINISHED;
            }
            List<Map<String, Object>> records = new ArrayList<>();
            long total = 0;
            for (Map<String, Object> r : rows) {
                long amount = ((BigDecimal) r.get("amount")).longValueExact();
                total += amount;
                records.add(Map.of("payerNo", r.get("contract_no"), "bankCd", r.get("bank_cd"),
                        "accountNo", cipher.decrypt((String) r.get("account_enc")), "amount", amount));
            }
            String file = CmsFileCodec.build(CmsFileCodec.EB21, debitDate, orgCode, records);
            transport.send(CmsFileCodec.EB21, debitDate, file);
            logFile(jdbc, clock, CmsFileCodec.EB21, debitDate, "SEND", records.size(), total, file, execId);
            jdbc.update("UPDATE TB_CMS_DEBIT SET STAT_CD = '20' WHERE DEBIT_DT = :d AND STAT_CD = '10'", new MapSqlParameterSource("d", debitDate));
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
    Tasklet cmsDebitResultTasklet(NamedParameterJdbcTemplate jdbc, CmsFileTransport transport, Clock clock,
                                  @Value("#{jobParameters['debitDate']}") LocalDate debitDate,
                                  @Value("#{stepExecution.jobExecutionId}") Long execId) {
        return (contribution, ctx) -> {
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM TB_CMS_DEBIT WHERE DEBIT_DT = :d AND STAT_CD = '20'",
                    new MapSqlParameterSource("d", debitDate), Integer.class);
            if (waiting == null || waiting == 0) {
                return RepeatStatus.FINISHED;
            }
            String content = transport.receive(CmsFileCodec.EB22, debitDate)
                    .orElseThrow(() -> new IllegalStateException("출금 결과 파일(EB22)이 아직 오지 않았습니다. 받은 뒤 같은 출금일로 다시 실행하면 이 단계부터 이어서 합니다."));
            CmsFileCodec.CmsFile file = CmsFileCodec.parse(CmsFileCodec.EB22, debitDate, content);
            logFile(jdbc, clock, CmsFileCodec.EB22, debitDate, "RECV", file.records().size(), file.totalAmount(), content, execId);
            LocalDateTime now = LocalDateTime.now(clock);
            int paid = 0;
            for (Map<String, String> r : file.records()) {
                MapSqlParameterSource key = new MapSqlParameterSource("d", debitDate).addValue("c", r.get("payerNo"));
                List<Map<String, Object>> debit = jdbc.queryForList(
                        "SELECT DEBIT_ID, AMOUNT FROM TB_CMS_DEBIT WHERE DEBIT_DT = :d AND CONTRACT_NO = :c AND STAT_CD = '20' FOR UPDATE", key);
                if (debit.isEmpty()) {
                    log.warn("출금 결과에 맞는 의뢰 건이 없습니다: 납부자번호={}", r.get("payerNo"));
                    continue;
                }
                long debitId = ((Number) debit.get(0).get("debit_id")).longValue();
                long debited = Long.parseLong(r.get("debited"));
                String code = r.get("resultCd");
                if (CmsFileCodec.OK.equals(code) && debited > 0) {
                    // 출금된 돈은 이미 우리 계좌에 들어왔으므로 수납으로 남긴다 (계약 상태가 바뀌었어도 남기고 환불은 따로 한다)
                    Long paymentId = jdbc.queryForObject("""
                            INSERT INTO TB_CTR_PAYMENT (CONTRACT_NO, PAY_AMT, PAY_METHOD, EMP_ID, GLOBAL_TX_ID, PAID_DT)
                            VALUES (:c, :amount, 'CMS', 'cms', :tx, :now) RETURNING PAYMENT_ID
                            """, new MapSqlParameterSource("c", r.get("payerNo")).addValue("amount", debited)
                            .addValue("tx", "CMS-" + debitId).addValue("now", now), Long.class);
                    jdbc.update("""
                            UPDATE TB_CMS_DEBIT SET STAT_CD = '30', RESULT_CD = :code, RESULT_MSG = :msg, PAYMENT_ID = :pay, DONE_DT = :now
                             WHERE DEBIT_ID = :id""", new MapSqlParameterSource("id", debitId).addValue("code", code)
                            .addValue("msg", debited == ((BigDecimal) debit.get(0).get("amount")).longValue() ? "출금 성공" : "부분 출금 " + debited + "원")
                            .addValue("pay", paymentId).addValue("now", now));
                    paid++;
                } else {
                    jdbc.update("""
                            UPDATE TB_CMS_DEBIT SET STAT_CD = '80', RESULT_CD = :code, RESULT_MSG = :msg, DONE_DT = :now WHERE DEBIT_ID = :id""",
                            new MapSqlParameterSource("id", debitId).addValue("code", code)
                                    .addValue("msg", "출금 실패: " + CmsFileCodec.describe(code)).addValue("now", now));
                }
                contribution.incrementWriteCount(1);
            }
            contribution.incrementReadCount();
            log.info("CMS 출금 결과: 출금일={}, {}건 중 성공 {}건, 출금 합계 {}원", debitDate, file.records().size(), paid, file.totalAmount());
            return RepeatStatus.FINISHED;
        };
    }

    private static void logFile(NamedParameterJdbcTemplate jdbc, Clock clock, String type, LocalDate date, String direction, int count,
                                long total, String content, Long execId) {
        byte[] bytes = content.getBytes(com.insur.insurcoresystem.ext.FixedLengthCodec.CHARSET);
        jdbc.update("""
                INSERT INTO TB_CMS_FILE (FILE_TYPE, FILE_DT, DIRECTION, RECORD_COUNT, TOTAL_AMT, BYTES, FILE_HASH, JOB_EXECUTION_ID, CREATED_DT)
                VALUES (:type, :d, :dir, :count, :total, :bytes, :hash, :exec, :now)
                """, new MapSqlParameterSource("type", type).addValue("d", date).addValue("dir", direction).addValue("count", count)
                .addValue("total", total).addValue("bytes", bytes.length).addValue("hash", sha256(bytes)).addValue("exec", execId)
                .addValue("now", LocalDateTime.now(clock)));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
