package com.insur.insurcoresystem.service.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.AccountCipher;
import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.insur.insurcoresystem.ext.bank.BankLayouts;
import com.insur.insurcoresystem.ext.bank.BankSimulator;
import com.insur.insurcoresystem.ext.fep.FepAdminService;
import com.insur.insurcoresystem.ext.fep.FepGateway;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 펌뱅킹 지급: 예금주 조회, 지급 요청, 이체·결과 불명·결과 조회의 상태 전이. 은행은 모의 은행(BankSimulator)이 전문으로 답한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PayoutIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PayoutSender sender;
    @Autowired
    BankSimulator bank;
    @Autowired
    FepGateway fep;
    @Autowired
    FepAdminService fepAdmin;
    @Autowired
    AccountCipher cipher;

    private final RequestPostProcessor claimHandler = TestAuth.as("E-CLM-P", Roles.CLAIM);

    @AfterEach
    void restore() {
        bank.setFailAll(false);
        fep.resetCircuit(BankLayouts.INST);
    }

    private ResultActions call(String path, RequestPostProcessor auth, String txId, String body) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": " + body + " }"));
    }

    private String contract() {
        String no = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, CTR_STAT_CD, START_DATE, END_DATE, POLICYHOLDER_ID, INSURED_ID,
                                        TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID)
                VALUES (?, ?, '01', '20', ?, ?, 'C1', 'C1', 500000, NOW(), 'E-AG-P')
                """, no, "APP-" + no, TODAY.minusDays(100), TODAY.plusDays(265));
        return no;
    }

    private String claim(String stat, long pay) {
        String clmNo = "CLM-PAY-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("""
                INSERT INTO TB_CLM (CLM_NO, CONTRACT_NO, CLM_STAT_CD, ACDT_DT, ACDT_DESC, CLAIMANT_ID, TOTAL_CLAIM_AMT, TOTAL_PAY_AMT,
                                    RPT_DT, DECIDE_DT, GLOBAL_TX_ID, REQ_HASH)
                VALUES (?, ?, ?, ?, '지급 시험', 'C1', ?, ?, NOW(), NOW(), ?, 'h')
                """, clmNo, contract(), stat, TODAY.minusDays(3), Math.max(pay, 1), pay, clmNo);
        return clmNo;
    }

    private ResultActions request(String txId, String srcType, String srcNo, String accountNo, String holder) throws Exception {
        return call("/api/payout/request", claimHandler, txId, "{ \"srcType\": \"" + srcType + "\", \"srcNo\": \"" + srcNo
                + "\", \"bankCd\": \"004\", \"accountNo\": \"" + accountNo + "\", \"holderNm\": \"" + holder + "\" }");
    }

    private Map<String, Object> payout(String srcNo) {
        return jdbc.queryForMap("SELECT * FROM TB_PAY_OUT WHERE SRC_NO = ? ORDER BY PAYOUT_ID DESC LIMIT 1", srcNo);
    }

    private void due(String srcNo) {
        jdbc.update("UPDATE TB_PAY_OUT SET NEXT_TRY_DT = NOW() - INTERVAL '1 minute' WHERE SRC_NO = ?", srcNo);
    }

    @Test
    void 예금주_조회는_계좌_상태와_이름을_확인한다() throws Exception {
        String verify = "/api/payout/account-verify";
        call(verify, claimHandler, "tx-v1", "{ \"bankCd\": \"004\", \"accountNo\": \"11122233344\", \"holderNm\": \"홍 길동\" }")
                .andExpect(jsonPath("$.body.matched").value(true)).andExpect(jsonPath("$.body.bankHolderNm").value("홍*동"));
        call(verify, claimHandler, "tx-v2", "{ \"bankCd\": \"004\", \"accountNo\": \"11122233344\", \"holderNm\": \"홍길순\" }")
                .andExpect(jsonPath("$.body.matched").value(false)).andExpect(jsonPath("$.body.resultMsg").value("예금주가 다릅니다"));
        call(verify, claimHandler, "tx-v3", "{ \"bankCd\": \"020\", \"accountNo\": \"99900011122\", \"holderNm\": \"박해지\" }")
                .andExpect(jsonPath("$.body.matched").value(false)).andExpect(jsonPath("$.body.resultCd").value("B102"));
        call(verify, claimHandler, "tx-v4", "{ \"bankCd\": \"004\", \"accountNo\": \"00000000000\", \"holderNm\": \"없음\" }")
                .andExpect(jsonPath("$.body.resultCd").value("B101"));
        call(verify, claimHandler, "tx-v5", "{ \"bankCd\": \"4\", \"accountNo\": \"123\", \"holderNm\": \"\" }")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-PAY-001"));
        bank.setFailAll(true);
        call(verify, claimHandler, "tx-v6", "{ \"bankCd\": \"004\", \"accountNo\": \"11122233344\", \"holderNm\": \"홍길동\" }")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.header.responseCode").value("E-PAY-005"));
        call(verify, TestAuth.as("E-AG-P", Roles.AGENT), "tx-v7", "{}").andExpect(status().isForbidden());
    }

    @Test
    void 지급_요청은_지급_결정된_원천과_맞는_계좌만_받고_계좌번호는_암호화한다() throws Exception {
        String clmNo = claim("40", 150_000);
        request("tx-p0-" + clmNo, "CLAIM", claim("20", 1000), "11122233344", "홍길동")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-PAY-002"));
        request("tx-p1-" + clmNo, "CLAIM", clmNo, "11122233344", "김철수")
                .andExpect(jsonPath("$.header.responseCode").value("E-PAY-003"));
        request("tx-p2-" + clmNo, "CLAIM", clmNo, "30012345678", "최정지")
                .andExpect(jsonPath("$.header.responseCode").value("E-PAY-003"));   // 지급정지 계좌

        String txId = "tx-p3-" + clmNo;
        request(txId, "CLAIM", clmNo, "11122233344", "홍길동")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.statCd").value("10"))
                .andExpect(jsonPath("$.body.payAmt").value(150000))
                .andExpect(jsonPath("$.body.accountMask").value("111*****344"))
                .andExpect(jsonPath("$.body.traceNo").value(org.hamcrest.Matchers.startsWith("K99")));
        request(txId, "CLAIM", clmNo, "11122233344", "홍길동").andExpect(status().isOk());   // 재전송
        request("tx-p4-" + clmNo, "CLAIM", clmNo, "11122233344", "홍길동")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-PAY-004"));
        request(txId, "CLAIM", claim("40", 1000), "11122233344", "홍길동")
                .andExpect(jsonPath("$.header.responseCode").value("E-PAY-007"));

        String stored = (String) payout(clmNo).get("account_enc");
        assertThat(stored).doesNotContain("11122233344");
        assertThat(cipher.decrypt(stored)).isEqualTo("11122233344");

        sender.processDue();
        Map<String, Object> done = payout(clmNo);
        assertThat(done.get("stat_cd")).isEqualTo("30");
        assertThat(done.get("send_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT AMOUNT FROM TB_SIM_BANK_TRANSFER WHERE TRACE_NO = ?", Long.class, done.get("trace_no")))
                .isEqualTo(150_000L);
        call("/api/payout/list", claimHandler, "tx-l", "{ \"srcType\": \"CLAIM\", \"srcNo\": \"" + clmNo + "\" }")
                .andExpect(jsonPath("$.body[0].statCd").value("30"));
    }

    @Test
    void 응답이_없으면_다시_보내지_않고_결과를_조회한다() throws Exception {
        // 7777: 은행은 이체하고 응답을 잃는다 → 결과 불명 → 결과 조회 S → 완료. 이체는 한 번뿐
        String lost = claim("40", 70_000);
        request("tx-r1-" + lost, "CLAIM", lost, "55566677777", "김지급").andExpect(status().isOk());
        sender.processDue();
        Map<String, Object> p = payout(lost);
        assertThat(p.get("stat_cd")).isEqualTo("70");
        assertThat(p.get("sent_dt")).isNotNull();
        sender.processDue();   // 결과 조회는 잠시 뒤라 아직 아무것도 하지 않는다
        assertThat(payout(lost).get("inquiry_count")).isEqualTo(0);
        due(lost);
        sender.processDue();
        p = payout(lost);
        assertThat(p.get("stat_cd")).isEqualTo("30");
        assertThat(p.get("send_count")).isEqualTo(1);
        assertThat(p.get("inquiry_count")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TB_SIM_BANK_TRANSFER WHERE TRACE_NO = ?", Integer.class, p.get("trace_no"))).isEqualTo(1);

        // 8888: 은행이 받기 전에 끊긴다 → 결과 조회 N(받은 적 없음) → 같은 거래고유번호로 다시 이체 → 또 끊겨 결과 불명
        String cut = claim("40", 30_000);
        jdbc.update("UPDATE TB_SIM_BANK_ACCOUNT SET BANK_CD = '004' WHERE ACCOUNT_NO = '12345678888'");   // 시험 편의상 같은 은행으로
        request("tx-r2-" + cut, "CLAIM", cut, "12345678888", "이불명").andExpect(status().isOk());
        sender.processDue();
        assertThat(payout(cut).get("stat_cd")).isEqualTo("70");
        due(cut);
        sender.processDue();
        p = payout(cut);
        assertThat(p.get("stat_cd")).isEqualTo("70");
        assertThat(p.get("send_count")).isEqualTo(2);
        assertThat(p.get("inquiry_count")).isEqualTo(1);
    }

    @Test
    void 계좌_오류_응답은_실패로_두고_새로_요청할_수_있다() throws Exception {
        String clmNo = claim("40", 20_000);
        jdbc.update("INSERT INTO TB_SIM_BANK_ACCOUNT (BANK_CD, ACCOUNT_NO, HOLDER_NM, STAT_CD) VALUES ('004', '77700011122', '정해지', 'ACTIVE')");
        request("tx-f1-" + clmNo, "CLAIM", clmNo, "77700011122", "정해지").andExpect(status().isOk());
        // 요청과 이체 사이에 계좌가 해지됐다
        jdbc.update("UPDATE TB_SIM_BANK_ACCOUNT SET STAT_CD = 'CLOSED' WHERE ACCOUNT_NO = '77700011122'");
        sender.processDue();
        Map<String, Object> failed = payout(clmNo);
        assertThat(failed.get("stat_cd")).isEqualTo("80");
        assertThat(failed.get("result_cd")).isEqualTo("B102");
        request("tx-f2-" + clmNo, "CLAIM", clmNo, "11122233344", "홍길동").andExpect(status().isOk());
        sender.processDue();
        assertThat(payout(clmNo).get("stat_cd")).isEqualTo("30");
    }

    @Test
    void 기관을_종료하면_이체를_보내지_않고_대기에_둔다() throws Exception {
        String clmNo = claim("40", 10_000);
        request("tx-o1-" + clmNo, "CLAIM", clmNo, "11122233344", "홍길동").andExpect(status().isOk());
        fepAdmin.signOff(BankLayouts.INST, "E-ADM-P");
        sender.processDue();
        Map<String, Object> p = payout(clmNo);
        assertThat(p.get("stat_cd")).isEqualTo("10");
        assertThat(p.get("send_count")).isEqualTo(0);
        assertThat((String) p.get("result_msg")).contains("보내지 않음");
        fepAdmin.signOn(BankLayouts.INST, "E-ADM-P");
        due(clmNo);
        sender.processDue();
        assertThat(payout(clmNo).get("stat_cd")).isEqualTo("30");
    }

    @Test
    void 마일리지_환급금도_같은_방식으로_지급한다() throws Exception {
        String contractNo = contract();
        jdbc.update("""
                INSERT INTO TB_CTR_MILEAGE (CONTRACT_NO, MIL_STAT_CD, START_ODO_KM, START_REG_DT, START_TX_ID, END_ODO_KM, END_REG_DT, END_TX_ID,
                                            DRIVEN_KM, ANNUAL_KM, REFUND_RATE, REFUND_BASE_PREM, REFUND_AMT)
                VALUES (?, '20', 0, NOW(), ?, 4000, NOW(), ?, 4000, 4000, 0.32, 500000, 160000)
                """, contractNo, "s-" + contractNo, "e-" + contractNo);
        call("/api/payout/request", TestAuth.as("E-OP-P", Roles.OPERATOR), "tx-m-" + contractNo, "{ \"srcType\": \"MILEAGE\", \"srcNo\": \""
                + contractNo + "\", \"bankCd\": \"004\", \"accountNo\": \"11122233344\", \"holderNm\": \"홍길동\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.payAmt").value(160000));
        sender.processDue();
        assertThat(payout(contractNo).get("stat_cd")).isEqualTo("30");
        assertThat(JsonPath.<String>read(call("/api/payout/list", TestAuth.as("E-OP-P", Roles.OPERATOR), "tx-ml",
                "{ \"contractNo\": \"" + contractNo + "\" }").andReturn().getResponse().getContentAsString(), "$.body[0].srcType"))
                .isEqualTo("MILEAGE");
    }
}
