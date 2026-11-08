package com.insur.insurcoresystem.service.stats;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
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
 * 손해율 집계를 손으로 계산한 값과 비교한다. 다른 테스트·개발 데이터와 겹치지 않도록 2001년 계약과 사고만 쓰고, 테스트가 끝나면 롤백된다.
 * <pre>
 * 집계 기간 2001-01-01 ~ 2001-06-30 (181일), 계약은 모두 2001-01-01 ~ 2002-01-01 (365일)
 *  A 유효: CA001 보험료 365,000 → 경과 181,000 / CA004 보험료 73,000 → 경과 36,200
 *  B 해지(2001-03-01): CA001 보험료 365,000 → 보장 59일 → 경과 59,000
 *  C 청약: 보장한 적이 없어 제외
 * 사고 (A 계약)
 *  1 지급 완료 CA004 100,000 (2001-02-10)        → 지급
 *  2 지급 대기 CA001 사정 지급액 50,000 (05-01)  → 추산
 *  3 접수 CA001 청구 30,000 (06-15)               → 추산
 *  4 면책 CA004 (03-01) / 5 지급 완료지만 사고일 07-15 → 제외
 * 합계: 경과 276,200, 발생 180,000 (지급 100,000 + 추산 80,000), 손해율 0.6517
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LossRatioIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;

    private String a;

    private void contract(String no, String stat, String termDate) {
        jdbc.update("""
                INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, PRD_CD, CTR_STAT_CD, START_DATE, END_DATE, TERM_DT,
                                        POLICYHOLDER_ID, INSURED_ID, TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID)
                VALUES (?, ?, '01', 'CAR-STD-2026', ?, DATE '2001-01-01', DATE '2002-01-01', CAST(? AS DATE),
                        'C1', 'C1', 0, TIMESTAMP '2000-12-20 10:00:00', 'E1')
                """, no, "APP" + no, stat, termDate);
    }

    private void coverage(String no, String cvrg, long prem) {
        jdbc.update("INSERT INTO TB_CTR_CVRG (CONTRACT_NO, CVRG_CD, ISRD_AMT, PREM) VALUES (?, ?, 100000000, ?)", no, cvrg, prem);
    }

    private void claim(String contractNo, String stat, String acdtDt, String cvrg, long claimAmt, Long payAmt) {
        String clmNo = "CLM-T-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("""
                INSERT INTO TB_CLM (CLM_NO, CONTRACT_NO, CLM_STAT_CD, ACDT_DT, ACDT_DESC, CLAIMANT_ID, TOTAL_CLAIM_AMT, TOTAL_PAY_AMT,
                                    RPT_DT, GLOBAL_TX_ID, REQ_HASH)
                VALUES (?, ?, ?, CAST(? AS DATE), '테스트 사고', 'C1', ?, ?, TIMESTAMP '2001-07-31 10:00:00', ?, 'h')
                """, clmNo, contractNo, stat, acdtDt, claimAmt, payAmt, "tx-" + clmNo);
        jdbc.update("INSERT INTO TB_CLM_CVRG (CLM_NO, CVRG_CD, CLAIM_AMT, ASSESSED_AMT, DEDUCTIBLE_AMT, PAY_AMT) VALUES (?, ?, ?, ?, 0, ?)",
                clmNo, cvrg, claimAmt, payAmt, payAmt);
    }

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        a = "LR-A-" + suffix;
        String b = "LR-B-" + suffix;
        String c = "LR-C-" + suffix;
        contract(a, "20", null);
        coverage(a, "CA001", 365_000);
        coverage(a, "CA004", 73_000);
        contract(b, "30", "2001-03-01");
        coverage(b, "CA001", 365_000);
        contract(c, "10", null);
        coverage(c, "CA001", 365_000);

        claim(a, "40", "2001-02-10", "CA004", 120_000, 100_000L);
        claim(a, "20", "2001-05-01", "CA001", 70_000, 50_000L);
        claim(a, "10", "2001-06-15", "CA001", 30_000, null);
        claim(a, "80", "2001-03-01", "CA004", 999_999, 0L);
        claim(a, "40", "2001-07-15", "CA004", 500_000, 500_000L);
    }

    private ResultActions lossRatio(String body) throws Exception {
        return mockMvc.perform(post("/api/stats/loss-ratio").with(TestAuth.as("E-UW", Roles.UNDERWRITER))
                .contentType(MediaType.APPLICATION_JSON).content("{ \"header\": {}, \"body\": " + body + " }"));
    }

    @Test
    void 경과보험료와_발생손해액으로_손해율을_계산한다() throws Exception {
        lossRatio("{ \"fromDate\": \"2001-01-01\", \"toDate\": \"2001-06-30\", \"groupBy\": \"CLASS\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.total.contractCount").value(2))
                .andExpect(jsonPath("$.body.total.earnedPremium").value(276200))
                .andExpect(jsonPath("$.body.total.claimCount").value(3))
                .andExpect(jsonPath("$.body.total.paidLoss").value(100000))
                .andExpect(jsonPath("$.body.total.outstandingLoss").value(80000))
                .andExpect(jsonPath("$.body.total.incurredLoss").value(180000))
                .andExpect(jsonPath("$.body.total.lossRatio").value(0.6517))
                .andExpect(jsonPath("$.body.rows.length()").value(1))
                .andExpect(jsonPath("$.body.rows[0].insrClsCd").value("01"));
    }

    @Test
    void 담보별로_묶으면_담보마다_경과보험료와_손해액이_나뉜다() throws Exception {
        lossRatio("{ \"fromDate\": \"2001-01-01\", \"toDate\": \"2001-06-30\", \"groupBy\": \"COVERAGE\" }")
                .andExpect(jsonPath("$.body.rows.length()").value(2))
                .andExpect(jsonPath("$.body.rows[0].cvrgCd").value("CA001"))
                .andExpect(jsonPath("$.body.rows[0].contractCount").value(2))
                .andExpect(jsonPath("$.body.rows[0].earnedPremium").value(240000))
                .andExpect(jsonPath("$.body.rows[0].incurredLoss").value(80000))
                .andExpect(jsonPath("$.body.rows[0].lossRatio").value(0.3333))
                .andExpect(jsonPath("$.body.rows[1].cvrgCd").value("CA004"))
                .andExpect(jsonPath("$.body.rows[1].earnedPremium").value(36200))
                .andExpect(jsonPath("$.body.rows[1].lossRatio").value(2.7624))
                // 담보 행의 계약 수를 더하면 3이지만, 전체 합계는 계약 2건이다
                .andExpect(jsonPath("$.body.total.contractCount").value(2));
    }

    @Test
    void 상품별로_묶으면_상품코드가_나온다() throws Exception {
        lossRatio("{ \"fromDate\": \"2001-01-01\", \"toDate\": \"2001-06-30\" }")
                .andExpect(jsonPath("$.body.groupBy").value("PRODUCT"))
                .andExpect(jsonPath("$.body.rows[0].prdCd").value("CAR-STD-2026"))
                .andExpect(jsonPath("$.body.rows[0].lossRatio").value(0.6517));
    }

    @Test
    void 기간_뒤로_사고가_나도_사고일_기준이라_그_기간에_잡힌다() throws Exception {
        // 7월만 보면 경과는 A 의 31일분, 손해는 7-15 사고 500,000
        lossRatio("{ \"fromDate\": \"2001-07-01\", \"toDate\": \"2001-07-31\", \"groupBy\": \"CLASS\" }")
                .andExpect(jsonPath("$.body.total.earnedPremium").value(37200))   // (365,000 + 73,000) × 31 ÷ 365
                .andExpect(jsonPath("$.body.total.incurredLoss").value(500000));
    }

    @Test
    void 기간과_묶는_단위를_검증하고_권한이_없으면_403이다() throws Exception {
        lossRatio("{ \"fromDate\": \"2001-07-01\", \"toDate\": \"2001-01-01\" }")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-STAT-001"));
        lossRatio("{ \"fromDate\": \"2001-01-01\", \"toDate\": \"2009-01-01\" }")
                .andExpect(jsonPath("$.header.responseCode").value("E-STAT-001"));
        lossRatio("{ \"groupBy\": \"REGION\" }")
                .andExpect(jsonPath("$.header.responseCode").value("E-STAT-002"));
        mockMvc.perform(post("/api/stats/loss-ratio").with(TestAuth.as("E-AG", Roles.AGENT))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"header\": {}, \"body\": {} }"))
                .andExpect(status().isForbidden());
    }
}
