package com.insur.insurcoresystem.service.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널계 시스템(CHANNEL 토큰)으로 들어오는 업무. 판매채널은 토큰으로 정해지고, 채널 할인 계수가 산출과 청약 검증에 똑같이 걸리며,
 * 채널은 자기 채널이 접수한 계약만 보고, 열어 둔 API 밖은 막힌다.
 * <pre>
 * 자동차 CA001 가입금액 1억, 1년(365일), 기본 요율 0.005, 운전자 35세·할인할증 3등급(계수 1.0)
 *   대면: 100,000,000 × 0.005 = 500,000
 *   다이렉트(계수 0.85): 425,000
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChannelIntegrationTest {

    private static final String CHANNEL_ID = "ch-direct-test";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    private static RequestPostProcessor channel(String clientId, String channelCd) {
        return jwt().jwt(t -> {
            t.subject(clientId);
            if (channelCd != null) {
                t.claim("channel_cd", channelCd);
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_" + Roles.CHANNEL));
    }

    private static RequestPostProcessor direct() {
        return channel(CHANNEL_ID, "20");
    }

    private ResultActions call(String path, RequestPostProcessor auth, String body) throws Exception {
        return call(path, auth, body, "tx-ch-" + UUID.randomUUID());
    }

    private ResultActions call(String path, RequestPostProcessor auth, String body, String txId) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": " + body + " }"));
    }

    private String quoteBody() {
        return """
                { "insrClsCd": "01", "startDate": "%s", "endDate": "%s",
                  "rating": { "driverAge": 35, "discountGrade": "3" },
                  "coverages": [ { "cvrgCd": "CA001", "isrdAmt": 100000000 } ] }
                """.formatted(today.plusDays(1), today.plusDays(366));
    }

    private String applyBody(long prem) {
        return """
                { "insrClsCd": "01", "startDate": "%s", "endDate": "%s", "policyholderId": "CUST-1", "insuredId": "CUST-1",
                  "totalPremium": %d, "rating": { "driverAge": 35, "discountGrade": "3" },
                  "coverages": [ { "cvrgCd": "CA001", "isrdAmt": 100000000, "prem": %d } ] }
                """.formatted(today.plusDays(1), today.plusDays(366), prem, prem);
    }

    private String applyAs(RequestPostProcessor auth, long prem) throws Exception {
        return JsonPath.read(call("/api/contract/apply", auth, applyBody(prem)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.body.contractNo");
    }

    @Test
    void 채널_할인은_토큰의_판매채널로_정해지고_산출과_청약에_똑같이_걸린다() throws Exception {
        call("/api/premium/quote", TestAuth.as("E-STAFF", Roles.AGENT), quoteBody())
                .andExpect(jsonPath("$.body.totalPremium").value(500000));
        call("/api/premium/quote", direct(), quoteBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.totalPremium").value(425000))
                .andExpect(jsonPath("$.body.factors[?(@.factorType == 'SALES_CHANNEL')].input").value("20"));
        // 바디로 판매채널을 보내도 무시한다 (직원이 다이렉트 할인을 받을 수 없다)
        call("/api/premium/quote", TestAuth.as("E-STAFF", Roles.AGENT), quoteBody().replace("\"insrClsCd\"", "\"salesChnlCd\": \"20\", \"insrClsCd\""))
                .andExpect(jsonPath("$.body.totalPremium").value(500000));

        // 채널이 대면 보험료로, 직원이 채널 보험료로 청약하면 산출 보험료와 달라 거절된다
        call("/api/contract/apply", direct(), applyBody(500000)).andExpect(jsonPath("$.header.responseCode").value("E-CTR-020"));
        call("/api/contract/apply", TestAuth.as("E-STAFF", Roles.AGENT), applyBody(425000))
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-020"));

        String contractNo = applyAs(direct(), 425000);
        assertThat(jdbc.queryForMap("SELECT SALES_CHNL_CD, REG_EMP_ID FROM TB_CTR_LGD WHERE CONTRACT_NO = ?", contractNo))
                .containsEntry("sales_chnl_cd", "20").containsEntry("reg_emp_id", CHANNEL_ID);
        call("/api/contract/inquiry", direct(), "{ \"contractNo\": \"" + contractNo + "\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.salesChnlCd").value("20"));
        String staffContract = applyAs(TestAuth.as("E-STAFF", Roles.AGENT), 500000);
        assertThat(jdbc.queryForObject("SELECT SALES_CHNL_CD FROM TB_CTR_LGD WHERE CONTRACT_NO = ?", String.class, staffContract))
                .isEqualTo("10");
    }

    @Test
    void 채널은_자기_채널이_접수한_계약만_보고_납입한다() throws Exception {
        String mine = applyAs(direct(), 425000);
        String staff = applyAs(TestAuth.as("E-STAFF", Roles.AGENT), 500000);
        String otherChannel = applyAs(channel("ch-other-test", "20"), 425000);

        for (String foreign : new String[] {staff, otherChannel}) {
            call("/api/contract/inquiry", direct(), "{ \"contractNo\": \"" + foreign + "\" }")
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
            call("/api/contract/payment", direct(), "{ \"contractNo\": \"" + foreign + "\", \"payAmt\": 1000, \"payMethod\": \"CARD\" }")
                    .andExpect(status().isNotFound());
        }
        call("/api/contract/payment", direct(), "{ \"contractNo\": \"" + mine + "\", \"payAmt\": 425000, \"payMethod\": \"CARD\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.fullyPaid").value(true));
        call("/api/contract/payment-history", direct(), "{ \"contractNo\": \"" + mine + "\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.paidPremium").value(425000));
    }

    @Test
    void 채널에_열지_않은_API_는_막힌다() throws Exception {
        String[] closed = {"/api/contract/list", "/api/contract/status-change", "/api/contract/endorse", "/api/contract/endorse-history",
                "/api/work/dashboard", "/api/work/todo", "/api/stats/loss-ratio", "/api/claim/list", "/api/claim/assess",
                "/api/admin/premium/rate-list", "/api/no-such-api"};
        for (String path : closed) {
            call(path, direct(), "{}").andExpect(status().isForbidden());
        }
    }

    @Test
    void 판매채널_클레임이_없는_채널_토큰은_거절한다() throws Exception {
        call("/api/premium/quote", channel(CHANNEL_ID, null), quoteBody())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.responseCode").value("E-AUTH-002"));
        call("/api/premium/quote", channel(CHANNEL_ID, "10"), quoteBody()).andExpect(status().isForbidden());
    }

    @Test
    void 갱신은_원_계약의_판매채널을_잇는다() throws Exception {
        // 다이렉트로 들어와 30일 뒤 만기인 유효 계약 (요율 계수 저장값: 35세, 3등급)
        String source = "CH-RN-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDate start = today.minusDays(335);
        LocalDate end = today.plusDays(30);
        jdbc.update("""
                INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, CTR_STAT_CD, START_DATE, END_DATE, POLICYHOLDER_ID, INSURED_ID,
                                        TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID, DRIVER_AGE, DISCOUNT_GRADE, SALES_CHNL_CD)
                VALUES (?, ?, '01', '20', ?, ?, 'CUST-1', 'CUST-1', 425000, NOW(), ?, 35, '3', '20')
                """, source, "APP-" + source, start, end, CHANNEL_ID);
        jdbc.update("INSERT INTO TB_CTR_CVRG (CONTRACT_NO, CVRG_CD, ISRD_AMT, PREM) VALUES (?, 'CA001', 100000000, 425000)", source);

        // 1년 = 365일 또는 366일이라 기대 보험료는 같은 공식으로 계산한다
        long days = java.time.temporal.ChronoUnit.DAYS.between(end, end.plusYears(1));
        long expected = Math.round(100_000_000L * 0.005 * 0.85 * days / 365.0);
        call("/api/contract/renew-quote", direct(), "{ \"contractNo\": \"" + source + "\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.totalPremium").value(expected));
        String renewed = JsonPath.read(call("/api/contract/renew", direct(), "{ \"contractNo\": \"" + source + "\" }")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.body.contractNo");
        assertThat(jdbc.queryForObject("SELECT SALES_CHNL_CD FROM TB_CTR_LGD WHERE CONTRACT_NO = ?", String.class, renewed))
                .isEqualTo("20");
    }
}
