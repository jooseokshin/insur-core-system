package com.insur.insurcoresystem.service.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 조회와 상품 구성 검증(산출, 청약, 갱신)을 실제 DB 와 V20 시드 상품으로 검증한다. 테스트가 끝나면 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final String RATING = "\"rating\": { \"driverAge\": 35, \"discountGrade\": \"3\" }";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ContractRepository contractRepository;
    @Autowired
    CoverageRepository coverageRepository;

    private ResultActions call(String path, String txId, String body) throws Exception {
        return mockMvc.perform(post(path).with(TestAuth.emp()).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": " + body + " }"));
    }

    private ResultActions quote(String prdCd, String coverages) throws Exception {
        return call("/api/premium/quote", "q", "{ \"insrClsCd\": \"01\", \"prdCd\": \"" + prdCd + "\", \"startDate\": \""
                + TODAY.plusDays(1) + "\", \"endDate\": \"" + TODAY.plusDays(1).plusYears(1) + "\", " + RATING
                + ", \"coverages\": [" + coverages + "] }");
    }

    private static String cvrg(String code, long amount) {
        return "{ \"cvrgCd\": \"" + code + "\", \"isrdAmt\": " + amount + " }";
    }

    @Test
    void 판매_중인_상품만_고를_수_있고_담보마다_구분과_한도와_자기부담금이_있다() throws Exception {
        call("/api/product/list", "l", "{ \"insrClsCd\": \"01\", \"onSaleOnly\": true }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.items[*].prdCd").value(hasItem("CAR-STD-2026")))
                .andExpect(jsonPath("$.body.items[*].prdCd").value(not(hasItem("CAR-STD-2025"))))
                .andExpect(jsonPath("$.body.items[*].prdCd").value(not(hasItem("FIRE-HOME-2026"))));

        call("/api/product/list", "l2", "{ \"prdCd\": \"CAR-STD-2026\" }")
                .andExpect(jsonPath("$.body.items[0].onSale").value(true))
                .andExpect(jsonPath("$.body.items[0].coverages[0].cvrgCd").value("CA001"))
                .andExpect(jsonPath("$.body.items[0].coverages[0].cvrgType").value("MAIN"))
                .andExpect(jsonPath("$.body.items[0].coverages[0].cvrgNm").value("대인배상"))
                .andExpect(jsonPath("$.body.items[0].coverages[3].cvrgType").value("RIDER"))
                .andExpect(jsonPath("$.body.items[0].coverages[3].required").value(false))
                .andExpect(jsonPath("$.body.items[0].coverages[3].deductibleAmt").value(200000));
        call("/api/product/list", "l3", "{ \"prdCd\": \"CAR-STD-2025\" }")
                .andExpect(jsonPath("$.body.items[0].onSale").value(false));
    }

    @Test
    void 상품을_지정한_산출은_담보_구성과_가입금액_한도를_검증한다() throws Exception {
        quote("CAR-STD-2026", cvrg("CA001", 100_000_000) + "," + cvrg("CA002", 20_000_000) + "," + cvrg("CA004", 30_000_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.coverages.length()").value(3));

        quote("CAR-STD-2026", cvrg("CA001", 100_000_000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-PRD-005"))
                .andExpect(jsonPath("$.header.responseMsg").value("상품 CAR-STD-2026의 필수 담보가 빠졌습니다: CA002"));
        quote("CAR-ECO-2026", cvrg("CA001", 50_000_000) + "," + cvrg("CA002", 20_000_000) + "," + cvrg("CA004", 5_000_000))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRD-004"));
        quote("CAR-ECO-2026", cvrg("CA001", 200_000_000) + "," + cvrg("CA002", 20_000_000))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRD-006"));
        quote("FIRE-HOME-2026", cvrg("CA001", 100_000_000))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRD-002"));
        quote("NOPE", cvrg("CA001", 100_000_000))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRD-001"));
        // 판매 종료 상품은 새 청약용 산출에 쓸 수 없다
        quote("CAR-STD-2025", cvrg("CA001", 100_000_000) + "," + cvrg("CA002", 20_000_000))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRD-003"));
    }

    @Test
    void 상품을_지정한_청약은_계약에_상품이_저장되고_조회에_상품명이_나온다() throws Exception {
        String quote = quote("CAR-STD-2026", cvrg("CA001", 100_000_000) + "," + cvrg("CA002", 20_000_000))
                .andReturn().getResponse().getContentAsString();
        long prem1 = ((Number) JsonPath.read(quote, "$.body.coverages[0].prem")).longValue();
        long prem2 = ((Number) JsonPath.read(quote, "$.body.coverages[1].prem")).longValue();
        String txId = "tx-prd-" + UUID.randomUUID();

        call("/api/contract/apply", txId, "{ \"insrClsCd\": \"01\", \"prdCd\": \"CAR-STD-2026\", \"startDate\": \""
                + TODAY.plusDays(1) + "\", \"endDate\": \"" + TODAY.plusDays(1).plusYears(1) + "\", \"policyholderId\": \"C1\", "
                + "\"insuredId\": \"C1\", \"totalPremium\": " + (prem1 + prem2) + ", " + RATING + ", \"coverages\": ["
                + "{ \"cvrgCd\": \"CA001\", \"isrdAmt\": 100000000, \"prem\": " + prem1 + " },"
                + "{ \"cvrgCd\": \"CA002\", \"isrdAmt\": 20000000, \"prem\": " + prem2 + " }] }")
                .andExpect(status().isOk());

        Contract saved = contractRepository.findByGlobalTxId(txId).orElseThrow();
        assertThat(saved.getPrdCd()).isEqualTo("CAR-STD-2026");
        call("/api/contract/inquiry", "i", "{ \"contractNo\": \"" + saved.getContractNo() + "\" }")
                .andExpect(jsonPath("$.body.prdCd").value("CAR-STD-2026"))
                .andExpect(jsonPath("$.body.prdNm").value("다이렉트 자동차보험 표준형"));
    }

    @Test
    void 판매_종료_상품으로는_새_청약을_받지_않는다() throws Exception {
        call("/api/contract/apply", "tx-prd-old-" + UUID.randomUUID(), "{ \"insrClsCd\": \"01\", \"prdCd\": \"CAR-STD-2025\", "
                + "\"startDate\": \"" + TODAY.plusDays(1) + "\", \"endDate\": \"" + TODAY.plusDays(1).plusYears(1) + "\", "
                + "\"policyholderId\": \"C1\", \"insuredId\": \"C1\", \"totalPremium\": 850000, " + RATING + ", \"coverages\": ["
                + "{ \"cvrgCd\": \"CA001\", \"isrdAmt\": 100000000, \"prem\": 500000 },"
                + "{ \"cvrgCd\": \"CA002\", \"isrdAmt\": 20000000, \"prem\": 350000 }] }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-PRD-003"));
    }

    @Test
    void 판매가_끝난_상품의_계약도_같은_상품으로_갱신된다() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LocalDate end = TODAY.plusDays(20);
        Contract source = contractRepository.save(Contract.builder()
                .contractNo("POL-P-" + suffix).applNo("APP-P-" + suffix).insrClsCd("01").prdCd("CAR-STD-2025").ctrStatCd("20")
                .startDate(end.minusYears(1)).endDate(end).policyholderId("C1").insuredId("C1")
                .totalPremium(new BigDecimal("850000")).sysRegDt(LocalDateTime.now()).regEmpId("E12345")
                .driverAge(35).discountGrade("3").build());
        coverageRepository.save(Coverage.builder().contract(source).cvrgCd("CA001")
                .isrdAmt(new BigDecimal("100000000")).prem(new BigDecimal("500000")).build());
        coverageRepository.save(Coverage.builder().contract(source).cvrgCd("CA002")
                .isrdAmt(new BigDecimal("20000000")).prem(new BigDecimal("350000")).build());

        String txId = "tx-prd-rn-" + suffix;
        call("/api/contract/renew", txId, "{ \"contractNo\": \"" + source.getContractNo() + "\" }")
                .andExpect(status().isOk());
        assertThat(contractRepository.findByGlobalTxId(txId).orElseThrow().getPrdCd()).isEqualTo("CAR-STD-2025");
    }

    @Test
    void 상품을_지정하지_않은_청약은_기존처럼_상품_없이_접수된다() throws Exception {
        String txId = "tx-prd-none-" + UUID.randomUUID();
        call("/api/contract/apply", txId, "{ \"insrClsCd\": \"01\", \"startDate\": \"" + TODAY.plusDays(1) + "\", \"endDate\": \""
                + TODAY.plusDays(1).plusYears(1) + "\", \"policyholderId\": \"C1\", \"insuredId\": \"C1\", \"totalPremium\": 500000, "
                + RATING + ", \"coverages\": [{ \"cvrgCd\": \"CA001\", \"isrdAmt\": 100000000, \"prem\": 500000 }] }")
                .andExpect(status().isOk());
        assertThat(contractRepository.findByGlobalTxId(txId).orElseThrow().getPrdCd()).isNull();
    }

    @Test
    void 상품_조회는_업무_역할이_있어야_한다() throws Exception {
        mockMvc.perform(post("/api/product/list").with(TestAuth.withRoles()).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"header\": {}, \"body\": {} }"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/product/list").with(TestAuth.withRoles(Roles.AGENT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"header\": {}, \"body\": {} }"))
                .andExpect(status().isOk());
    }
}
