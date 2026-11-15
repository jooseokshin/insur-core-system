package com.insur.insurcoresystem.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.insur.insurcoresystem.service.payout.PayoutSender;
import com.insur.insurcoresystem.service.stats.ReserveIbnrFixture;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 스프링 배치 작업을 실제 DB 로 실행한다. 배치는 자기 트랜잭션과 실행 이력(BATCH_*)을 커밋하므로 이 시험은 트랜잭션으로 감싸지 않고
 * 만든 자료를 끝나면 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchJobIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PayoutSender payoutSender;

    private final RequestPostProcessor admin = TestAuth.as("E-ADM-B", Roles.ADMIN);
    private final RequestPostProcessor operator = TestAuth.as("E-OP-B", Roles.OPERATOR);
    private final List<String> contracts = new ArrayList<>();
    private final List<String> traces = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM TB_RSV_CLOSE_DTL WHERE CLOSE_DT = ?", ReserveIbnrFixture.EVAL);
        jdbc.update("DELETE FROM TB_RSV_CLOSE WHERE CLOSE_DT = ?", ReserveIbnrFixture.EVAL);
        deleteBatchRuns("reserveCloseJob", "closeDate", ReserveIbnrFixture.EVAL.toString());
        for (String trace : traces) {
            jdbc.update("DELETE FROM TB_PAY_RECON WHERE TRACE_NO = ?", trace);
            jdbc.update("DELETE FROM TB_PAY_RECON_BANK WHERE TRACE_NO = ?", trace);
            jdbc.update("DELETE FROM TB_SIM_BANK_TRANSFER WHERE TRACE_NO = ?", trace);
        }
        for (String no : contracts) {
            jdbc.update("DELETE FROM TB_PAY_OUT WHERE CONTRACT_NO = ?", no);
            ReserveIbnrFixture.delete(jdbc, no);
        }
        traces.clear();
        contracts.clear();
    }

    private ResultActions call(String path, RequestPostProcessor auth, String body) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-b-" + UUID.randomUUID() + "\" }, \"body\": " + body + " }"));
    }

    @Test
    void 분기_결산은_종목별_지급준비금을_확정하고_같은_기준일은_다시_결산하지_않는다() throws Exception {
        contracts.add(ReserveIbnrFixture.insert(jdbc));
        String body = "{ \"closeDate\": \"" + ReserveIbnrFixture.EVAL + "\" }";
        call("/api/batch/reserve-close", operator, body).andExpect(status().isForbidden());
        call("/api/batch/reserve-close", admin, "{ \"closeDate\": \"2001-12-30\" }")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-BAT-001"));

        long execId = ((Number) JsonPath.read(call("/api/batch/reserve-close", admin, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.status").value("COMPLETED"))
                .andExpect(jsonPath("$.body.jobName").value("reserveCloseJob"))
                .andExpect(jsonPath("$.body.steps[0].stepName").value("reserveCloseStep"))
                .andReturn().getResponse().getContentAsString(), "$.body.executionId")).longValue();

        // 손 계산: 개별추산액 8,000 + IBNR 1,268 (2001Q4 는 BF) = 9,268 (ReserveIbnrFixture). 결산은 분기 12개라 앞 기간은 0
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM TB_RSV_CLOSE WHERE CLOSE_DT = ? AND INSR_CLS_CD = '98'", ReserveIbnrFixture.EVAL);
        assertThat(((Number) row.get("case_rsv")).longValue()).isEqualTo(8_000);
        assertThat(((Number) row.get("ibnr_amt")).longValue()).isEqualTo(1_268);
        assertThat(((Number) row.get("total_rsv")).longValue()).isEqualTo(9_268);
        assertThat(((Number) row.get("paid_amt")).longValue()).isEqualTo(52_000);
        assertThat(((Number) row.get("job_execution_id")).longValue()).isEqualTo(execId);
        assertThat(row.get("close_emp_id")).isEqualTo("E-ADM-B");
        assertThat(jdbc.queryForObject("SELECT METHOD FROM TB_RSV_CLOSE_DTL WHERE CLOSE_DT = ? AND INSR_CLS_CD = '98' AND ACDT_PERIOD = '2001Q4'",
                String.class, ReserveIbnrFixture.EVAL)).isEqualTo("BF");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TB_RSV_CLOSE_DTL WHERE CLOSE_DT = ? AND INSR_CLS_CD = '98'",
                Integer.class, ReserveIbnrFixture.EVAL)).isEqualTo(12);

        // 같은 기준일은 스프링 배치가 거절한다 (식별 파라미터가 같은 완료 인스턴스)
        call("/api/batch/reserve-close", admin, body)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-BAT-002"));
        call("/api/batch/reserve-close-list", TestAuth.as("E-UW-B", Roles.UNDERWRITER), body)
                .andExpect(status().isOk()).andExpect(jsonPath("$.body[?(@.insrClsCd == '98')].totalRsv").value(9268));
        call("/api/batch/executions", operator, "{ \"jobName\": \"reserveCloseJob\", \"limit\": 5 }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body[0].executionId").value(execId))
                .andExpect(jsonPath("$.body[0].params").value("closeDate=" + ReserveIbnrFixture.EVAL));
    }

    @Test
    void 일일_대사는_은행_이체_내역과_지급_원장의_차이를_찾는다() throws Exception {
        String contractNo = contract();
        String normal = payout(contractNo, 150_000, "11122233344", "홍길동");
        String lost = payout(contractNo, 70_000, "55566677777", "김지급");   // 은행은 이체하고 응답을 잃는 계좌
        payoutSender.processDue();
        assertThat(stat(normal)).isEqualTo("30");
        assertThat(stat(lost)).isEqualTo("70");
        // 은행에만 있는 이체 (우리 원장에 없는 건)
        String bankOnly = "XB" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        traces.add(bankOnly);
        jdbc.update("INSERT INTO TB_SIM_BANK_TRANSFER (TRACE_NO, BANK_CD, ACCOUNT_NO, AMOUNT, RECIPIENT_NM, PROC_DT) VALUES (?, '004', '11122233344', 1000, '홍길동', NOW())",
                bankOnly);
        // 우리는 완료로 알지만 은행에 없는 건
        String oursOnly = "XO" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        traces.add(oursOnly);
        jdbc.update("""
                INSERT INTO TB_PAY_OUT (SRC_TYPE, SRC_NO, CONTRACT_NO, PAY_AMT, BANK_CD, ACCOUNT_ENC, ACCOUNT_MASK, HOLDER_NM, STAT_CD, TRACE_NO,
                                        NEXT_TRY_DT, REQ_DT, SENT_DT, DONE_DT, GLOBAL_TX_ID)
                VALUES ('CLAIM', ?, ?, 5000, '004', 'x', '111*****344', '홍길동', '30', ?, NOW(), NOW(), NOW(), NOW(), ?)
                """, "CLM-X-" + oursOnly, contractNo, oursOnly, "tx-" + oursOnly);

        String body = "{ \"reconDate\": \"" + TODAY + "\" }";
        call("/api/batch/payout-recon", TestAuth.as("E-CLM-B", Roles.CLAIM), body).andExpect(status().isForbidden());
        call("/api/batch/payout-recon", operator, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.status").value("COMPLETED"))
                .andExpect(jsonPath("$.body.steps[0].stepName").value("bankFetchStep"))
                .andExpect(jsonPath("$.body.steps[1].stepName").value("reconCompareStep"));
        assertThat(result(normal)).isEqualTo("MATCHED");
        assertThat(result(lost)).isEqualTo("STATUS_DIFF");   // 은행은 이체했는데 우리는 결과 불명: 그냥 재전송했으면 이중 지급
        assertThat(result(bankOnly)).isEqualTo("BANK_ONLY");
        assertThat(result(oursOnly)).isEqualTo("OURS_ONLY");

        // 결과 조회가 결과 불명을 해소하고, 은행 금액이 바뀐 건은 다시 대사하면 금액 차이로 잡힌다 (같은 날도 다시 실행할 수 있다)
        jdbc.update("UPDATE TB_PAY_OUT SET NEXT_TRY_DT = NOW() - INTERVAL '1 minute' WHERE TRACE_NO = ?", lost);
        payoutSender.processDue();
        assertThat(stat(lost)).isEqualTo("30");
        jdbc.update("UPDATE TB_SIM_BANK_TRANSFER SET AMOUNT = 149000 WHERE TRACE_NO = ?", normal);
        call("/api/batch/payout-recon", operator, body).andExpect(jsonPath("$.body.status").value("COMPLETED"));
        assertThat(result(lost)).isEqualTo("MATCHED");
        assertThat(result(normal)).isEqualTo("AMOUNT_DIFF");
        call("/api/batch/payout-recon-list", operator, "{ \"reconDate\": \"" + TODAY + "\", \"diffOnly\": true }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[?(@.traceNo == '" + normal + "')].resultCd").value("AMOUNT_DIFF"))
                .andExpect(jsonPath("$.body[?(@.traceNo == '" + lost + "')]").isEmpty());
        call("/api/batch/payout-recon-list", operator, "{ \"reconDate\": \"" + TODAY + "\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[?(@.traceNo == '" + lost + "')].resultCd").value("MATCHED"));
        // 은행 이체는 한 번뿐이다 (결과 불명을 재전송하지 않았다)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TB_SIM_BANK_TRANSFER WHERE TRACE_NO = ?", Integer.class, lost)).isEqualTo(1);
    }

    private String contract() {
        String no = "BAT-" + UUID.randomUUID().toString().substring(0, 8);
        contracts.add(no);
        jdbc.update("""
                INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, CTR_STAT_CD, START_DATE, END_DATE, POLICYHOLDER_ID, INSURED_ID,
                                        TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID)
                VALUES (?, ?, '01', '20', ?, ?, 'C1', 'C1', 500000, NOW(), 'E-AG-B')
                """, no, "APP-" + no, TODAY.minusDays(100), TODAY.plusDays(265));
        return no;
    }

    /** 지급 결정(40)된 사고를 만들고 API 로 지급을 요청한 뒤 거래고유번호를 돌려준다 */
    private String payout(String contractNo, long amount, String accountNo, String holder) throws Exception {
        String clmNo = "CLM-BAT-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("""
                INSERT INTO TB_CLM (CLM_NO, CONTRACT_NO, CLM_STAT_CD, ACDT_DT, ACDT_DESC, CLAIMANT_ID, TOTAL_CLAIM_AMT, TOTAL_PAY_AMT,
                                    RPT_DT, DECIDE_DT, GLOBAL_TX_ID, REQ_HASH)
                VALUES (?, ?, '40', ?, '대사 시험', 'C1', ?, ?, NOW(), NOW(), ?, 'h')
                """, clmNo, contractNo, TODAY.minusDays(3), amount, amount, clmNo);
        String res = call("/api/payout/request", TestAuth.as("E-CLM-B", Roles.CLAIM), "{ \"srcType\": \"CLAIM\", \"srcNo\": \"" + clmNo
                + "\", \"bankCd\": \"004\", \"accountNo\": \"" + accountNo + "\", \"holderNm\": \"" + holder + "\" }")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String trace = JsonPath.read(res, "$.body.traceNo");
        traces.add(trace);
        return trace;
    }

    private String stat(String trace) {
        return jdbc.queryForObject("SELECT STAT_CD FROM TB_PAY_OUT WHERE TRACE_NO = ?", String.class, trace);
    }

    private String result(String trace) {
        return jdbc.queryForObject("SELECT RESULT_CD FROM TB_PAY_RECON WHERE RECON_DT = ? AND TRACE_NO = ?", String.class, TODAY, trace);
    }

    private void deleteBatchRuns(String job, String param, String value) {
        BatchTestSupport.deleteBatchRuns(jdbc, job, param, value);
    }
}
