package com.insur.insurcoresystem.service.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
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
 * 자동이체 등록(출금 동의)·해지·조회와 갱신 승계.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CmsMandateIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CmsMandateService service;

    private final RequestPostProcessor agent = TestAuth.as("E-AG-CMS", Roles.AGENT);
    private final RequestPostProcessor operator = TestAuth.as("E-OP-CMS", Roles.OPERATOR);

    private String contract(String stat, String regEmpId) {
        String no = "CMS-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, CTR_STAT_CD, START_DATE, END_DATE, POLICYHOLDER_ID, INSURED_ID,
                                        TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID)
                VALUES (?, ?, '01', ?, ?, ?, 'C1', 'C1', 300000, NOW(), ?)
                """, no, "APP-" + no, stat, TODAY.plusDays(1), TODAY.plusDays(366), regEmpId);
        return no;
    }

    private ResultActions call(String path, RequestPostProcessor auth, String txId, String body) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": " + body + " }"));
    }

    private ResultActions register(RequestPostProcessor auth, String txId, String contractNo, String accountNo, String payer, String consent)
            throws Exception {
        return call("/api/contract/autodebit/register", auth, txId, "{ \"contractNo\": \"" + contractNo + "\", \"bankCd\": \"004\", "
                + "\"accountNo\": \"" + accountNo + "\", \"payerNm\": \"" + payer + "\", \"consentMethod\": \"" + consent
                + "\", \"consentRef\": \"REC-2026-0001\" }");
    }

    @Test
    void 출금_동의는_예금주가_맞는_계좌로_계약당_하나_등록하고_계좌는_암호화한다() throws Exception {
        String no = contract("10", "E-AG-CMS");
        register(agent, "tx-c1-" + no, no, "11122233344", "홍길순", "RECORDING")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CMS-003"));
        register(agent, "tx-c2-" + no, no, "99900011122", "박해지", "RECORDING")
                .andExpect(jsonPath("$.header.responseCode").value("E-CMS-003"));   // 형식은 맞지만 004 에 없는 계좌 (예금주 조회 B101)
        register(agent, "tx-c3-" + no, no, "11122233344", "홍길동", "FAX")
                .andExpect(jsonPath("$.header.responseCode").value("E-CMS-001"));
        String tx = "tx-c4-" + no;
        register(agent, tx, no, "11122233344", "홍길동", "RECORDING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.mandate.statCd").value("10"))
                .andExpect(jsonPath("$.body.mandate.accountMask").value("111*****344"))
                .andExpect(jsonPath("$.body.mandate.consentMethod").value("RECORDING"));
        register(agent, tx, no, "11122233344", "홍길동", "RECORDING").andExpect(status().isOk());   // 재전송
        register(agent, "tx-c5-" + no, no, "11122233344", "홍길동", "PAPER")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CMS-004"));
        assertThat(jdbc.queryForObject("SELECT ACCOUNT_ENC FROM TB_CMS_MANDATE WHERE CONTRACT_NO = ?", String.class, no))
                .doesNotContain("11122233344");

        // 설계사는 본인 계약만, 다른 설계사의 계약은 없는 계약과 같다
        String others = contract("10", "E-AG-OTHER");
        register(agent, "tx-c6-" + others, others, "11122233344", "홍길동", "PAPER")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
        register(TestAuth.as("E-UW-CMS", Roles.UNDERWRITER), "tx-c7", no, "11122233344", "홍길동", "PAPER").andExpect(status().isForbidden());
        // 해지된 계약은 등록할 수 없다
        String terminated = contract("30", "E-AG-CMS");
        register(operator, "tx-c8-" + terminated, terminated, "11122233344", "홍길동", "PAPER")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CMS-002"));
    }

    @Test
    void 출금이_진행_중이면_해지와_수기_수납을_막는다() throws Exception {
        String no = contract("10", "E-AG-CMS");
        register(operator, "tx-d1-" + no, no, "11122233344", "홍길동", "ARS").andExpect(status().isOk());
        long mandateId = jdbc.queryForObject("SELECT MANDATE_ID FROM TB_CMS_MANDATE WHERE CONTRACT_NO = ?", Long.class, no);
        jdbc.update("UPDATE TB_CMS_MANDATE SET STAT_CD = '30' WHERE MANDATE_ID = ?", mandateId);
        jdbc.update("""
                INSERT INTO TB_CMS_DEBIT (DEBIT_DT, MANDATE_ID, CONTRACT_NO, AMOUNT, ATTEMPT_NO, STAT_CD, JOB_EXECUTION_ID, CREATED_DT)
                VALUES (?, ?, ?, 300000, 1, '20', 0, NOW())""", TODAY, mandateId, no);

        call("/api/contract/autodebit/cancel", operator, "tx-d2", "{ \"contractNo\": \"" + no + "\" }")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CMS-005"));
        call("/api/contract/payment", operator, "tx-d3-" + no, "{ \"contractNo\": \"" + no + "\", \"payAmt\": 300000, \"payMethod\": \"CARD\" }")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-031"));
        // 직원 수납 API 는 CMS 를 받지 않는다 (배치만 기록)
        call("/api/contract/payment", operator, "tx-d4-" + no, "{ \"contractNo\": \"" + no + "\", \"payAmt\": 1000, \"payMethod\": \"CMS\" }")
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-029"));

        jdbc.update("UPDATE TB_CMS_DEBIT SET STAT_CD = '80' WHERE CONTRACT_NO = ?", no);
        call("/api/contract/autodebit/cancel", operator, "tx-d5", "{ \"contractNo\": \"" + no + "\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.mandate.statCd").value("90"));
        call("/api/contract/autodebit/cancel", operator, "tx-d6", "{ \"contractNo\": \"" + no + "\" }")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.responseCode").value("E-CMS-008"));
        call("/api/contract/autodebit/inquiry", TestAuth.as("E-CLM-CMS", Roles.CLAIM), "tx-d7", "{ \"contractNo\": \"" + no + "\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.debits[0].statCd").value("80"));
    }

    @Test
    void 갱신_계약은_완료된_자동이체를_이어받아_새_납부자번호로_다시_등록한다() throws Exception {
        String source = contract("20", "E-AG-CMS");
        String renewal = contract("10", "E-AG-CMS");
        register(operator, "tx-r1-" + source, source, "11122233344", "홍길동", "E_SIGN").andExpect(status().isOk());
        // 등록 완료 전이면 잇지 않는다
        service.inheritForRenewal(source, renewal, "E-AG-CMS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TB_CMS_MANDATE WHERE CONTRACT_NO = ?", Integer.class, renewal)).isZero();

        jdbc.update("UPDATE TB_CMS_MANDATE SET STAT_CD = '30' WHERE CONTRACT_NO = ?", source);
        service.inheritForRenewal(source, renewal, "E-AG-CMS");
        service.inheritForRenewal(source, renewal, "E-AG-CMS");   // 갱신 재전송에도 한 번만
        Map<String, Object> inherited = jdbc.queryForMap("SELECT * FROM TB_CMS_MANDATE WHERE CONTRACT_NO = ?", renewal);
        assertThat(inherited.get("stat_cd")).isEqualTo("10");
        assertThat(inherited.get("inherited_from")).isEqualTo(source);
        assertThat(inherited.get("consent_method")).isEqualTo("E_SIGN");
        assertThat(inherited.get("account_enc")).isEqualTo(jdbc.queryForObject("SELECT ACCOUNT_ENC FROM TB_CMS_MANDATE WHERE CONTRACT_NO = ?",
                String.class, source));
    }
}
