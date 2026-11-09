package com.insur.insurcoresystem.service.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 마일리지 특약 등록·정산. 환급액은 손으로 계산한 값과 비교한다 (V25 예시 환급률표).
 * <pre>
 * 1년 계약, 총보험료 600,000, 주행 4,000km → 1년 환산 4,000 → 32% → 192,000
 * 180일 계약, 총보험료 300,000, 주행 2,000km → 1년 환산 2,000 × 365 ÷ 180 = 4,055.6 → 4,056 → 32% (환산 전이면 40%) → 96,000
 * 총보험료 333,333 × 32% = 106,666.56 → 원 미만 버림 106,666
 * 주행 16,000km → 구간 없음 → 환급 0
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContractMileageIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private final RequestPostProcessor operator = TestAuth.as("E-OP-M", Roles.OPERATOR);

    private String contract(String cls, String stat, LocalDate start, LocalDate end, long premium, String regEmpId) {
        String no = "MIL-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("""
                INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, CTR_STAT_CD, START_DATE, END_DATE, POLICYHOLDER_ID, INSURED_ID,
                                        TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID)
                VALUES (?, ?, ?, ?, ?, ?, 'C1', 'C1', ?, NOW(), ?)
                """, no, "APP" + no, cls, stat, start, end, premium, regEmpId);
        return no;
    }

    /** 이미 시작 주행거리를 등록해 둔 유효 자동차 계약 */
    private String registered(LocalDate start, LocalDate end, long premium, int startOdo) {
        String no = contract("01", "20", start, end, premium, "E-AG-M");
        jdbc.update("INSERT INTO TB_CTR_MILEAGE (CONTRACT_NO, MIL_STAT_CD, START_ODO_KM, START_REG_DT, START_TX_ID) VALUES (?, '10', ?, NOW(), ?)",
                no, startOdo, "tx-start-" + no);
        return no;
    }

    private ResultActions call(String path, RequestPostProcessor auth, String txId, String contractNo, Integer odo) throws Exception {
        String body = odo == null ? "{ \"contractNo\": \"" + contractNo + "\" }"
                : "{ \"contractNo\": \"" + contractNo + "\", \"odometerKm\": " + odo + " }";
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": " + body + " }"));
    }

    private ResultActions settle(String contractNo, int odo) throws Exception {
        return call("/api/contract/mileage/settle", operator, "tx-" + UUID.randomUUID(), contractNo, odo);
    }

    @Test
    void 시작_주행거리는_자동차_계약에_보험시기_30일_뒤까지_한_번_등록한다() throws Exception {
        String no = contract("01", "10", today.plusDays(1), today.plusDays(366), 600000, "E-AG-M");
        call("/api/contract/mileage/inquiry", operator, "tx-q", no, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.milStatCd").doesNotExist())
                .andExpect(jsonPath("$.body.registerUntil").value(today.plusDays(31).toString()));

        call("/api/contract/mileage/register", operator, "tx-reg-1-" + no, no, 20000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.milStatCd").value("10"))
                .andExpect(jsonPath("$.body.startOdometerKm").value(20000));
        // 같은 거래 재전송은 현재 상태, 다른 내용이면 거절, 새 거래로 또 등록하면 이미 가입
        call("/api/contract/mileage/register", operator, "tx-reg-1-" + no, no, 20000).andExpect(status().isOk());
        call("/api/contract/mileage/register", operator, "tx-reg-1-" + no, no, 21000)
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-019"));
        call("/api/contract/mileage/register", operator, "tx-reg-2-" + no, no, 20000)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-MIL-004"));
        // 청약 상태라 아직 정산할 수 없다
        settle(no, 24000).andExpect(jsonPath("$.header.responseCode").value("E-MIL-002"));

        String late = contract("01", "20", today.minusDays(31), today.plusDays(334), 600000, "E-AG-M");
        call("/api/contract/mileage/register", operator, "tx-late", late, 1000)
                .andExpect(jsonPath("$.header.responseCode").value("E-MIL-003"));
        String fire = contract("02", "20", today, today.plusDays(365), 100000, "E-AG-M");
        call("/api/contract/mileage/register", operator, "tx-fire", fire, 1000)
                .andExpect(jsonPath("$.header.responseCode").value("E-MIL-002"));
        call("/api/contract/mileage/register", operator, "tx-neg", no, -1)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-MIL-001"));
    }

    @Test
    void 만기_무렵_정산하면_1년_환산_주행거리_구간의_환급률로_환급액을_정한다() throws Exception {
        String yearly = registered(today.minusDays(350), today.plusDays(15), 600000, 20000);
        String txId = "tx-settle-" + yearly;
        call("/api/contract/mileage/settle", operator, txId, yearly, 24000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.milStatCd").value("20"))
                .andExpect(jsonPath("$.body.drivenKm").value(4000))
                .andExpect(jsonPath("$.body.annualKm").value(4000))
                .andExpect(jsonPath("$.body.refundRate").value(0.32))
                .andExpect(jsonPath("$.body.refundBasePrem").value(600000))
                .andExpect(jsonPath("$.body.refundAmt").value(192000));
        call("/api/contract/mileage/settle", operator, txId, yearly, 24000)
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.refundAmt").value(192000));
        settle(yearly, 25000).andExpect(jsonPath("$.header.responseCode").value("E-MIL-004"));

        // 짧은 계약은 1년으로 환산해서 구간을 찾는다 (2,000km 그대로면 40% 구간)
        String shortTerm = registered(today.minusDays(170), today.plusDays(10), 300000, 0);
        settle(shortTerm, 2000)
                .andExpect(jsonPath("$.body.annualKm").value(4056))
                .andExpect(jsonPath("$.body.refundRate").value(0.32))
                .andExpect(jsonPath("$.body.refundAmt").value(96000));

        settle(registered(today.minusDays(350), today.plusDays(15), 333333, 0), 4000)
                .andExpect(jsonPath("$.body.refundAmt").value(106666));
        settle(registered(today.minusDays(350), today.plusDays(15), 600000, 0), 16000)
                .andExpect(jsonPath("$.body.refundRate").value(0))
                .andExpect(jsonPath("$.body.refundAmt").value(0));
    }

    @Test
    void 정산은_기간_안에_시작보다_큰_주행거리로_가입한_계약만_한다() throws Exception {
        settle(registered(today.minusDays(320), today.plusDays(45), 600000, 0), 3000)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-MIL-003"));
        settle(registered(today.minusDays(350), today.plusDays(15), 600000, 20000), 19999)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-MIL-005"));
        String notJoined = contract("01", "20", today.minusDays(350), today.plusDays(15), 600000, "E-AG-M");
        settle(notJoined, 3000).andExpect(jsonPath("$.header.responseCode").value("E-MIL-004"));
    }

    @Test
    void 설계사는_본인_계약만_보상_담당은_조회만_한다() throws Exception {
        String mine = registered(today.minusDays(350), today.plusDays(15), 600000, 0);
        call("/api/contract/mileage/inquiry", TestAuth.as("E-AG-M", Roles.AGENT), "tx-a", mine, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.milStatCd").value("10"));
        call("/api/contract/mileage/settle", TestAuth.as("E-AG-OTHER", Roles.AGENT), "tx-b", mine, 3000)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
        call("/api/contract/mileage/settle", TestAuth.as("E-CLM-M", Roles.CLAIM), "tx-c", mine, 3000)
                .andExpect(status().isForbidden());
        call("/api/contract/mileage/inquiry", TestAuth.as("E-CLM-M", Roles.CLAIM), "tx-d", mine, null)
                .andExpect(status().isOk());
    }
}
