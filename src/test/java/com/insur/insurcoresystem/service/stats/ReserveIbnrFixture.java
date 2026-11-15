package com.insur.insurcoresystem.service.stats;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * IBNR 손 계산용 자료. 다른 자료와 섞이지 않게 쓰지 않는 보험종목 98 과 2001년 사고만 쓴다.
 * <pre>
 * 계약: 2001-01-01 ~ 2002-01-01, 보험료 365,000 → 분기 경과보험료 90,000 / 91,000 / 92,000 / 92,000
 * 누적 지급 (분기 경과)     경과1    경과2    경과3    경과4
 *   2001Q1               10,000   30,000   30,000   30,000
 *   2001Q2                6,000   12,000   12,000
 *   2001Q3                4,000    8,000
 *   2001Q4                2,000
 * 2001Q4 개별추산액 = 접수만 된 3,000 + 기준일 전 손해사정 5,000 = 8,000 (2002년에 접수된 사고는 넣지 않는다)
 * f1 = 50,000 ÷ 20,000 = 2.5, f2 = f3 = 1 → 2001Q4 진전율 0.4 (BF), 나머지 1 (CL)
 * 케이프코드 ELR = 52,000 ÷ (90,000 + 91,000 + 92,000 + 92,000 × 0.4) = 52,000 ÷ 309,800 = 0.1679
 * 2001Q4 BF = 2,000 + 0.1679 × 92,000 × 0.6 = 11,268 → IBNR = 11,268 − 2,000 − 8,000 = 1,268 (CL 로는 5,000 − 10,000 = −5,000)
 * 지급준비금 = 개별추산액 8,000 + IBNR 1,268 = 9,268
 * </pre>
 */
public final class ReserveIbnrFixture {

    public static final String CLS = "98";
    public static final LocalDate EVAL = LocalDate.of(2001, 12, 31);

    private ReserveIbnrFixture() {
    }

    /** 자료를 넣고 증권번호를 돌려준다 */
    public static String insert(JdbcTemplate jdbc) {
        String no = "IBNR-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, CTR_STAT_CD, START_DATE, END_DATE, POLICYHOLDER_ID, INSURED_ID,
                                        TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID)
                VALUES (?, ?, '98', '20', DATE '2001-01-01', DATE '2002-01-01', 'C1', 'C1', 365000, NOW(), 'E-IBNR')
                """, no, "APP-" + no);
        jdbc.update("INSERT INTO TB_CTR_CVRG (CONTRACT_NO, CVRG_CD, ISRD_AMT, PREM) VALUES (?, 'XX001', 10000000, 365000)", no);
        paid(jdbc, no, "2001-02-01", "2001-03-15", 10_000);
        paid(jdbc, no, "2001-02-10", "2001-05-10", 20_000);
        paid(jdbc, no, "2001-05-01", "2001-06-20", 6_000);
        paid(jdbc, no, "2001-05-02", "2001-08-10", 6_000);
        paid(jdbc, no, "2001-08-01", "2001-09-01", 4_000);
        paid(jdbc, no, "2001-08-02", "2001-10-15", 4_000);
        paid(jdbc, no, "2001-10-10", "2001-11-20", 2_000);
        claim(jdbc, no, "10", "2001-11-01", "2001-11-05", null, null, 3_000, null);            // 접수만 (개별추산 = 청구액)
        claim(jdbc, no, "40", "2001-11-15", "2001-11-20", "2001-12-10", "2002-01-10", 9_000, 5_000L);  // 기준일 전 사정, 이후 지급
        claim(jdbc, no, "40", "2001-12-01", "2002-01-05", "2002-01-06", "2002-01-20", 7_000, 7_000L);  // 기준일 뒤 접수 (IBNR 의 실제 예)
        return no;
    }

    public static void delete(JdbcTemplate jdbc, String contractNo) {
        jdbc.update("DELETE FROM TB_CLM WHERE CONTRACT_NO = ?", contractNo);
        jdbc.update("DELETE FROM TB_CTR_CVRG WHERE CONTRACT_NO = ?", contractNo);
        jdbc.update("DELETE FROM TB_CTR_LGD WHERE CONTRACT_NO = ?", contractNo);
    }

    private static void paid(JdbcTemplate jdbc, String no, String acdt, String paid, long amount) {
        claim(jdbc, no, "40", acdt, acdt, paid, paid, amount, amount);
    }

    private static void claim(JdbcTemplate jdbc, String no, String stat, String acdt, String rpt, String adj, String decide, long claimAmt,
                              Long payAmt) {
        String clmNo = "CLM-IBNR-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("""
                INSERT INTO TB_CLM (CLM_NO, CONTRACT_NO, CLM_STAT_CD, ACDT_DT, ACDT_DESC, CLAIMANT_ID, TOTAL_CLAIM_AMT, TOTAL_PAY_AMT,
                                    RPT_DT, ADJ_DT, DECIDE_DT, GLOBAL_TX_ID, REQ_HASH)
                VALUES (?, ?, ?, CAST(? AS DATE), 'IBNR 시험', 'C1', ?, ?, CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP), ?, 'h')
                """, clmNo, no, stat, acdt, claimAmt, payAmt, rpt + " 10:00:00", adj == null ? null : adj + " 10:00:00",
                decide == null ? null : decide + " 10:00:00", clmNo);
    }
}
