package com.insur.insurcoresystem.service.stats;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요율 영향 시뮬레이션을 손으로 계산한 값과 비교한다. 다른 데이터와 섞이지 않게 테스트 전용 보험종목 99 와 요율표를 만들고, 끝나면 롤백된다.
 * <pre>
 * 요율표: CX001 0.010 (2000-01-01 ~ 2001-03-31) → 0.012 (2001-04-01 ~), CX002 0.020
 * 경험 2001-01-01 ~ 2001-12-31, 계약 X1 2001-01-01 ~ 2002-01-01 (365일 전부 경과)
 *   CX001 보험료 1,000,000, 지급 900,000 → 손해율 0.9
 *   CX002 보험료   500,000, 지급 200,000 → 손해율 0.4
 * 변경: CX001 +10%
 *   CX001 새 손해율 900,000 ÷ 1,100,000 = 0.8182, CX002 0.4 그대로
 *   전체 1,100,000 ÷ 1,500,000 = 0.7333 → 1,100,000 ÷ 1,600,000 = 0.6875, 가중 평균 변경률 +6.67%
 * 목표 0.75: 필요 변경률 CX001 0.9/0.75 − 1 = +20.00%, CX002 −46.67%, 전체 −2.22%
 *   신뢰도 Z = √(1/1082) = 0.0304 → CX001 +0.61%, CX002 −1.42% / 전체 Z = √(2/1082) = 0.0430 → −0.10%
 * 오늘 유효 계약 (오늘 = T)
 *   Y1 T−335 ~ T+30  CX001 120,000, CX002 60,000  → 갱신 대상
 *   Y2 T−30  ~ T+400 CX001 100,000                 → 365일 밖
 *   Y3 T−355 ~ T+10  CX001  50,000                 → 이미 갱신(청약 R)함
 *   유효: CX001 3건 270,000, CX002 1건 60,000, 전체 3건 330,000
 *   갱신: CX001 1건 120,000 → +12,000, CX002 1건 60,000 → 0, 전체 1건 180,000 → +12,000
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RateImpactIntegrationTest {

    private static final String EXPERIENCE = "\"fromDate\": \"2001-01-01\", \"toDate\": \"2001-12-31\"";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    private void rate(String cvrg, String rate, String from, String to) {
        jdbc.update("INSERT INTO TB_PRM_RATE (INSR_CLS_CD, CVRG_CD, CVRG_NM, ANNUAL_RATE, EFF_FROM, EFF_TO) "
                + "VALUES ('99', ?, ?, CAST(? AS NUMERIC), CAST(? AS DATE), CAST(? AS DATE))", cvrg, "테스트" + cvrg, rate, from, to);
    }

    private String contract(String stat, LocalDate start, LocalDate end, String renewedFrom) {
        String no = "RI-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("""
                INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, CTR_STAT_CD, START_DATE, END_DATE,
                                        POLICYHOLDER_ID, INSURED_ID, TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID, RENEWED_FROM)
                VALUES (?, ?, '99', ?, ?, ?, 'C1', 'C1', 0, TIMESTAMP '2000-12-20 10:00:00', 'E1', ?)
                """, no, "APP" + no, stat, start, end, renewedFrom);
        return no;
    }

    private void coverage(String no, String cvrg, long prem) {
        jdbc.update("INSERT INTO TB_CTR_CVRG (CONTRACT_NO, CVRG_CD, ISRD_AMT, PREM) VALUES (?, ?, 100000000, ?)", no, cvrg, prem);
    }

    private void paidClaim(String contractNo, String cvrg, long amount) {
        String clmNo = "CLM-RI-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("""
                INSERT INTO TB_CLM (CLM_NO, CONTRACT_NO, CLM_STAT_CD, ACDT_DT, ACDT_DESC, CLAIMANT_ID, TOTAL_CLAIM_AMT, TOTAL_PAY_AMT,
                                    RPT_DT, GLOBAL_TX_ID, REQ_HASH)
                VALUES (?, ?, '40', DATE '2001-06-01', '테스트 사고', 'C1', ?, ?, TIMESTAMP '2001-06-02 10:00:00', ?, 'h')
                """, clmNo, contractNo, amount, amount, "tx-" + clmNo);
        jdbc.update("INSERT INTO TB_CLM_CVRG (CLM_NO, CVRG_CD, CLAIM_AMT, ASSESSED_AMT, DEDUCTIBLE_AMT, PAY_AMT) VALUES (?, ?, ?, ?, 0, ?)",
                clmNo, cvrg, amount, amount, amount);
    }

    @BeforeEach
    void setUp() {
        rate("CX001", "0.010", "2000-01-01", "2001-03-31");
        rate("CX001", "0.012", "2001-04-01", null);
        rate("CX002", "0.020", "2000-01-01", null);

        String x1 = contract("20", LocalDate.of(2001, 1, 1), LocalDate.of(2002, 1, 1), null);
        coverage(x1, "CX001", 1_000_000);
        coverage(x1, "CX002", 500_000);
        paidClaim(x1, "CX001", 900_000);
        paidClaim(x1, "CX002", 200_000);

        String y1 = contract("20", today.minusDays(335), today.plusDays(30), null);
        coverage(y1, "CX001", 120_000);
        coverage(y1, "CX002", 60_000);
        String y2 = contract("20", today.minusDays(30), today.plusDays(400), null);
        coverage(y2, "CX001", 100_000);
        String y3 = contract("20", today.minusDays(355), today.plusDays(10), null);
        coverage(y3, "CX001", 50_000);
        contract("10", today.plusDays(10), today.plusDays(375), y3);
    }

    private ResultActions simulate(String body) throws Exception {
        return simulate(body, TestAuth.as("E-UW", Roles.UNDERWRITER));
    }

    private ResultActions simulate(String body, org.springframework.test.web.servlet.request.RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post("/api/stats/rate-impact").with(auth)
                .contentType(MediaType.APPLICATION_JSON).content("{ \"header\": {}, \"body\": " + body + " }"));
    }

    @Test
    void 요율을_바꾸면_경험_손해율과_갱신_보험료가_어떻게_달라지는지_계산한다() throws Exception {
        simulate("{ \"insrClsCd\": \"99\", " + EXPERIENCE + ", \"targetLossRatio\": 0.75,"
                + " \"changes\": [ { \"cvrgCd\": \"CX001\", \"rateChangePct\": 10 } ] }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.fullCredibilityClaims").value(1082))
                .andExpect(jsonPath("$.body.renewalTo").value(today.plusDays(365).toString()))
                .andExpect(jsonPath("$.body.rows.length()").value(2))
                // CX001: 오늘 요율 0.012 → 0.0132
                .andExpect(jsonPath("$.body.rows[0].cvrgCd").value("CX001"))
                .andExpect(jsonPath("$.body.rows[0].currentRate").value(0.012))
                .andExpect(jsonPath("$.body.rows[0].newRate").value(0.0132))
                .andExpect(jsonPath("$.body.rows[0].rateChangePct").value(10.00))
                .andExpect(jsonPath("$.body.rows[0].earnedPremium").value(1000000))
                .andExpect(jsonPath("$.body.rows[0].incurredLoss").value(900000))
                .andExpect(jsonPath("$.body.rows[0].lossRatio").value(0.9))
                .andExpect(jsonPath("$.body.rows[0].simulatedLossRatio").value(0.8182))
                .andExpect(jsonPath("$.body.rows[0].indicatedChangePct").value(20.00))
                .andExpect(jsonPath("$.body.rows[0].credibility").value(0.0304))
                .andExpect(jsonPath("$.body.rows[0].credibilityWeightedChangePct").value(0.61))
                .andExpect(jsonPath("$.body.rows[0].inForceContracts").value(3))
                .andExpect(jsonPath("$.body.rows[0].inForcePremium").value(270000))
                .andExpect(jsonPath("$.body.rows[0].renewalContracts").value(1))
                .andExpect(jsonPath("$.body.rows[0].renewalPremium").value(120000))
                .andExpect(jsonPath("$.body.rows[0].renewalPremiumChange").value(12000))
                // CX002: 변경 없음
                .andExpect(jsonPath("$.body.rows[1].rateChangePct").value(0.00))
                .andExpect(jsonPath("$.body.rows[1].lossRatio").value(0.4))
                .andExpect(jsonPath("$.body.rows[1].simulatedLossRatio").value(0.4))
                .andExpect(jsonPath("$.body.rows[1].indicatedChangePct").value(-46.67))
                .andExpect(jsonPath("$.body.rows[1].credibilityWeightedChangePct").value(-1.42))
                .andExpect(jsonPath("$.body.rows[1].renewalPremiumChange").value(0))
                // 전체
                .andExpect(jsonPath("$.body.total.cvrgCd").doesNotExist())
                .andExpect(jsonPath("$.body.total.rateChangePct").value(6.67))
                .andExpect(jsonPath("$.body.total.earnedPremium").value(1500000))
                .andExpect(jsonPath("$.body.total.claimCount").value(2))
                .andExpect(jsonPath("$.body.total.lossRatio").value(0.7333))
                .andExpect(jsonPath("$.body.total.simulatedLossRatio").value(0.6875))
                .andExpect(jsonPath("$.body.total.indicatedChangePct").value(-2.22))
                .andExpect(jsonPath("$.body.total.credibility").value(0.043))
                .andExpect(jsonPath("$.body.total.credibilityWeightedChangePct").value(-0.10))
                .andExpect(jsonPath("$.body.total.inForceContracts").value(3))
                .andExpect(jsonPath("$.body.total.inForcePremium").value(330000))
                .andExpect(jsonPath("$.body.total.renewalContracts").value(1))
                .andExpect(jsonPath("$.body.total.renewalPremium").value(180000))
                .andExpect(jsonPath("$.body.total.renewalPremiumChange").value(12000))
                // 경험 기간 중 CX001 요율이 바뀌었고, 사고가 적다
                .andExpect(jsonPath("$.body.warnings", hasItem(containsString("담보 요율 CX001 개정(2001-04-01 시작)"))))
                .andExpect(jsonPath("$.body.warnings", hasItem(containsString("사고 2건으로 완전 신뢰 기준(1082건)"))));
    }

    @Test
    void 목표_손해율이_없으면_필요_변경률은_비우고_갱신_기간을_줄이면_대상이_빠진다() throws Exception {
        simulate("{ \"insrClsCd\": \"99\", " + EXPERIENCE + ", \"renewalWindowDays\": 20 }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.targetLossRatio").doesNotExist())
                .andExpect(jsonPath("$.body.rows[0].indicatedChangePct").doesNotExist())
                .andExpect(jsonPath("$.body.rows[0].simulatedLossRatio").value(0.9))
                .andExpect(jsonPath("$.body.total.rateChangePct").value(0.00))
                .andExpect(jsonPath("$.body.total.renewalContracts").value(0))
                .andExpect(jsonPath("$.body.total.renewalPremium").value(0));
    }

    @Test
    void 기간을_생략하면_최근_12개월이고_미보고_사고_경고를_붙인다() throws Exception {
        simulate("{ \"insrClsCd\": \"99\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.toDate").value(today.toString()))
                .andExpect(jsonPath("$.body.fromDate").value(today.minusDays(364).toString()))
                .andExpect(jsonPath("$.body.warnings", hasItem(containsString("IBNR"))));
    }

    @Test
    void 잘못된_입력은_업무_오류로_거절한다() throws Exception {
        simulate("{ }").andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-STAT-003"));
        simulate("{ \"insrClsCd\": \"98\" }").andExpect(jsonPath("$.header.responseCode").value("E-STAT-003"));
        simulate("{ \"insrClsCd\": \"99\", \"changes\": [ { \"cvrgCd\": \"CA001\", \"rateChangePct\": 5 } ] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-STAT-004"));
        simulate("{ \"insrClsCd\": \"99\", \"changes\": [ { \"cvrgCd\": \"CX001\", \"rateChangePct\": 5 },"
                + " { \"cvrgCd\": \"CX001\", \"rateChangePct\": 6 } ] }")
                .andExpect(jsonPath("$.header.responseMsg").value("같은 담보를 두 번 넣었습니다: CX001"));
        simulate("{ \"insrClsCd\": \"99\", \"changes\": [ { \"cvrgCd\": \"CX001\", \"rateChangePct\": 150 } ] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-STAT-004"));
        simulate("{ \"insrClsCd\": \"99\", \"changes\": [ { \"cvrgCd\": \"CX001\", \"rateChangePct\": 1.234 } ] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-STAT-004"));
        simulate("{ \"insrClsCd\": \"99\", \"changes\": [ { \"cvrgCd\": \"CX001\" } ] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-STAT-004"));
        simulate("{ \"insrClsCd\": \"99\", \"targetLossRatio\": 0 }").andExpect(jsonPath("$.header.responseCode").value("E-STAT-005"));
        simulate("{ \"insrClsCd\": \"99\", \"targetLossRatio\": 1.6 }").andExpect(jsonPath("$.header.responseCode").value("E-STAT-005"));
        simulate("{ \"insrClsCd\": \"99\", \"renewalWindowDays\": 366 }").andExpect(jsonPath("$.header.responseCode").value("E-STAT-006"));
        simulate("{ \"insrClsCd\": \"99\", \"fromDate\": \"2001-12-31\", \"toDate\": \"2001-01-01\" }")
                .andExpect(jsonPath("$.header.responseCode").value("E-STAT-001"));
    }

    @Test
    void 심사_담당과_ADMIN_만_쓸_수_있다() throws Exception {
        simulate("{ \"insrClsCd\": \"99\" }", TestAuth.as("E-ADM", Roles.ADMIN)).andExpect(status().isOk());
        for (String role : new String[] {Roles.AGENT, Roles.OPERATOR, Roles.CLAIM}) {
            simulate("{ \"insrClsCd\": \"99\" }", TestAuth.as("E-X", role))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-002"));
        }
    }
}
