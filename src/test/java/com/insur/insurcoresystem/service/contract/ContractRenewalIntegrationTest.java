package com.insur.insurcoresystem.service.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.domain.contract.Coverage;
import com.insur.insurcoresystem.domain.contract.CoverageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
 * 계약 갱신 API 를 실제 DB 로 검증한다. 원 계약은 직접 저장하고(유효 상태, 보험종기가 갱신 가능 기간 안), 테스트가 끝나면 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContractRenewalIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ContractRepository contractRepository;
    @Autowired
    CoverageRepository coverageRepository;

    private ResultActions renew(String txId, String body, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post("/api/contract/renew").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": " + body + " }"));
    }

    private ResultActions renewNo(String txId, String contractNo) throws Exception {
        return renew(txId, "{ \"contractNo\": \"" + contractNo + "\" }", TestAuth.as("E100", Roles.AGENT));
    }

    /** 자동차 계약 한 건을 저장한다. 보험종기까지 남은 일수로 갱신 가능 기간 여부를 정한다. */
    private Contract source(String statCd, int daysToEnd, String regEmpId) {
        LocalDate end = TODAY.plusDays(daysToEnd);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Contract contract = contractRepository.save(Contract.builder()
                .contractNo("POL-T-" + suffix).applNo("APP-T-" + suffix).insrClsCd("01").ctrStatCd(statCd)
                .startDate(end.minusYears(1)).endDate(end).policyholderId("C0001").insuredId("C0002")
                .totalPremium(new BigDecimal("500000")).sysRegDt(LocalDateTime.now()).regEmpId(regEmpId)
                .driverAge(35).discountGrade("3").build());
        coverageRepository.save(Coverage.builder().contract(contract).cvrgCd("CA001")
                .isrdAmt(new BigDecimal("100000000")).prem(new BigDecimal("500000")).build());
        return contract;
    }

    private static String expectedPremium(Contract source, String factor) {
        long days = ChronoUnit.DAYS.between(source.getEndDate(), source.getEndDate().plusYears(1));
        return new BigDecimal("100000000").multiply(new BigDecimal("0.005")).multiply(new BigDecimal(factor))
                .multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(365), 0, RoundingMode.HALF_UP).toPlainString();
    }

    @Test
    void 유효_계약을_갱신하면_기간을_이어받은_새_청약이_만들어지고_원_계약이_연결된다() throws Exception {
        Contract source = source("20", 20, "E100");

        renewNo("tx-rn-1", source.getContractNo())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.responseCode").value("0000"))
                .andExpect(jsonPath("$.body.ctrStatCd").value("10"))
                .andExpect(jsonPath("$.body.renewedFrom").value(source.getContractNo()))
                .andExpect(jsonPath("$.body.startDate").value(source.getEndDate().toString()))
                .andExpect(jsonPath("$.body.endDate").value(source.getEndDate().plusYears(1).toString()))
                .andExpect(jsonPath("$.body.previousTotalPremium").value(500000))
                .andExpect(jsonPath("$.body.totalPremium").value(Long.parseLong(expectedPremium(source, "1.0"))))
                .andExpect(jsonPath("$.body.coverageCount").value(1));

        Contract renewal = contractRepository.findByGlobalTxId("tx-rn-1").orElseThrow();
        assertThat(renewal.getRenewedFrom()).isEqualTo(source.getContractNo());
        assertThat(renewal.getRegEmpId()).isEqualTo("E100");
        assertThat(renewal.getPolicyholderId()).isEqualTo("C0001");
        assertThat(renewal.getDriverAge()).isEqualTo(35);   // 요율 계수도 이어받는다
        assertThat(coverageRepository.findByContractContractNo(renewal.getContractNo())).singleElement()
                .satisfies(c -> assertThat(c.getCvrgCd()).isEqualTo("CA001"));

        // 조회 응답에 원 계약이 나온다
        mockMvc.perform(post("/api/contract/inquiry").with(TestAuth.emp()).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"header\": {}, \"body\": { \"contractNo\": \"" + renewal.getContractNo() + "\" } }"))
                .andExpect(jsonPath("$.body.renewedFrom").value(source.getContractNo()));
    }

    @Test
    void 요율_계수를_넘기면_원_계약_값_대신_그_값으로_보험료를_다시_산출한다() throws Exception {
        Contract source = source("20", 20, "E100");

        renew("tx-rn-r", "{ \"contractNo\": \"" + source.getContractNo() + "\", \"rating\": { \"driverAge\": 35, \"discountGrade\": \"1\" } }",
                TestAuth.as("E100", Roles.AGENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.totalPremium").value(Long.parseLong(expectedPremium(source, "0.8"))));
        assertThat(contractRepository.findByGlobalTxId("tx-rn-r").orElseThrow().getDiscountGrade()).isEqualTo("1");
    }

    @Test
    void 같은_거래UUID_재전송은_처음_결과를_돌려주고_다른_원_계약이나_다른_사번은_거절한다() throws Exception {
        Contract source = source("20", 20, "E100");
        Contract other = source("20", 20, "E100");
        String first = renewNo("tx-rn-2", source.getContractNo()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        renewNo("tx-rn-2", source.getContractNo()).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.contractNo").value(
                        com.jayway.jsonpath.JsonPath.<String>read(first, "$.body.contractNo")))
                .andExpect(jsonPath("$.body.renewedFrom").value(source.getContractNo()));
        renewNo("tx-rn-2", other.getContractNo()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-019"));
        renew("tx-rn-2", "{ \"contractNo\": \"" + source.getContractNo() + "\" }", TestAuth.as("E200", Roles.AGENT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-015"));
    }

    @Test
    void 이미_갱신한_계약은_다시_갱신할_수_없고_갱신_계약을_청약취소하면_다시_갱신할_수_있다() throws Exception {
        Contract source = source("20", 20, "E100");
        renewNo("tx-rn-3", source.getContractNo()).andExpect(status().isOk());

        renewNo("tx-rn-4", source.getContractNo()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-025"));

        Contract renewal = contractRepository.findByGlobalTxId("tx-rn-3").orElseThrow();
        renewal.changeStatus(ContractStatus.CANCELED);
        contractRepository.saveAndFlush(renewal);
        renewNo("tx-rn-5", source.getContractNo()).andExpect(status().isOk());
    }

    @Test
    void 유효_상태가_아니거나_갱신_가능_기간이_아니면_거절한다() throws Exception {
        renewNo("tx-rn-6", source("10", 20, "E100").getContractNo()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-023"));
        renewNo("tx-rn-7", source("20", 61, "E100").getContractNo()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-024"));   // 아직 이르다
        renewNo("tx-rn-8", source("20", -1, "E100").getContractNo()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-024"));   // 이미 만기가 지났다
        renewNo("tx-rn-9", source("20", 60, "E100").getContractNo()).andExpect(status().isOk());   // 경계: 60일 전
        renewNo("tx-rn-10", source("20", 0, "E100").getContractNo()).andExpect(status().isOk());   // 경계: 보험종기 당일
    }

    @Test
    void 입력_오류와_없는_계약과_접수자_제한() throws Exception {
        renew("tx-rn-11", "{}", TestAuth.as("E100", Roles.AGENT)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-009"));
        renewNo("tx-rn-12", "POL-NOPE").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
        // 다른 접수자의 계약은 AGENT 에게 없는 계약과 똑같이 보이고, ADMIN 은 갱신할 수 있다
        Contract others = source("20", 20, "E999");
        renewNo("tx-rn-13", others.getContractNo()).andExpect(status().isNotFound());
        renew("tx-rn-14", "{ \"contractNo\": \"" + others.getContractNo() + "\" }", TestAuth.as("A500", Roles.ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void 갱신은_청약_접수_담당과_ADMIN만_할_수_있다() throws Exception {
        Contract source = source("20", 20, "E100");
        for (String role : new String[] {Roles.UNDERWRITER, Roles.OPERATOR}) {
            renew("tx-rn-r", "{ \"contractNo\": \"" + source.getContractNo() + "\" }", TestAuth.withRoles(role))
                    .andExpect(status().isForbidden());
        }
        assertThat(contractRepository.findByGlobalTxId("tx-rn-r")).isEmpty();
    }

    private ResultActions preview(String body, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post("/api/contract/renew-quote").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": {}, \"body\": " + body + " }"));
    }

    @Test
    void 미리보기는_저장하지_않고_갱신과_같은_보험료와_원_계약과의_차이를_보여준다() throws Exception {
        Contract source = source("20", 20, "E100");
        long expected = Long.parseLong(expectedPremium(source, "1.0"));
        long contracts = contractRepository.count();

        preview("{ \"contractNo\": \"" + source.getContractNo() + "\" }", TestAuth.as("E100", Roles.AGENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.renewedFrom").value(source.getContractNo()))
                .andExpect(jsonPath("$.body.startDate").value(source.getEndDate().toString()))
                .andExpect(jsonPath("$.body.endDate").value(source.getEndDate().plusYears(1).toString()))
                .andExpect(jsonPath("$.body.previousTotalPremium").value(500000))
                .andExpect(jsonPath("$.body.totalPremium").value(expected))
                .andExpect(jsonPath("$.body.premiumDifference").value(expected - 500000))
                .andExpect(jsonPath("$.body.requiredFactors.length()").value(2))
                .andExpect(jsonPath("$.body.coverages[0].cvrgCd").value("CA001"))
                .andExpect(jsonPath("$.body.coverages[0].previousPrem").value(500000))
                .andExpect(jsonPath("$.body.coverages[0].prem").value(expected));
        assertThat(contractRepository.count()).isEqualTo(contracts);   // 아무것도 저장하지 않았다

        // 요율 계수를 바꿔 보면 보험료가 달라지고, 이어서 같은 요청으로 갱신하면 그 보험료가 된다
        String withRating = "{ \"contractNo\": \"" + source.getContractNo() + "\", \"rating\": { \"driverAge\": 35, \"discountGrade\": \"1\" } }";
        long discounted = Long.parseLong(expectedPremium(source, "0.8"));
        preview(withRating, TestAuth.as("E100", Roles.AGENT)).andExpect(jsonPath("$.body.totalPremium").value(discounted))
                .andExpect(jsonPath("$.body.combinedFactor").value(0.8));
        renew("tx-pv-1", withRating, TestAuth.as("E100", Roles.AGENT)).andExpect(jsonPath("$.body.totalPremium").value(discounted));
    }

    @Test
    void 미리보기도_갱신과_같은_검증과_권한을_따른다() throws Exception {
        preview("{}", TestAuth.as("E100", Roles.AGENT)).andExpect(jsonPath("$.header.responseCode").value("E-CTR-009"));
        preview("{ \"contractNo\": \"" + source("10", 20, "E100").getContractNo() + "\" }", TestAuth.as("E100", Roles.AGENT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-023"));
        preview("{ \"contractNo\": \"" + source("20", 90, "E100").getContractNo() + "\" }", TestAuth.as("E100", Roles.AGENT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-024"));
        Contract others = source("20", 20, "E999");
        preview("{ \"contractNo\": \"" + others.getContractNo() + "\" }", TestAuth.as("E100", Roles.AGENT)).andExpect(status().isNotFound());
        preview("{ \"contractNo\": \"" + others.getContractNo() + "\" }", TestAuth.withRoles(Roles.UNDERWRITER)).andExpect(status().isForbidden());

        // 이미 갱신한 계약은 미리보기도 막는다
        Contract source = source("20", 20, "E100");
        renewNo("tx-pv-2", source.getContractNo()).andExpect(status().isOk());
        preview("{ \"contractNo\": \"" + source.getContractNo() + "\" }", TestAuth.as("E100", Roles.AGENT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-025"));
    }
}
