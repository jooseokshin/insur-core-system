package com.insur.insurcoresystem.service.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.insur.insurcoresystem.service.stats.dto.IbnrResponse;
import java.math.BigDecimal;
import java.util.List;
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
 * IBNR 산출을 실제 DB 로 확인한다. 금액은 {@link ReserveIbnrFixture} 의 손 계산.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReserveIbnrIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReserveService reserveService;

    @Test
    void 사고일_지급일_기준_삼각형으로_체인래더와_BF_를_계산한다() {
        ReserveIbnrFixture.insert(jdbc);
        IbnrResponse r = reserveService.compute(ReserveIbnrFixture.CLS, ReserveIbnrFixture.EVAL, ReserveService.QUARTER, 4, BigDecimal.ONE, null);

        assertThat(r.getTriangle()).extracting(IbnrResponse.TriangleRow::getPeriod).containsExactly("2001Q1", "2001Q2", "2001Q3", "2001Q4");
        assertThat(r.getTriangle().get(0).getCells()).extracting(BigDecimal::longValue).containsExactly(10_000L, 30_000L, 30_000L, 30_000L);
        assertThat(r.getTriangle().get(2).getCells()).extracting(BigDecimal::longValue).containsExactly(4_000L, 8_000L);
        assertThat(r.getFactors()).extracting(f -> f.getFactor().toPlainString()).containsExactly("2.500000", "1.000000", "1.000000", "1.000000");
        assertThat(r.getPeriods()).extracting(p -> p.getEarnedPremium().longValue()).containsExactly(90_000L, 91_000L, 92_000L, 92_000L);
        assertThat(r.getExpectedLossRatio()).isEqualByComparingTo("0.1679");
        assertThat(r.isElrEstimated()).isTrue();

        IbnrResponse.Period q4 = r.getPeriods().get(3);
        assertThat(q4.getCaseReserve()).isEqualByComparingTo("8000");
        assertThat(q4.getDeveloped()).isEqualByComparingTo("0.4");
        assertThat(q4.getClIbnr()).isEqualByComparingTo("-5000");
        assertThat(q4.getMethod()).isEqualTo("BF");
        assertThat(q4.getBfUltimate()).isEqualByComparingTo("11268");
        assertThat(q4.getIbnr()).isEqualByComparingTo("1268");
        assertThat(r.getPeriods().subList(0, 3)).extracting(IbnrResponse.Period::getMethod).containsOnly("CL");

        assertThat(r.getTotal().getPaid()).isEqualByComparingTo("52000");
        assertThat(r.getTotal().getCaseReserve()).isEqualByComparingTo("8000");
        assertThat(r.getTotal().getIbnr()).isEqualByComparingTo("1268");
        assertThat(r.getTotal().getReserve()).isEqualByComparingTo("9268");
        assertThat(r.getWarnings()).noneMatch(w -> w.contains("말일이 아니라"));
    }

    @Test
    void 기준일이_기간_말일이_아니면_경고한다() {
        ReserveIbnrFixture.insert(jdbc);
        IbnrResponse r = reserveService.compute(ReserveIbnrFixture.CLS, ReserveIbnrFixture.EVAL.minusDays(15), ReserveService.QUARTER, 4,
                BigDecimal.ONE, new BigDecimal("0.5"));
        assertThat(r.getWarnings()).anyMatch(w -> w.contains("말일이 아니라"));
        assertThat(r.isElrEstimated()).isFalse();
        assertThat(r.getExpectedLossRatio()).isEqualByComparingTo("0.5");
    }

    private ResultActions call(RequestPostProcessor auth, String body) throws Exception {
        return mockMvc.perform(post("/api/stats/ibnr").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-ibnr\" }, \"body\": " + body + " }"));
    }

    @Test
    void API_는_조건을_검사하고_심사_보상_관리자만_쓴다() throws Exception {
        RequestPostProcessor uw = TestAuth.as("E-UW-I", Roles.UNDERWRITER);
        call(uw, "{ \"insrClsCd\": \"01\", \"periodType\": \"YEAR\", \"periods\": 3 }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.periods.length()").value(3))
                .andExpect(jsonPath("$.body.total.reserve").exists());
        for (String bad : List.of("{}", "{ \"insrClsCd\": \"77\" }", "{ \"insrClsCd\": \"01\", \"periodType\": \"MONTH\" }",
                "{ \"insrClsCd\": \"01\", \"periods\": 1 }", "{ \"insrClsCd\": \"01\", \"tailFactor\": 0.9 }",
                "{ \"insrClsCd\": \"01\", \"evaluationDate\": \"2999-12-31\" }", "{ \"insrClsCd\": \"01\", \"expectedLossRatio\": 0 }")) {
            call(uw, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-RSV-001"));
        }
        call(TestAuth.as("E-AG-I", Roles.AGENT), "{ \"insrClsCd\": \"01\" }").andExpect(status().isForbidden());
    }
}
