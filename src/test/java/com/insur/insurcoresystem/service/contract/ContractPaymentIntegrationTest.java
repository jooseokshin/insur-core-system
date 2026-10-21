package com.insur.insurcoresystem.service.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractPaymentRepository;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 납입(수납) API 와 "납입이 끝나야 승인" 규칙을 실제 DB 로 검증한다. 계약은 직접 저장하고, 끝나면 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContractPaymentIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ContractRepository contractRepository;
    @Autowired
    ContractPaymentRepository paymentRepository;

    private static final RequestPostProcessor OPERATOR = TestAuth.as("O400", Roles.OPERATOR);
    private static final RequestPostProcessor UNDERWRITER = TestAuth.as("U300", Roles.UNDERWRITER);

    private ResultActions call(String path, String txId, String body, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": " + body + " }"));
    }

    private ResultActions pay(String txId, String contractNo, String amt, String method, RequestPostProcessor auth) throws Exception {
        return call("/api/contract/payment", txId, "{ \"contractNo\": \"" + contractNo + "\", \"payAmt\": " + amt
                + ", \"payMethod\": \"" + method + "\" }", auth);
    }

    private ResultActions approve(String contractNo) throws Exception {
        return call("/api/contract/status-change", UUID.randomUUID().toString(),
                "{ \"contractNo\": \"" + contractNo + "\", \"ctrStatCd\": \"20\" }", UNDERWRITER);
    }

    private Contract applied(String total, String regEmpId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return contractRepository.save(Contract.builder().contractNo("POL-P-" + suffix).applNo("APP-P-" + suffix)
                .insrClsCd("01").ctrStatCd("10").startDate(LocalDate.now()).endDate(LocalDate.now().plusYears(1))
                .policyholderId("C0001").insuredId("C0002").totalPremium(new BigDecimal(total))
                .sysRegDt(LocalDateTime.now()).regEmpId(regEmpId).build());
    }

    @Test
    void 납입이_끝나기_전에는_승인할_수_없고_나누어_내서_채우면_승인된다() throws Exception {
        String no = applied("500000", "E100").getContractNo();

        approve(no).andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-028"));

        pay("tx-p-1", no, "200000", "TRANSFER", OPERATOR)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.responseCode").value("0000"))
                .andExpect(jsonPath("$.body.paidPremium").value(200000))
                .andExpect(jsonPath("$.body.remainingPremium").value(300000))
                .andExpect(jsonPath("$.body.fullyPaid").value(false));
        approve(no).andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-028"));   // 일부 납입으로는 안 된다

        pay("tx-p-over", no, "300001", "CARD", OPERATOR).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-027"));
        pay("tx-p-2", no, "300000", "CARD", OPERATOR).andExpect(jsonPath("$.body.fullyPaid").value(true));

        approve(no).andExpect(status().isOk()).andExpect(jsonPath("$.body.afterCtrStatCd").value("20"));
        // 승인된 계약에는 더 낼 수 없다
        pay("tx-p-3", no, "1000", "CASH", OPERATOR).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-026"));
    }

    @Test
    void 납입_내역은_오래된_순으로_페이지를_나누고_현황은_전체_기준이다() throws Exception {
        String no = applied("500000", "E100").getContractNo();
        pay("tx-h-1", no, "100000", "CASH", OPERATOR).andExpect(status().isOk());
        pay("tx-h-2", no, "150000", "CARD", OPERATOR).andExpect(status().isOk());
        pay("tx-h-3", no, "50000", "TRANSFER", OPERATOR).andExpect(status().isOk());

        call("/api/contract/payment-history", "tx-h", "{ \"contractNo\": \"" + no + "\", \"size\": 2 }", UNDERWRITER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.totalElements").value(3))
                .andExpect(jsonPath("$.body.totalPages").value(2))
                .andExpect(jsonPath("$.body.items.length()").value(2))
                .andExpect(jsonPath("$.body.items[0].payAmt").value(100000))
                .andExpect(jsonPath("$.body.items[0].payMethod").value("CASH"))
                .andExpect(jsonPath("$.body.items[0].empId").value("O400"))
                .andExpect(jsonPath("$.body.items[1].payAmt").value(150000))
                .andExpect(jsonPath("$.body.paidPremium").value(300000))
                .andExpect(jsonPath("$.body.remainingPremium").value(200000));
    }

    @Test
    void 같은_거래UUID_재전송은_한_번만_반영되고_다른_내용은_거절한다() throws Exception {
        String no = applied("500000", "E100").getContractNo();
        pay("tx-r-1", no, "100000", "CASH", OPERATOR).andExpect(status().isOk());
        pay("tx-r-1", no, "100000", "CASH", OPERATOR).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.paidPremium").value(100000));   // 두 번 더해지지 않는다
        assertThat(paymentRepository.sumPaid(no)).isEqualByComparingTo("100000");
        pay("tx-r-1", no, "200000", "CASH", OPERATOR).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-019"));
        pay("tx-r-1", no, "100000", "CASH", TestAuth.as("O401", Roles.OPERATOR)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-015"));
    }

    @Test
    void 입력_오류는_400이고_없는_계약은_404다() throws Exception {
        String no = applied("500000", "E100").getContractNo();
        pay("tx-e-1", no, "0", "CASH", OPERATOR).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-007"));
        pay("tx-e-2", no, "1000", "GOLD", OPERATOR).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-029"));
        pay("tx-e-3", "POL-NOPE", "1000", "CASH", OPERATOR).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
    }

    @Test
    void 납입은_계약_관리와_ADMIN만_내역은_모든_역할이_조회하되_AGENT는_본인_접수_계약만이다() throws Exception {
        String no = applied("500000", "E100").getContractNo();
        for (String role : new String[] {Roles.AGENT, Roles.UNDERWRITER}) {
            pay("tx-a-" + role, no, "1000", "CASH", TestAuth.withRoles(role)).andExpect(status().isForbidden());
        }
        pay("tx-a-adm", no, "1000", "CASH", TestAuth.as("A500", Roles.ADMIN)).andExpect(status().isOk());

        String history = "{ \"contractNo\": \"" + no + "\" }";
        call("/api/contract/payment-history", "tx-s", history, TestAuth.as("E100", Roles.AGENT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.items.length()").value(1));
        call("/api/contract/payment-history", "tx-s", history, TestAuth.as("E200", Roles.AGENT)).andExpect(status().isNotFound());
        call("/api/contract/payment-history", "tx-s", history, TestAuth.as("O400", Roles.OPERATOR)).andExpect(status().isOk());
    }
}
