package com.insur.insurcoresystem.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.insur.insurcoresystem.ext.cms.KftcCmsSimulator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 보험료 자동이체 배치(cmsDebitJob): 결제원 등록 → 출금 의뢰 → 결과 반영, 잔액 부족 재출금, 결과 파일 지연 시 실패한 단계부터 재시작.
 * 배치가 커밋하므로 트랜잭션으로 감싸지 않고 만든 자료를 지운다. 다른 자료와 겹치지 않게 2001년 출금일을 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CmsDebitJobIntegrationTest {

    private static final LocalDate D1 = LocalDate.of(2001, 1, 10);
    private static final LocalDate D2 = LocalDate.of(2001, 1, 11);
    private static final LocalDate D3 = LocalDate.of(2001, 1, 12);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KftcCmsSimulator kftc;

    private final RequestPostProcessor operator = TestAuth.as("E-OP-CJ", Roles.OPERATOR);
    private final List<String> contracts = new ArrayList<>();
    private final List<String> accounts = new ArrayList<>();
    private long hongBalance;

    @BeforeEach
    void setUp() {
        cleanUp();
        hongBalance = jdbc.queryForObject("SELECT BALANCE FROM TB_SIM_BANK_ACCOUNT WHERE BANK_CD = '004' AND ACCOUNT_NO = '11122233344'", Long.class);
    }

    @AfterEach
    void tearDown() {
        kftc.setResultDelayed(false);
        jdbc.update("UPDATE TB_SIM_BANK_ACCOUNT SET BALANCE = ? WHERE BANK_CD = '004' AND ACCOUNT_NO = '11122233344'", hongBalance);
        cleanUp();
    }

    private void cleanUp() {
        for (String no : contracts) {
            jdbc.update("DELETE FROM TB_CMS_DEBIT WHERE CONTRACT_NO = ?", no);
            jdbc.update("DELETE FROM TB_CTR_PAYMENT WHERE CONTRACT_NO = ?", no);
            jdbc.update("DELETE FROM TB_CMS_MANDATE WHERE CONTRACT_NO = ?", no);
            jdbc.update("DELETE FROM TB_SIM_CMS_MANDATE WHERE PAYER_NO = ?", no);
            jdbc.update("DELETE FROM TB_CTR_LGD WHERE CONTRACT_NO = ?", no);
        }
        for (String acc : accounts) {
            jdbc.update("DELETE FROM TB_SIM_BANK_ACCOUNT WHERE BANK_CD = '004' AND ACCOUNT_NO = ?", acc);
        }
        contracts.clear();
        accounts.clear();
        for (LocalDate d : List.of(D1, D2, D3)) {
            jdbc.update("DELETE FROM TB_CMS_FILE WHERE FILE_DT = ?", d);
            jdbc.update("DELETE FROM TB_SIM_CMS_FILE WHERE FILE_DT = ?", d);
            BatchTestSupport.deleteBatchRuns(jdbc, CmsDebitJobConfig.JOB, "debitDate", d.toString());
        }
    }

    private String contract(long premium) {
        String no = "CJ-" + UUID.randomUUID().toString().substring(0, 8);
        contracts.add(no);
        jdbc.update("""
                INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, CTR_STAT_CD, START_DATE, END_DATE, POLICYHOLDER_ID, INSURED_ID,
                                        TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID)
                VALUES (?, ?, '01', '10', DATE '2001-02-01', DATE '2002-02-01', 'C1', 'C1', ?, NOW(), 'E-AG-CJ')
                """, no, "APP-" + no, premium);
        return no;
    }

    private String account(String holder, long balance) {
        String acc = "5" + String.format("%010d", (long) (Math.random() * 1e10));
        accounts.add(acc);
        jdbc.update("INSERT INTO TB_SIM_BANK_ACCOUNT (BANK_CD, ACCOUNT_NO, HOLDER_NM, STAT_CD, BALANCE) VALUES ('004', ?, ?, 'ACTIVE', ?)",
                acc, holder, balance);
        return acc;
    }

    private ResultActions call(String path, String body) throws Exception {
        return mockMvc.perform(post(path).with(operator).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-cj-" + UUID.randomUUID() + "\" }, \"body\": " + body + " }"));
    }

    private void register(String contractNo, String accountNo, String payer) throws Exception {
        call("/api/contract/autodebit/register", "{ \"contractNo\": \"" + contractNo + "\", \"bankCd\": \"004\", \"accountNo\": \"" + accountNo
                + "\", \"payerNm\": \"" + payer + "\", \"consentMethod\": \"PAPER\", \"consentRef\": \"APP-FORM-1\" }").andExpect(status().isOk());
    }

    private ResultActions run(LocalDate date) throws Exception {
        return call("/api/batch/cms-debit", "{ \"debitDate\": \"" + date + "\" }");
    }

    private Map<String, Object> debit(String contractNo, LocalDate date) {
        return jdbc.queryForMap("SELECT * FROM TB_CMS_DEBIT WHERE CONTRACT_NO = ? AND DEBIT_DT = ?", contractNo, date);
    }

    private String mandateStat(String contractNo) {
        return jdbc.queryForObject("SELECT STAT_CD FROM TB_CMS_MANDATE WHERE CONTRACT_NO = ? ORDER BY MANDATE_ID DESC LIMIT 1", String.class, contractNo);
    }

    @Test
    void 결제원에_등록하고_남은_보험료를_출금해_수납으로_기록한다() throws Exception {
        String paid = contract(300_000);
        register(paid, "11122233344", "홍길동");
        String poor = contract(50_000);
        String poorAccount = account("박부족", 1_000);
        register(poor, poorAccount, "박부족");
        String frozen = contract(70_000);
        String frozenAccount = account("최동결", 1_000_000);
        register(frozen, frozenAccount, "최동결");
        // 등록과 결제원 등록 사이에 계좌가 지급정지됐다
        jdbc.update("UPDATE TB_SIM_BANK_ACCOUNT SET STAT_CD = 'FROZEN' WHERE ACCOUNT_NO = ?", frozenAccount);

        run(D1).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.status").value("COMPLETED"))
                .andExpect(jsonPath("$.body.steps.length()").value(5));

        assertThat(mandateStat(paid)).isEqualTo("30");
        assertThat(mandateStat(frozen)).isEqualTo("80");
        Map<String, Object> ok = debit(paid, D1);
        assertThat(ok.get("stat_cd")).isEqualTo("30");
        assertThat(jdbc.queryForObject("SELECT PAY_METHOD || ':' || PAY_AMT FROM TB_CTR_PAYMENT WHERE PAYMENT_ID = ?", String.class, ok.get("payment_id")))
                .isEqualTo("CMS:300000");
        Map<String, Object> fail = debit(poor, D1);
        assertThat(fail.get("stat_cd")).isEqualTo("80");
        assertThat(fail.get("result_cd")).isEqualTo("C301");
        assertThat(fail.get("attempt_no")).isEqualTo(1);
        // 파일 기록: 등록 의뢰·결과, 출금 의뢰(2건 350,000)·결과(출금 300,000)
        List<Map<String, Object>> files = jdbc.queryForList("SELECT FILE_TYPE, RECORD_COUNT, TOTAL_AMT FROM TB_CMS_FILE WHERE FILE_DT = ? ORDER BY FILE_ID", D1);
        assertThat(files).extracting(f -> f.get("file_type") + ":" + f.get("record_count") + ":" + ((Number) f.get("total_amt")).longValue())
                .containsExactly("EB11:3:0", "EB12:3:0", "EB21:2:350000", "EB22:2:300000");

        // 같은 출금일은 다시 돌지 않는다
        run(D1).andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-BAT-002"));

        // 다음 출금일: 완납한 계약은 걸러지고, 잔액 부족 계약만 2차 출금 (잔액을 채워 두면 성공)
        jdbc.update("UPDATE TB_SIM_BANK_ACCOUNT SET BALANCE = 100000 WHERE ACCOUNT_NO = ?", poorAccount);
        run(D2).andExpect(jsonPath("$.body.status").value("COMPLETED"));
        Map<String, Object> second = debit(poor, D2);
        assertThat(second.get("attempt_no")).isEqualTo(2);
        assertThat(second.get("stat_cd")).isEqualTo("30");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TB_CMS_DEBIT WHERE CONTRACT_NO = ?", Integer.class, paid)).isEqualTo(1);
        call("/api/contract/autodebit/inquiry", "{ \"contractNo\": \"" + poor + "\" }")
                .andExpect(jsonPath("$.body.debits.length()").value(2))
                .andExpect(jsonPath("$.body.debits[0].resultMsg").value("출금 성공"));
    }

    @Test
    void 결과_파일이_늦으면_실패하고_다시_실행하면_파일을_다시_보내지_않고_결과_단계부터_이어서_한다() throws Exception {
        String no = contract(120_000);
        register(no, "11122233344", "홍길동");
        kftc.setResultDelayed(true);
        run(D1).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.status").value("FAILED"))
                .andExpect(jsonPath("$.body.exitMessage").value(org.hamcrest.Matchers.containsString("EB12")));
        assertThat(mandateStat(no)).isEqualTo("20");   // 등록 의뢰만 하고 결과를 기다리는 중

        kftc.setResultDelayed(false);
        run(D1).andExpect(jsonPath("$.body.status").value("COMPLETED"));
        assertThat(mandateStat(no)).isEqualTo("30");
        assertThat(debit(no, D1).get("stat_cd")).isEqualTo("30");
        // 등록 의뢰 파일(EB11)은 한 번만 보냈다 (완료한 단계는 재시작에서 다시 하지 않는다)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TB_CMS_FILE WHERE FILE_DT = ? AND FILE_TYPE = 'EB11'", Integer.class, D1)).isEqualTo(1);
        // 같은 인스턴스의 실행이 두 번 (실패 → 재시작)
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM BATCH_JOB_EXECUTION E JOIN BATCH_JOB_EXECUTION_PARAMS P ON P.JOB_EXECUTION_ID = E.JOB_EXECUTION_ID
                 WHERE P.PARAMETER_NAME = 'debitDate' AND P.PARAMETER_VALUE = ?""", Integer.class, D1.toString())).isEqualTo(2);
    }

    @Test
    void 출금_결과를_기다리는_동안에는_수기_수납을_막는다() throws Exception {
        String no = contract(80_000);
        register(no, "11122233344", "홍길동");
        run(D1).andExpect(jsonPath("$.body.status").value("COMPLETED"));   // 등록 완료, 출금 성공
        jdbc.update("DELETE FROM TB_CMS_DEBIT WHERE CONTRACT_NO = ?", no);
        jdbc.update("DELETE FROM TB_CTR_PAYMENT WHERE CONTRACT_NO = ?", no);

        kftc.setResultDelayed(true);
        run(D3).andExpect(jsonPath("$.body.status").value("FAILED"));   // 출금 의뢰는 했고 결과(EB22) 대기
        assertThat(debit(no, D3).get("stat_cd")).isEqualTo("20");
        call("/api/contract/payment", "{ \"contractNo\": \"" + no + "\", \"payAmt\": 80000, \"payMethod\": \"CASH\" }")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-031"));
        kftc.setResultDelayed(false);
        run(D3).andExpect(jsonPath("$.body.status").value("COMPLETED"));
        assertThat(debit(no, D3).get("stat_cd")).isEqualTo("30");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TB_CMS_FILE WHERE FILE_DT = ? AND FILE_TYPE = 'EB21'", Integer.class, D3)).isEqualTo(1);
    }
}
