package com.insur.insurcoresystem.service.work;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractPayment;
import com.insur.insurcoresystem.domain.contract.ContractPaymentRepository;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistory;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistoryRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
 * 업무 현황·할 일함·갱신 대상 API 를 실제 DB 로 검증한다. 개발 DB 에 다른 계약이 있어도 결과가 흔들리지 않도록,
 * 건수는 테스트마다 새로 만든 설계사 사번(AGENT 본인 계약 제한)으로 확인한다. 테스트가 끝나면 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ContractRepository contractRepository;
    @Autowired
    ContractPaymentRepository paymentRepository;
    @Autowired
    ContractStatusHistoryRepository historyRepository;

    private String agent;
    private Contract unpaidToday;
    private Contract paidToday;
    private Contract stale;
    private Contract dueSoon;
    private Contract dueRenewed;

    private ResultActions call(String path, String body, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": {}, \"body\": " + body + " }"));
    }

    private Contract contract(String statCd, LocalDateTime regDt, LocalDate endDate, String regEmpId, String renewedFrom) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return contractRepository.save(Contract.builder()
                .contractNo("POL-W-" + suffix).applNo("APP-W-" + suffix).insrClsCd("01").ctrStatCd(statCd)
                .startDate(endDate.minusYears(1)).endDate(endDate).policyholderId("C0001").insuredId("C0002")
                .totalPremium(new BigDecimal("500000")).sysRegDt(regDt).regEmpId(regEmpId).renewedFrom(renewedFrom)
                .build());
    }

    /**
     * 설계사 한 명의 계약 묶음을 만든다.
     * 청약: 오늘 접수 미납 1, 오늘 접수 완납 1, 10일 전 접수 미납 1 / 유효: 30일 뒤 만기 1(갱신 대상), 30일 뒤 만기 이미 갱신 1, 90일 뒤 만기 1
     */
    @BeforeEach
    void setUp() {
        agent = "E-WK-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDate nextYear = TODAY.plusYears(1);
        unpaidToday = contract("10", NOW, nextYear, agent, null);
        paidToday = contract("10", NOW, nextYear, agent, null);
        stale = contract("10", NOW.minusDays(10), nextYear, agent, null);
        dueSoon = contract("20", NOW.minusYears(1), TODAY.plusDays(30), agent, null);
        dueRenewed = contract("20", NOW.minusYears(1), TODAY.plusDays(30), agent, null);
        contract("20", NOW.minusYears(1), TODAY.plusDays(90), agent, null);
        // 갱신 계약은 다른 사번이 접수했다. 설계사의 집계에는 안 잡히지만 원 계약은 이미 갱신된 것으로 본다
        contract("10", NOW.minusYears(1), TODAY.plusDays(395), "E-OTHER", dueRenewed.getContractNo());

        paymentRepository.save(ContractPayment.builder().contractNo(paidToday.getContractNo())
                .payAmt(new BigDecimal("500000")).payMethod("CARD").empId("E-OP").globalTxId("tx-wk-" + agent)
                .paidDt(NOW).build());
        historyRepository.save(ContractStatusHistory.builder().contractNo(dueSoon.getContractNo())
                .beforeStatCd("10").afterStatCd("20").empId("E-UW").globalTxId("tx-wk-h-" + agent).chgDt(NOW).build());
    }

    @Test
    void 설계사의_업무_현황은_본인_계약만_집계하고_볼_수_있는_할_일만_센다() throws Exception {
        call("/api/work/dashboard", "{}", TestAuth.as(agent, Roles.AGENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.responseCode").value("0000"))
                .andExpect(jsonPath("$.body.baseDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.body.ownContractsOnly").value(true))
                .andExpect(jsonPath("$.body.statusCounts[?(@.ctrStatCd=='10')].count").value(3))
                .andExpect(jsonPath("$.body.statusCounts[?(@.ctrStatCd=='20')].count").value(3))
                .andExpect(jsonPath("$.body.statusCounts[?(@.ctrStatCd=='20')].totalPremium").value(1500000))
                .andExpect(jsonPath("$.body.today.appliedCount").value(2))
                .andExpect(jsonPath("$.body.today.appliedPremium").value(1000000))
                .andExpect(jsonPath("$.body.today.approvedCount").value(1))
                .andExpect(jsonPath("$.body.today.paidCount").value(1))
                .andExpect(jsonPath("$.body.today.paidAmount").value(500000))
                // AGENT 는 승인 대기를 처리하지 않으므로 그 건수는 빠진다
                .andExpect(jsonPath("$.body.todoCounts[*].type").value(not(hasItem("PENDING_APPROVAL"))))
                .andExpect(jsonPath("$.body.todoCounts[?(@.type=='UNPAID')].count").value(2))
                .andExpect(jsonPath("$.body.todoCounts[?(@.type=='STALE_APPLICATION')].count").value(1))
                .andExpect(jsonPath("$.body.renewalDueCount").value(1));
    }

    @Test
    void 바디_없이_보내도_업무_현황을_조회할_수_있다() throws Exception {
        mockMvc.perform(post("/api/work/dashboard").with(TestAuth.as(agent, Roles.AGENT))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"header\": {} }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.statusCounts").isArray());
    }

    @Test
    void 미납_청약은_오래된_접수순으로_낸_금액과_남은_금액을_보여준다() throws Exception {
        String res = call("/api/work/todo", "{ \"type\": \"UNPAID\" }", TestAuth.as(agent, Roles.AGENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.type").value("UNPAID"))
                .andExpect(jsonPath("$.body.totalElements").value(2))
                .andExpect(jsonPath("$.body.items[0].contractNo").value(stale.getContractNo()))
                .andExpect(jsonPath("$.body.items[0].elapsedDays").value(10))
                .andExpect(jsonPath("$.body.items[0].paidPremium").value(0))
                .andExpect(jsonPath("$.body.items[0].remainingPremium").value(500000))
                .andExpect(jsonPath("$.body.items[1].contractNo").value(unpaidToday.getContractNo()))
                .andReturn().getResponse().getContentAsString();
        // 완납한 청약은 미납 목록에 나오지 않는다
        List<String> numbers = JsonPath.read(res, "$.body.items[*].contractNo");
        org.assertj.core.api.Assertions.assertThat(numbers).doesNotContain(paidToday.getContractNo());

        call("/api/work/todo", "{ \"type\": \"STALE_APPLICATION\" }", TestAuth.as(agent, Roles.AGENT))
                .andExpect(jsonPath("$.body.totalElements").value(1))
                .andExpect(jsonPath("$.body.items[0].contractNo").value(stale.getContractNo()));
    }

    @Test
    void 승인_대기에는_완납한_청약이_나오고_건수는_업무_현황과_같다() throws Exception {
        RequestPostProcessor underwriter = TestAuth.as("E-UW", Roles.UNDERWRITER);
        String dashboard = call("/api/work/dashboard", "{}", underwriter).andReturn().getResponse().getContentAsString();
        List<Integer> counts = JsonPath.read(dashboard, "$.body.todoCounts[?(@.type=='PENDING_APPROVAL')].count");

        String first = call("/api/work/todo", "{ \"type\": \"PENDING_APPROVAL\", \"size\": 100 }", underwriter)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer total = JsonPath.read(first, "$.body.totalElements");
        org.assertj.core.api.Assertions.assertThat(total).isEqualTo(counts.get(0));

        // 개발 DB 의 다른 청약 뒤에 있을 수 있으므로 모든 페이지에서 찾는다 (오래된 접수순이라 오늘 만든 건은 뒤쪽)
        Integer pages = JsonPath.read(first, "$.body.totalPages");
        boolean found = false;
        for (int page = 0; page < pages && !found; page++) {
            String res = call("/api/work/todo", "{ \"type\": \"PENDING_APPROVAL\", \"size\": 100, \"page\": " + page + " }",
                    underwriter).andReturn().getResponse().getContentAsString();
            List<String> numbers = JsonPath.read(res, "$.body.items[*].contractNo");
            found = numbers.contains(paidToday.getContractNo());
            org.assertj.core.api.Assertions.assertThat(numbers).doesNotContain(unpaidToday.getContractNo());
        }
        org.assertj.core.api.Assertions.assertThat(found).isTrue();
    }

    @Test
    void 처리_권한이_없는_할_일_유형은_403이고_없는_유형은_400이다() throws Exception {
        call("/api/work/todo", "{ \"type\": \"PENDING_APPROVAL\" }", TestAuth.as(agent, Roles.AGENT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-002"));
        call("/api/work/todo", "{ \"type\": \"STALE_APPLICATION\" }", TestAuth.as("E-OP", Roles.OPERATOR))
                .andExpect(status().isForbidden());
        call("/api/work/todo", "{ \"type\": \"NOPE\" }", TestAuth.emp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-WRK-001"));
    }

    @Test
    void 갱신_대상은_기간_안에_만기가_오고_아직_갱신하지_않은_유효_계약이다() throws Exception {
        call("/api/work/renewal-targets", "{}", TestAuth.as(agent, Roles.AGENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.withinDays").value(60))
                .andExpect(jsonPath("$.body.totalElements").value(1))
                .andExpect(jsonPath("$.body.items[0].contractNo").value(dueSoon.getContractNo()))
                .andExpect(jsonPath("$.body.items[0].daysToEnd").value(30))
                .andExpect(jsonPath("$.body.items[0].endDate").value(TODAY.plusDays(30).toString()));

        call("/api/work/renewal-targets", "{ \"withinDays\": 20 }", TestAuth.as(agent, Roles.AGENT))
                .andExpect(jsonPath("$.body.totalElements").value(0));
        call("/api/work/renewal-targets", "{ \"insrClsCd\": \"02\" }", TestAuth.as(agent, Roles.AGENT))
                .andExpect(jsonPath("$.body.totalElements").value(0));
    }

    @Test
    void 갱신_대상_조회_기간은_1에서_60일_사이다() throws Exception {
        call("/api/work/renewal-targets", "{ \"withinDays\": 61 }", TestAuth.emp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-WRK-002"));
        call("/api/work/renewal-targets", "{ \"withinDays\": 0 }", TestAuth.emp())
                .andExpect(status().isBadRequest());
    }

    @Test
    void 업무_API는_토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/work/dashboard").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-001"));
    }
}
