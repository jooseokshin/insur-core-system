package com.insur.insurcoresystem.service.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.Coverage;
import com.insur.insurcoresystem.domain.contract.CoverageRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보상(사고 접수 → 손해사정 → 지급·면책·취소)을 실제 DB 로 검증한다. 계약은 V20 시드 상품(CAR-STD-2026)으로 직접 저장하고,
 * 테스트가 끝나면 롤백된다. 자기차량손해(CA004)의 자기부담금은 상품 기준 200,000원이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ClaimIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ContractRepository contractRepository;
    @Autowired
    CoverageRepository coverageRepository;

    private String agent;
    private Contract contract;
    private final RequestPostProcessor claimHandler = TestAuth.as("E-CLM1", Roles.CLAIM);
    private final RequestPostProcessor admin = TestAuth.as("E-ADM1", Roles.ADMIN);

    @BeforeEach
    void setUp() {
        agent = "E-CA-" + UUID.randomUUID().toString().substring(0, 8);
        contract = contract("20", TODAY.minusDays(100), TODAY.plusDays(265), null);
    }

    /** CAR-STD-2026 계약: 대인 1억, 대물 2천만, 자기차량 1천만 */
    private Contract contract(String statCd, LocalDate start, LocalDate end, LocalDate termDate) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Contract saved = contractRepository.save(Contract.builder()
                .contractNo("POL-C-" + suffix).applNo("APP-C-" + suffix).insrClsCd("01").prdCd("CAR-STD-2026").ctrStatCd(statCd)
                .startDate(start).endDate(end).termDate(termDate).policyholderId("C-HOLDER").insuredId("C-INSURED")
                .totalPremium(new BigDecimal("1000000")).sysRegDt(LocalDateTime.now()).regEmpId(agent)
                .driverAge(35).discountGrade("3").build());
        coverageRepository.save(Coverage.builder().contract(saved).cvrgCd("CA001").isrdAmt(new BigDecimal("100000000")).prem(new BigDecimal("500000")).build());
        coverageRepository.save(Coverage.builder().contract(saved).cvrgCd("CA002").isrdAmt(new BigDecimal("20000000")).prem(new BigDecimal("350000")).build());
        coverageRepository.save(Coverage.builder().contract(saved).cvrgCd("CA004").isrdAmt(new BigDecimal("10000000")).prem(new BigDecimal("150000")).build());
        return saved;
    }

    private ResultActions call(String path, String txId, String body, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": " + body + " }"));
    }

    private ResultActions report(String txId, Contract target, LocalDate acdtDt, String cvrgCd, long amount) throws Exception {
        return call("/api/claim/report", txId, "{ \"contractNo\": \"" + target.getContractNo() + "\", \"acdtDt\": \"" + acdtDt
                + "\", \"acdtDesc\": \"주차장 접촉 사고\", \"coverages\": [{ \"cvrgCd\": \"" + cvrgCd + "\", \"amount\": " + amount + " }] }",
                TestAuth.as(agent, Roles.AGENT));
    }

    private String reportNo(Contract target, String cvrgCd, long amount) throws Exception {
        String res = report("tx-c-" + UUID.randomUUID(), target, TODAY.minusDays(1), cvrgCd, amount)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.body.clmNo");
    }

    private ResultActions assess(String clmNo, String cvrgCd, long amount) throws Exception {
        return call("/api/claim/assess", "tx-a-" + UUID.randomUUID(), "{ \"clmNo\": \"" + clmNo + "\", \"coverages\": [{ \"cvrgCd\": \""
                + cvrgCd + "\", \"amount\": " + amount + " }] }", claimHandler);
    }

    private ResultActions decide(String clmNo, String decision, String memo, RequestPostProcessor auth) throws Exception {
        return call("/api/claim/decide", "tx-d-" + UUID.randomUUID(), "{ \"clmNo\": \"" + clmNo + "\", \"decision\": \"" + decision
                + "\"" + (memo == null ? "" : ", \"memo\": \"" + memo + "\"") + " }", auth);
    }

    @Test
    void 접수_손해사정_지급까지_자기부담금을_빼고_이력이_남는다() throws Exception {
        String clmNo = JsonPath.read(report("tx-c-1-" + agent, contract, TODAY.minusDays(3), "CA004", 3_000_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.clmStatCd").value("10"))
                .andExpect(jsonPath("$.body.claimantId").value("C-INSURED"))
                .andExpect(jsonPath("$.body.totalClaimAmt").value(3000000))
                .andReturn().getResponse().getContentAsString(), "$.body.clmNo");
        assertThat(clmNo).startsWith("CLM-CAR-" + TODAY.getYear() + "-");

        assess(clmNo, "CA004", 2_500_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.clmStatCd").value("20"))
                .andExpect(jsonPath("$.body.items[0].deductibleAmt").value(200000))
                .andExpect(jsonPath("$.body.items[0].payAmt").value(2300000))
                .andExpect(jsonPath("$.body.totalPayAmt").value(2300000))
                .andExpect(jsonPath("$.body.highValue").value(false));

        decide(clmNo, "PAY", null, claimHandler)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.clmStatCd").value("40"))
                .andExpect(jsonPath("$.body.decideEmpId").value("E-CLM1"))
                .andExpect(jsonPath("$.body.histories.length()").value(3))
                .andExpect(jsonPath("$.body.histories[0].afterStatCd").value("10"))
                .andExpect(jsonPath("$.body.histories[2].afterStatCd").value("40"));

        // 설계사 업무 현황의 보상 현황에 오늘 지급이 잡힌다
        call("/api/work/dashboard", "dash", "{}", TestAuth.as(agent, Roles.AGENT))
                .andExpect(jsonPath("$.body.claims.todayPaidCount").value(1))
                .andExpect(jsonPath("$.body.claims.todayPaidAmount").value(2300000))
                .andExpect(jsonPath("$.body.claims.todayReportedCount").value(1));
        // 심사 담당의 업무 현황에는 보상 현황이 없다
        call("/api/work/dashboard", "dash2", "{}", TestAuth.as("E-UW", Roles.UNDERWRITER))
                .andExpect(jsonPath("$.body.claims").doesNotExist());
    }

    @Test
    void 사고일은_보장_기간_안이고_오늘_이전이어야_한다() throws Exception {
        report("tx-p1-" + agent, contract, TODAY.minusDays(101), "CA001", 1_000_000).andExpect(jsonPath("$.header.responseCode").value("E-CLM-003"));
        report("tx-p2-" + agent, contract, TODAY.plusDays(1), "CA001", 1_000_000).andExpect(jsonPath("$.header.responseCode").value("E-CLM-003"));
        report("tx-p3-" + agent, contract, TODAY.minusDays(100), "CA001", 1_000_000).andExpect(status().isOk());

        // 해지 계약은 해지일 전날까지 보장한다
        Contract terminated = contract("30", TODAY.minusDays(200), TODAY.plusDays(165), TODAY.minusDays(10));
        report("tx-p4-" + agent, terminated, TODAY.minusDays(11), "CA001", 1_000_000).andExpect(status().isOk());
        report("tx-p5-" + agent, terminated, TODAY.minusDays(10), "CA001", 1_000_000).andExpect(jsonPath("$.header.responseCode").value("E-CLM-003"));
    }

    @Test
    void 청약_상태_계약이나_가입하지_않은_담보는_접수할_수_없다() throws Exception {
        Contract applied = contract("10", TODAY.plusDays(1), TODAY.plusDays(366), null);
        report("tx-s1-" + agent, applied, TODAY, "CA001", 1_000_000)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CLM-002"));
        report("tx-s2-" + agent, contract, TODAY, "CA003", 1_000_000).andExpect(jsonPath("$.header.responseCode").value("E-CLM-004"));
        report("tx-s3-" + agent, contract, TODAY, "CA001", 0).andExpect(jsonPath("$.header.responseCode").value("E-CLM-005"));
        // 다른 설계사의 계약은 없는 것과 같다
        call("/api/claim/report", "tx-s4-" + agent, "{ \"contractNo\": \"" + contract.getContractNo() + "\", \"acdtDt\": \"" + TODAY
                + "\", \"acdtDesc\": \"사고\", \"coverages\": [{ \"cvrgCd\": \"CA001\", \"amount\": 1000 }] }", TestAuth.as("E-OTHER", Roles.AGENT))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
    }

    @Test
    void 같은_담보의_지급은_가입금액을_넘지_않는다() throws Exception {
        String first = reportNo(contract, "CA004", 9_000_000);
        assess(first, "CA004", 8_200_000).andExpect(jsonPath("$.body.items[0].payAmt").value(8000000));
        decide(first, "PAY", null, claimHandler).andExpect(status().isOk());

        // 남은 가입금액 2,000,000 이 상한이다
        String second = reportNo(contract, "CA004", 5_000_000);
        assess(second, "CA004", 5_000_000)
                .andExpect(jsonPath("$.body.items[0].paidByOthers").value(8000000))
                .andExpect(jsonPath("$.body.items[0].payAmt").value(2000000));
    }

    @Test
    void 사정_후_다른_사고가_한도를_쓰면_지급하지_않고_재사정을_요구한다() throws Exception {
        String a = reportNo(contract, "CA004", 9_000_000);
        String b = reportNo(contract, "CA004", 9_000_000);
        assess(a, "CA004", 8_200_000).andExpect(jsonPath("$.body.items[0].payAmt").value(8000000));
        assess(b, "CA004", 8_200_000).andExpect(jsonPath("$.body.items[0].payAmt").value(8000000));
        decide(b, "PAY", null, claimHandler).andExpect(status().isOk());

        decide(a, "PAY", null, claimHandler)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CLM-012"));
        assess(a, "CA004", 8_200_000).andExpect(jsonPath("$.body.items[0].payAmt").value(2000000));
        decide(a, "PAY", null, claimHandler).andExpect(jsonPath("$.body.clmStatCd").value("40"));
    }

    @Test
    void 고액_지급은_관리자만_할_수_있다() throws Exception {
        String clmNo = reportNo(contract, "CA001", 30_000_000);
        assess(clmNo, "CA001", 20_000_000).andExpect(jsonPath("$.body.highValue").value(true));
        call("/api/claim/list", "l", "{ \"highValueOnly\": true, \"contractNo\": \"" + contract.getContractNo() + "\" }", claimHandler)
                .andExpect(jsonPath("$.body.totalElements").value(1));

        decide(clmNo, "PAY", null, claimHandler)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.responseCode").value("E-CLM-008"));
        decide(clmNo, "PAY", null, admin).andExpect(status().isOk()).andExpect(jsonPath("$.body.clmStatCd").value("40"));
    }

    @Test
    void 면책은_사유가_필요하고_접수_취소는_사정_전에만_된다() throws Exception {
        String deny = reportNo(contract, "CA002", 1_000_000);
        decide(deny, "DENY", null, claimHandler).andExpect(jsonPath("$.header.responseCode").value("E-CLM-007"));
        decide(deny, "DENY", "음주 운전 면책", claimHandler)
                .andExpect(jsonPath("$.body.clmStatCd").value("80"))
                .andExpect(jsonPath("$.body.totalPayAmt").value(0))
                .andExpect(jsonPath("$.body.decideMemo").value("음주 운전 면책"));

        String withdraw = reportNo(contract, "CA002", 1_000_000);
        decide(withdraw, "WITHDRAW", null, TestAuth.as(agent, Roles.AGENT)).andExpect(jsonPath("$.body.clmStatCd").value("90"));

        String assessed = reportNo(contract, "CA002", 1_000_000);
        assess(assessed, "CA002", 500_000);
        decide(assessed, "WITHDRAW", null, claimHandler)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CLM-006"));
    }

    @Test
    void 설계사는_손해사정과_지급을_할_수_없다() throws Exception {
        String clmNo = reportNo(contract, "CA002", 1_000_000);
        call("/api/claim/assess", "tx-x", "{ \"clmNo\": \"" + clmNo + "\", \"coverages\": [] }", TestAuth.as(agent, Roles.AGENT))
                .andExpect(status().isForbidden());
        decide(clmNo, "PAY", null, TestAuth.as(agent, Roles.AGENT))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.responseCode").value("E-AUTH-002"));
        // 다른 설계사는 조회도 할 수 없다
        call("/api/claim/inquiry", "i", "{ \"clmNo\": \"" + clmNo + "\" }", TestAuth.as("E-OTHER", Roles.AGENT))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.responseCode").value("E-CLM-010"));
    }

    @Test
    void 같은_거래UUID는_한_번만_처리한다() throws Exception {
        String txId = "tx-i-" + agent;
        String first = JsonPath.read(report(txId, contract, TODAY.minusDays(1), "CA002", 1_000_000)
                .andReturn().getResponse().getContentAsString(), "$.body.clmNo");
        report(txId, contract, TODAY.minusDays(1), "CA002", 1_000_000).andExpect(jsonPath("$.body.clmNo").value(first));
        report(txId, contract, TODAY.minusDays(1), "CA002", 2_000_000)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CLM-009"));

        String assessTx = "tx-ia-" + agent;
        call("/api/claim/assess", assessTx, "{ \"clmNo\": \"" + first + "\", \"coverages\": [{ \"cvrgCd\": \"CA002\", \"amount\": 700000 }] }", claimHandler)
                .andExpect(jsonPath("$.body.totalPayAmt").value(700000));
        // 같은 거래 UUID 로 다른 금액을 다시 보내도 다시 사정하지 않는다
        call("/api/claim/assess", assessTx, "{ \"clmNo\": \"" + first + "\", \"coverages\": [{ \"cvrgCd\": \"CA002\", \"amount\": 900000 }] }", claimHandler)
                .andExpect(jsonPath("$.body.totalPayAmt").value(700000))
                .andExpect(jsonPath("$.body.histories.length()").value(2));
    }

    @Test
    void 진행_중인_사고가_있으면_계약을_해지할_수_없다() throws Exception {
        String clmNo = reportNo(contract, "CA002", 1_000_000);
        String change = "{ \"contractNo\": \"" + contract.getContractNo() + "\", \"ctrStatCd\": \"30\" }";
        call("/api/contract/status-change", "tx-t1-" + agent, change, admin)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-030"));

        decide(clmNo, "DENY", "보장 대상 아님", claimHandler).andExpect(status().isOk());
        call("/api/contract/status-change", "tx-t2-" + agent, change, admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.afterCtrStatCd").value("30"));
    }

    @Test
    void 보상_담당은_계약을_조회할_수_있고_목록은_설계사_본인_건만_나온다() throws Exception {
        reportNo(contract, "CA002", 1_000_000);
        call("/api/contract/inquiry", "ci", "{ \"contractNo\": \"" + contract.getContractNo() + "\" }", claimHandler)
                .andExpect(status().isOk());
        call("/api/claim/list", "cl", "{ \"contractNo\": \"" + contract.getContractNo() + "\" }", TestAuth.as(agent, Roles.AGENT))
                .andExpect(jsonPath("$.body.totalElements").value(1));
        call("/api/claim/list", "cl2", "{ \"contractNo\": \"" + contract.getContractNo() + "\" }", TestAuth.as("E-OTHER", Roles.AGENT))
                .andExpect(jsonPath("$.body.totalElements").value(0));
    }
}
