package com.insur.insurcoresystem.ext.fep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.bank.BankClient;
import com.insur.insurcoresystem.ext.bank.BankLayouts;
import com.insur.insurcoresystem.ext.bank.BankSimulator;
import java.util.List;
import java.util.Map;
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
 * FEP: 개시·종료, 회선 차단, 전문 로그, 운영 API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FepGatewayIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FepGateway fep;
    @Autowired
    BankClient bankClient;
    @Autowired
    BankSimulator bank;
    @Autowired
    FepAdminService fepAdmin;

    private final RequestPostProcessor admin = TestAuth.as("E-ADM-F", Roles.ADMIN);
    private final RequestPostProcessor operator = TestAuth.as("E-OP-F", Roles.OPERATOR);

    @AfterEach
    void restore() {
        bank.setFailAll(false);
        fep.resetCircuit(BankLayouts.INST);
    }

    private ResultActions call(String path, RequestPostProcessor auth, String body) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-fep\" }, \"body\": " + body + " }"));
    }

    @Test
    void 응답_없음이_이어지면_회선을_막고_보내지_않으며_에코가_성공하면_푼다() {
        bank.setFailAll(true);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> bankClient.verify("004", "11122233344"))
                    .isInstanceOfSatisfying(ExternalAgencyException.class, e -> assertThat(e.isMaybeDelivered()).isTrue());
        }
        assertThat(fep.circuit(BankLayouts.INST).open()).isTrue();
        bank.setFailAll(false);
        // 기관이 살아나도 차단 중에는 보내지 않는다 (보내지 않았으니 다시 보내도 안전하다)
        assertThatThrownBy(() -> bankClient.verify("004", "11122233344"))
                .isInstanceOfSatisfying(ExternalAgencyException.class, e -> assertThat(e.isMaybeDelivered()).isFalse());
        String last = jdbc.queryForObject("SELECT RESULT_CD FROM TB_FEP_MSG_LOG WHERE INST_CD = 'BANK' ORDER BY LOG_ID DESC LIMIT 1", String.class);
        assertThat(last).isEqualTo("BLOCKED");

        // 회선 확인(에코)이 성공하면 차단을 푼다
        assertThat(fepAdmin.echo(BankLayouts.INST).lastEchoOk()).isTrue();
        assertThat(fep.circuit(BankLayouts.INST).open()).isFalse();
        assertThat(bankClient.verify("004", "11122233344").ok()).isTrue();
    }

    @Test
    void 운영_API_로_개시_종료_에코와_전문_로그를_본다() throws Exception {
        call("/api/admin/fep/inst-list", operator, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[?(@.instCd == 'BANK')].signOn").value(true))
                .andExpect(jsonPath("$.body[?(@.instCd == 'KCIS')].transport").value(true));
        call("/api/admin/fep/sign-off", operator, "{ \"instCd\": \"BANK\" }").andExpect(status().isForbidden());
        call("/api/admin/fep/sign-off", admin, "{ \"instCd\": \"BANK\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.signOn").value(false));
        assertThatThrownBy(() -> bankClient.verify("004", "11122233344"))
                .isInstanceOfSatisfying(ExternalAgencyException.class, e -> assertThat(e.getMessage()).contains("개시하지 않은"));
        // 망관리 전문은 종료 중에도 보낼 수 있다 (회선 확인)
        call("/api/admin/fep/echo", operator, "{ \"instCd\": \"BANK\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.lastEchoOk").value(true));
        call("/api/admin/fep/sign-on", admin, "{ \"instCd\": \"BANK\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.signOn").value(true));
        assertThat(bankClient.verify("004", "11122233344").holderNm()).isEqualTo("홍길동");
        call("/api/admin/fep/sign-on", admin, "{ \"instCd\": \"NOPE\" }")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.responseCode").value("E-FEP-001"));
        bank.setFailAll(true);
        call("/api/admin/fep/sign-on", admin, "{ \"instCd\": \"BANK\" }")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.header.responseCode").value("E-FEP-002"));

        // 전문 로그에는 본문(계좌번호·이름)이 없고 길이와 해시만 있다
        List<Map<String, Object>> logs = jdbc.queryForList("SELECT * FROM TB_FEP_MSG_LOG WHERE INST_CD = 'BANK' AND TX_TYPE = 'B100' ORDER BY LOG_ID DESC LIMIT 1");
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("req_hash").toString()).hasSize(64);
        assertThat(logs.get(0).values()).noneMatch(v -> v != null && v.toString().contains("11122233344"));
        call("/api/admin/fep/msg-log", operator, "{ \"instCd\": \"BANK\", \"resultCd\": \"OK\", \"limit\": 5 }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body[0].resultCd").value("OK"));
        call("/api/admin/fep/msg-log", operator, "{ \"resultCd\": \"MAYBE\" }")
                .andExpect(jsonPath("$.header.responseCode").value("E-FEP-003"));
    }
}
