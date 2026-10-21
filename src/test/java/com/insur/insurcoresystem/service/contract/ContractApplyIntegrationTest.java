package com.insur.insurcoresystem.service.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractPayment;
import com.insur.insurcoresystem.domain.contract.ContractPaymentRepository;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.Coverage;
import com.insur.insurcoresystem.domain.contract.CoverageRepository;
import com.insur.insurcoresystem.domain.premium.PremiumFactor;
import com.insur.insurcoresystem.domain.premium.PremiumFactorRepository;
import com.insur.insurcoresystem.domain.premium.PremiumRate;
import com.insur.insurcoresystem.domain.premium.PremiumRateRepository;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyRequest;
import com.insur.insurcoresystem.service.contract.dto.CoverageRequest;
import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 청약 통합 테스트 (docker compose Postgres 필요). 테스트 트랜잭션은 종료 시 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContractApplyIntegrationTest {

    private static final String POL_NO_PATTERN = "POL-CAR-\\d{4}-\\d{6}";
    private static final String APP_NO_PATTERN = "APP-CAR-\\d{4}-\\d{6}";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ContractService contractService;
    @Autowired
    ContractRepository contractRepository;
    @Autowired
    CoverageRepository coverageRepository;
    @Autowired
    ContractPaymentRepository paymentRepository;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    PremiumRateRepository premiumRateRepository;
    @Autowired
    PremiumFactorRepository premiumFactorRepository;

    @Test
    void 청약_요청시_계약원장과_담보가_저장된다() throws Exception {
        String json = """
                {
                  "header": { "globalTxId": "tx-0001", "empId": "E12345" },
                  "body": {
                    "insrClsCd": "01",
                    "startDate": "2026-10-03",
                    "endDate": "2027-10-03",
                    "policyholderId": "C0001",
                    "insuredId": "C0002",
                    "totalPremium": 850000,
                    "rating": { "driverAge": 35, "discountGrade": "3" },
                    "coverages": [
                      { "cvrgCd": "CA001", "isrdAmt": 100000000, "prem": 500000 },
                      { "cvrgCd": "CA002", "isrdAmt": 20000000,  "prem": 350000 }
                    ]
                  }
                }
                """;

        String responseJson = mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.globalTxId").value("tx-0001"))
                .andExpect(jsonPath("$.header.empId").value("E12345"))
                .andExpect(jsonPath("$.header.responseCode").value("0000"))
                .andExpect(jsonPath("$.body.ctrStatCd").value("10"))
                .andExpect(jsonPath("$.body.coverageCount").value(2))
                .andReturn().getResponse().getContentAsString();

        String contractNo = JsonPath.read(responseJson, "$.body.contractNo");
        String applNo = JsonPath.read(responseJson, "$.body.applNo");
        assertThat(contractNo).matches(POL_NO_PATTERN);
        assertThat(applNo).matches(APP_NO_PATTERN);

        // TB_CTR_LGD
        Contract saved = contractRepository.findById(contractNo).orElseThrow();
        assertThat(saved.getApplNo()).isEqualTo(applNo);
        assertThat(saved.getInsrClsCd()).isEqualTo("01");
        assertThat(saved.getCtrStatCd()).isEqualTo("10");
        assertThat(saved.getPolicyholderId()).isEqualTo("C0001");
        assertThat(saved.getInsuredId()).isEqualTo("C0002");
        assertThat(saved.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(saved.getEndDate()).isEqualTo(LocalDate.of(2027, 10, 3));
        assertThat(saved.getTotalPremium()).isEqualByComparingTo("850000");
        assertThat(saved.getSysRegDt()).isNotNull();

        // TB_CTR_CVRG
        List<Coverage> coverages = coverageRepository.findByContractContractNo(contractNo);
        assertThat(coverages).hasSize(2);
        assertThat(coverages).extracting(Coverage::getCvrgCd).containsExactlyInAnyOrder("CA001", "CA002");
        assertThat(coverages).extracting(Coverage::getPrem)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("500000"), new BigDecimal("350000"));
    }

    @Test
    void 청약한_계약을_증권번호와_청약번호로_조회할_수_있다() throws Exception {
        var applied = contractService.applyContract("tx-inq-1", "E12345", carRequest("850000", "500000", "350000"));

        for (String condition : List.of("\"contractNo\": \"" + applied.getContractNo() + "\"",
                "\"applNo\": \"" + applied.getApplNo() + "\"")) {
            mockMvc.perform(post("/api/contract/inquiry").with(TestAuth.emp())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"header\": { \"globalTxId\": \"tx-inq-2\" }, \"body\": { " + condition + " } }"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.header.globalTxId").value("tx-inq-2"))
                    .andExpect(jsonPath("$.header.empId").value("E12345"))
                    .andExpect(jsonPath("$.body.contractNo").value(applied.getContractNo()))
                    .andExpect(jsonPath("$.body.applNo").value(applied.getApplNo()))
                    .andExpect(jsonPath("$.body.ctrStatCd").value("10"))
                    .andExpect(jsonPath("$.body.totalPremium").value(850000))
                    .andExpect(jsonPath("$.body.coverages.length()").value(2));
        }
    }

    @Test
    void 없는_계약을_조회하면_E_CTR_010으로_응답한다() throws Exception {
        mockMvc.perform(post("/api/contract/inquiry").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"header\": { \"globalTxId\": \"tx-inq-3\" }, \"body\": { \"contractNo\": \"POL-CAR-2026-999999\" } }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.globalTxId").value("tx-inq-3"))
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
    }

    @Test
    void 청약_승인_후_해지까지_상태가_순서대로_변경되고_조회에_반영된다() throws Exception {
        String contractNo = contractService.applyContract("tx-st-1", "E12345", carRequest("100000", "100000")).getContractNo();

        changeStatus(contractNo, "20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.responseCode").value("0000"))
                .andExpect(jsonPath("$.body.beforeCtrStatCd").value("10"))
                .andExpect(jsonPath("$.body.afterCtrStatCd").value("20"));
        changeStatus(contractNo, "30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.beforeCtrStatCd").value("20"))
                .andExpect(jsonPath("$.body.afterCtrStatCd").value("30"));

        assertThat(contractRepository.findById(contractNo).orElseThrow().getCtrStatCd()).isEqualTo("30");

        // 상태 이력 조회 API: 청약 접수 → 유효 → 해지
        historyPage(contractNo, "", TestAuth.emp())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.items.length()").value(3))
                .andExpect(jsonPath("$.body.items[0].beforeCtrStatCd").doesNotExist())
                .andExpect(jsonPath("$.body.items[0].afterCtrStatCd").value("10"))
                .andExpect(jsonPath("$.body.items[0].empId").value("E12345"))
                .andExpect(jsonPath("$.body.items[1].beforeCtrStatCd").value("10"))
                .andExpect(jsonPath("$.body.items[1].afterCtrStatCd").value("20"))
                .andExpect(jsonPath("$.body.items[2].afterCtrStatCd").value("30"));

        // 계약 조회 응답에는 이력이 들어 있지 않다
        mockMvc.perform(post("/api/contract/inquiry").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"header\": { \"globalTxId\": \"tx-st-inq\" }, \"body\": { \"contractNo\": \""
                                + contractNo + "\" } }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.ctrStatCd").value("30"))
                .andExpect(jsonPath("$.body.statusHistory").doesNotExist());
    }

    @Test
    void 해지하면_남은_보험기간에_비례한_환급보험료를_기록하고_조회에서_확인한다() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        ContractApplyRequest request = carRequest("500000", "500000");
        request.setStartDate(today.minusDays(100));   // 이미 100일이 지난 365일짜리 계약
        request.setEndDate(today.minusDays(100).plusDays(365));
        String contractNo = contractService.applyContract("tx-refund-1", "E100", request).getContractNo();
        changeStatus(contractNo, "20").andExpect(status().isOk())
                .andExpect(jsonPath("$.body.termDate").doesNotExist())
                .andExpect(jsonPath("$.body.refundPremium").doesNotExist());

        // 500,000 × 남은 265일 ÷ 365일 = 363,013.69... → 363,014
        changeStatus(contractNo, "30").andExpect(status().isOk())
                .andExpect(jsonPath("$.body.termDate").value(today.toString()))
                .andExpect(jsonPath("$.body.refundPremium").value(363014));

        inquiryAs(contractNo, TestAuth.emp())
                .andExpect(jsonPath("$.body.ctrStatCd").value("30"))
                .andExpect(jsonPath("$.body.termDate").value(today.toString()))
                .andExpect(jsonPath("$.body.refundPremium").value(363014));
        assertThat(contractRepository.findById(contractNo).orElseThrow().getRefundPremium()).isEqualByComparingTo("363014");
    }

    @Test
    void 계약_변경_배서로_계약자와_피보험자를_바꾸면_조회에_반영되고_이력이_남는다() throws Exception {
        String contractNo = contractService.applyContract("tx-endo-1", "E100", carRequest("500000", "500000")).getContractNo();
        changeStatus(contractNo, "20").andExpect(status().isOk());   // 유효 상태에서도 변경할 수 있다

        endorse(contractNo, "\"policyholderId\": \"C9001\", \"insuredId\": \"C9002\"", TestAuth.as("U300", Roles.UNDERWRITER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.policyholderId").value("C9001"))
                .andExpect(jsonPath("$.body.insuredId").value("C9002"))
                .andExpect(jsonPath("$.body.changes.length()").value(2))
                .andExpect(jsonPath("$.body.changes[0].fieldNm").value("POLICYHOLDER_ID"))
                .andExpect(jsonPath("$.body.changes[0].beforeVal").value("C0001"))
                .andExpect(jsonPath("$.body.changes[0].afterVal").value("C9001"));
        // 한 항목만 바꾼다: 계약자는 현재 값과 같으니 바뀐 것이 아니다
        endorse(contractNo, "\"policyholderId\": \"C9001\", \"insuredId\": \"C9003\"", TestAuth.as("O400", Roles.OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.changes.length()").value(1))
                .andExpect(jsonPath("$.body.changes[0].fieldNm").value("INSURED_ID"));

        inquiryAs(contractNo, TestAuth.emp())
                .andExpect(jsonPath("$.body.policyholderId").value("C9001"))
                .andExpect(jsonPath("$.body.insuredId").value("C9003"));
        // 배서 이력: 바뀐 항목 3건, 오래된 순, 처리 사번 포함
        endorseHistory(contractNo, "\"page\": 0, \"size\": 2", TestAuth.emp())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.totalElements").value(3))
                .andExpect(jsonPath("$.body.totalPages").value(2))
                .andExpect(jsonPath("$.body.items[0].fieldNm").value("POLICYHOLDER_ID"))
                .andExpect(jsonPath("$.body.items[0].empId").value("U300"))   // 처리한 사람(토큰의 sub)이 기록된다
                .andExpect(jsonPath("$.body.items.length()").value(2));
        endorseHistory(contractNo, "\"page\": 1, \"size\": 2", TestAuth.emp())
                .andExpect(jsonPath("$.body.items.length()").value(1))
                .andExpect(jsonPath("$.body.items[0].beforeVal").value("C9002"))
                .andExpect(jsonPath("$.body.items[0].afterVal").value("C9003"));
    }

    @Test
    void 계약_변경_배서의_거절_조건과_조회_범위() throws Exception {
        String contractNo = contractService.applyContract("tx-endo-2", "E100", carRequest("500000", "500000")).getContractNo();

        endorse(contractNo, "\"policyholderId\": \"C0001\", \"insuredId\": \"C0002\"", TestAuth.emp())   // 같은 값
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-022"));
        endorse(contractNo, "\"insuredId\": \"" + "X".repeat(21) + "\"", TestAuth.emp())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-005"));
        endorse("POL-CAR-2026-999999", "\"insuredId\": \"C1\"", TestAuth.emp())
                .andExpect(status().isNotFound());
        endorse(contractNo, "\"insuredId\": \"C9002\"", TestAuth.as("E100", Roles.AGENT))   // 접수 담당은 변경 불가
                .andExpect(status().isForbidden());

        // 해지된 계약은 변경할 수 없다
        changeStatus(contractNo, "20").andExpect(status().isOk());
        changeStatus(contractNo, "30").andExpect(status().isOk());
        endorse(contractNo, "\"insuredId\": \"C9002\"", TestAuth.emp())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-021"));

        // 이력 조회 범위: 접수자 본인만 (AGENT)
        endorseHistory(contractNo, "", TestAuth.as("E100", Roles.AGENT)).andExpect(status().isOk());
        endorseHistory(contractNo, "", TestAuth.as("E200", Roles.AGENT))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
        endorseHistory(contractNo, "\"size\": 101", TestAuth.emp())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-016"));
    }

    /** 호출마다 새 거래 UUID 로 배서를 요청한다. (같은 거래 UUID 의 재전송은 기존 결과를 돌려주므로 매번 달라야 한다) */
    private org.springframework.test.web.servlet.ResultActions endorse(
            String contractNo, String changes, org.springframework.test.web.servlet.request.RequestPostProcessor auth)
            throws Exception {
        return endorse(java.util.UUID.randomUUID().toString(), contractNo, changes, auth);
    }

    private org.springframework.test.web.servlet.ResultActions endorse(
            String txId, String contractNo, String changes,
            org.springframework.test.web.servlet.request.RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post("/api/contract/endorse").with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": { \"contractNo\": \""
                        + contractNo + "\", " + changes + " } }"));
    }

    private org.springframework.test.web.servlet.ResultActions endorseHistory(
            String contractNo, String paging, org.springframework.test.web.servlet.request.RequestPostProcessor auth)
            throws Exception {
        return mockMvc.perform(post("/api/contract/endorse-history").with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-endo-hist\" }, \"body\": { \"contractNo\": \""
                        + contractNo + "\"" + (paging.isEmpty() ? "" : ", " + paging) + " } }"));
    }

    @Test
    void 응답을_못_받아_같은_거래UUID로_다시_보낸_상태_변경과_배서는_다시_처리하지_않고_처음_결과를_돌려준다() throws Exception {
        String contractNo = contractService.applyContract("tx-retry-0", "E100", carRequest("500000", "500000")).getContractNo();

        // 상태 변경: 승인(10→20)을 같은 거래 UUID 로 두 번 보낸다
        changeStatus("tx-retry-st", contractNo, "20").andExpect(status().isOk())
                .andExpect(jsonPath("$.body.beforeCtrStatCd").value("10"));
        changeStatus("tx-retry-st", contractNo, "20").andExpect(status().isOk())          // 409 E-CTR-014 가 아니라 처음 결과
                .andExpect(jsonPath("$.body.beforeCtrStatCd").value("10"))
                .andExpect(jsonPath("$.body.afterCtrStatCd").value("20"));
        changeStatus("tx-retry-st", contractNo, "90").andExpect(status().isConflict())    // 같은 UUID, 다른 상태
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-019"));
        historyPage(contractNo, "", TestAuth.emp()).andExpect(jsonPath("$.body.totalElements").value(2));   // 접수 + 승인, 늘지 않음

        // 해지의 재전송은 해지일과 환급보험료도 그대로 돌려준다
        String terminated = changeStatus("tx-retry-term", contractNo, "30").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        changeStatus("tx-retry-term", contractNo, "30").andExpect(status().isOk())
                .andExpect(jsonPath("$.body.termDate").value(JsonPath.<String>read(terminated, "$.body.termDate")))
                .andExpect(jsonPath("$.body.refundPremium").value(JsonPath.<Integer>read(terminated, "$.body.refundPremium")));

        // 배서: 해지 전에는 바뀌었던 항목이 재전송에서도 같은 결과여야 하지만, 이 계약은 이미 해지됐으므로 새 계약으로 확인한다
        String other = contractService.applyContract("tx-retry-1", "E100", carRequest("500000", "500000")).getContractNo();
        endorse("tx-retry-endo", other, "\"insuredId\": \"C9002\"", TestAuth.emp()).andExpect(status().isOk());
        endorse("tx-retry-endo", other, "\"insuredId\": \"C9002\"", TestAuth.emp()).andExpect(status().isOk())   // E-CTR-022 가 아니라 처음 결과
                .andExpect(jsonPath("$.body.changes.length()").value(1))
                .andExpect(jsonPath("$.body.changes[0].beforeVal").value("C0002"))
                .andExpect(jsonPath("$.body.changes[0].afterVal").value("C9002"));
        endorse("tx-retry-endo", other, "\"insuredId\": \"C7777\"", TestAuth.emp()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-019"));
        endorseHistory(other, "", TestAuth.emp()).andExpect(jsonPath("$.body.totalElements").value(1));   // 늘지 않음
    }

    // DB 오류가 나면 PostgreSQL 은 그 트랜잭션을 중단시키므로(테스트 트랜잭션 포함), 거절 확인은 테스트마다 한 건씩 한다.

    @Test
    void 요율_적용_기간이_겹치는_요율_행은_DB가_거절한다() {
        // 자동차 대인배상(CA001)은 2000-01-01 부터 현재까지 유효한 행이 이미 있으므로 겹치는 새 행은 막힌다
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into tb_prm_rate (insr_cls_cd, cvrg_cd, cvrg_nm, annual_rate, eff_from) "
                        + "values ('01', 'CA001', '대인배상', 0.006, date '2027-01-01')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("적용 기간이 겹칩니다");
    }

    @Test
    void 같은_키의_요율_계수를_같은_기간에_또_넣으면_DB가_거절한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into tb_prm_factor (insr_cls_cd, factor_type, factor_key, factor_nm, factor_value, eff_from) "
                        + "values ('01', 'DISCOUNT_GRADE', '1', '1등급 중복', 0.7, date '2027-01-01')"))
                .hasMessageContaining("적용 기간이 겹칩니다");
    }

    @Test
    void 구간이_겹치는_요율_계수를_같은_기간에_또_넣으면_DB가_거절한다() {
        // 기존 만 18~25세 구간과 20~30세가 겹친다
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into tb_prm_factor (insr_cls_cd, factor_type, range_from, range_to, factor_nm, factor_value, eff_from) "
                        + "values ('01', 'DRIVER_AGE', 20, 30, '구간 중복', 1.4, date '2027-01-01')"))
                .hasMessageContaining("적용 기간이 겹칩니다");
    }

    @Test
    void 기존_행의_적용_기간을_닫으면_바로_이어서_개정_행을_추가할_수_있고_다른_키_구간은_영향을_주지_않는다() {
        jdbcTemplate.update("update tb_prm_rate set eff_to = date '2026-12-31' where insr_cls_cd = '01' and cvrg_cd = 'CA001'");
        assertThat(jdbcTemplate.update(
                "insert into tb_prm_rate (insr_cls_cd, cvrg_cd, cvrg_nm, annual_rate, eff_from) "
                        + "values ('01', 'CA001', '대인배상', 0.006, date '2027-01-01')")).isEqualTo(1);
        // 아직 없는 키(9등급), 겹치지 않는 구간(만 100~110세), 다른 보험종목의 같은 키는 겹침이 아니다
        assertThat(jdbcTemplate.update(
                "insert into tb_prm_factor (insr_cls_cd, factor_type, factor_key, factor_nm, factor_value, eff_from) "
                        + "values ('01', 'DISCOUNT_GRADE', '9', '9등급', 2.0, date '2027-01-01')")).isEqualTo(1);
        assertThat(jdbcTemplate.update(
                "insert into tb_prm_factor (insr_cls_cd, factor_type, range_from, range_to, factor_nm, factor_value, eff_from) "
                        + "values ('01', 'DRIVER_AGE', 100, 110, '만 100세 이상', 2.0, date '2027-01-01')")).isEqualTo(1);
        assertThat(jdbcTemplate.update(
                "insert into tb_prm_factor (insr_cls_cd, factor_type, factor_key, factor_nm, factor_value, eff_from) "
                        + "values ('02', 'DISCOUNT_GRADE', '1', '다른 보험종목', 0.5, date '2027-01-01')")).isEqualTo(1);
    }

    @Test
    void 종료된_계약의_상태를_바꾸려_하면_409로_응답한다() throws Exception {
        String contractNo = contractService.applyContract("tx-st-2", "E12345", carRequest("100000", "100000")).getContractNo();
        changeStatus(contractNo, "90").andExpect(status().isOk());

        changeStatus(contractNo, "20")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-014"));
    }

    @Test
    void 없는_계약의_상태를_바꾸면_404_모르는_상태코드면_400으로_응답한다() throws Exception {
        changeStatus("POL-CAR-2026-999999", "20")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
        changeStatus("POL-CAR-2026-999999", "99")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-013"));
    }

    /** 호출마다 새 거래 UUID 로 상태 변경을 요청한다. (같은 거래 UUID 의 재전송은 기존 결과를 돌려주므로 매번 달라야 한다) */
    private org.springframework.test.web.servlet.ResultActions changeStatus(String contractNo, String ctrStatCd)
            throws Exception {
        return changeStatus(java.util.UUID.randomUUID().toString(), contractNo, ctrStatCd);
    }

    /** 승인(20)을 시험하는 테스트가 납입 규칙에 걸리지 않도록, 승인 전에 남은 보험료를 모두 납입한 것으로 저장해 둔다. */
    private void payInFullBeforeApproval(String contractNo, String ctrStatCd) {
        if (!"20".equals(ctrStatCd)) {
            return;
        }
        contractRepository.findById(contractNo).ifPresent(contract -> {
            java.math.BigDecimal remaining = contract.getTotalPremium().subtract(paymentRepository.sumPaid(contractNo));
            if (remaining.signum() > 0) {
                paymentRepository.saveAndFlush(ContractPayment.builder().contractNo(contractNo).payAmt(remaining)
                        .payMethod("CASH").empId("E100").globalTxId("pay-" + java.util.UUID.randomUUID())
                        .paidDt(java.time.LocalDateTime.now()).build());
            }
        });
    }

    private org.springframework.test.web.servlet.ResultActions changeStatus(String txId, String contractNo, String ctrStatCd)
            throws Exception {
        payInFullBeforeApproval(contractNo, ctrStatCd);
        return mockMvc.perform(post("/api/contract/status-change").with(TestAuth.emp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": { \"contractNo\": \""
                        + contractNo + "\", \"ctrStatCd\": \"" + ctrStatCd + "\" } }"));
    }

    @Test
    void 청약_접수_담당은_본인이_접수한_계약만_조회하고_심사_담당은_전체를_조회한다() throws Exception {
        String contractNo = contractService.applyContract("tx-own-1", "E100", carRequest("100000", "100000"))
                .getContractNo();

        inquiryAs(contractNo, TestAuth.as("E100", Roles.AGENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.regEmpId").value("E100"));
        inquiryAs(contractNo, TestAuth.as("E200", Roles.AGENT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
        inquiryAs(contractNo, TestAuth.as("E200", Roles.UNDERWRITER)).andExpect(status().isOk());
        inquiryAs(contractNo, TestAuth.as("E300", Roles.ADMIN)).andExpect(status().isOk());
    }

    @Test
    void 상태_이력을_페이지_단위로_조회한다() throws Exception {
        String contractNo = contractService.applyContract("tx-page-1", "E100", carRequest("100000", "100000"))
                .getContractNo();
        changeStatus(contractNo, "20").andExpect(status().isOk());
        changeStatus(contractNo, "30").andExpect(status().isOk());   // 이력: 청약 접수, 승인, 해지 = 3건

        historyPage(contractNo, "\"page\": 0, \"size\": 2", TestAuth.as("E100", Roles.AGENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.totalElements").value(3))
                .andExpect(jsonPath("$.body.totalPages").value(2))
                .andExpect(jsonPath("$.body.items.length()").value(2))
                .andExpect(jsonPath("$.body.items[0].afterCtrStatCd").value("10"))
                .andExpect(jsonPath("$.body.items[1].afterCtrStatCd").value("20"));
        historyPage(contractNo, "\"page\": 1, \"size\": 2", TestAuth.emp())
                .andExpect(jsonPath("$.body.items.length()").value(1))
                .andExpect(jsonPath("$.body.items[0].beforeCtrStatCd").value("20"))
                .andExpect(jsonPath("$.body.items[0].afterCtrStatCd").value("30"));
        historyPage(contractNo, "\"page\": 5, \"size\": 2", TestAuth.emp())   // 범위를 벗어난 페이지는 빈 목록
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.items.length()").value(0))
                .andExpect(jsonPath("$.body.totalElements").value(3));
        historyPage(contractNo, "", TestAuth.emp())   // 생략하면 첫 페이지 20건
                .andExpect(jsonPath("$.body.size").value(20))
                .andExpect(jsonPath("$.body.items.length()").value(3));
    }

    @Test
    void 상태_이력_조회는_조회_범위와_페이징_값_오류를_검증한다() throws Exception {
        String contractNo = contractService.applyContract("tx-page-2", "E100", carRequest("100000", "100000"))
                .getContractNo();

        historyPage(contractNo, "\"page\": 0, \"size\": 2", TestAuth.as("E200", Roles.AGENT))   // 남이 접수한 계약
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-010"));
        historyPage(contractNo, "\"page\": 0, \"size\": 101", TestAuth.emp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-016"));
        historyPage("POL-CAR-2026-999999", "", TestAuth.emp())
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions historyPage(
            String contractNo, String paging, org.springframework.test.web.servlet.request.RequestPostProcessor auth)
            throws Exception {
        return mockMvc.perform(post("/api/contract/status-history").with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-page-req\" }, \"body\": { \"contractNo\": \""
                        + contractNo + "\"" + (paging.isEmpty() ? "" : ", " + paging) + " } }"));
    }

    @Test
    void 계약_목록을_조건별로_최근_접수순으로_조회하고_접수자_범위와_페이징을_따른다() throws Exception {
        // 다른 데이터와 섞이지 않도록 이 테스트만의 계약자 ID 로 격리한다
        String holder = "PH-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ContractApplyRequest carByE100 = carRequest("100000", "100000");
        carByE100.setPolicyholderId(holder);
        ContractApplyRequest fireByE100 = carRequest("100000", "100000");
        fireByE100.setPolicyholderId(holder);
        fireByE100.setInsrClsCd("02");
        fireByE100.setRating(RatingInput.builder().structureGrade("1").build());   // 1급(내화) 계수 0.9
        fireByE100.setCoverages(List.of(CoverageRequest.builder().cvrgCd("FI001")
                .isrdAmt(new BigDecimal("92592593")).prem(new BigDecimal("100000")).build()));   // 요율 0.12% × 0.9
        ContractApplyRequest carByE200 = carRequest("100000", "100000");
        carByE200.setPolicyholderId(holder);
        String first = contractService.applyContract("tx-list-1", "E100", carByE100).getContractNo();
        contractService.applyContract("tx-list-2", "E100", fireByE100);
        contractService.applyContract("tx-list-3", "E200", carByE200);
        changeStatus(first, "20").andExpect(status().isOk());

        String base = "\"policyholderId\": \"" + holder + "\"";
        // 전체 3건, 최근 접수순(등록일시 내림차순)
        String json = listAs(base, TestAuth.emp()).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.totalElements").value(3))
                .andExpect(jsonPath("$.body.items.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        List<String> regDates = JsonPath.read(json, "$.body.items[*].sysRegDt");
        assertThat(regDates).isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // 조건 조합
        listAs(base + ", \"ctrStatCd\": \"20\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(1))
                .andExpect(jsonPath("$.body.items[0].contractNo").value(first));
        listAs(base + ", \"insrClsCd\": \"02\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(1))
                .andExpect(jsonPath("$.body.items[0].insrClsCd").value("02"));
        listAs(base + ", \"ctrStatCd\": \"90\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(0))
                .andExpect(jsonPath("$.body.totalPages").value(0));

        // 접수일 범위: 오늘은 3건, 내일 이후/어제 이전은 0건
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        listAs(base + ", \"regDateFrom\": \"" + today + "\", \"regDateTo\": \"" + today + "\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(3));
        listAs(base + ", \"regDateFrom\": \"" + today.plusDays(1) + "\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(0));
        listAs(base + ", \"regDateTo\": \"" + today.minusDays(1) + "\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(0));

        // 조회 범위: AGENT 는 본인이 접수한 계약만, 심사 담당은 전체
        listAs(base, TestAuth.as("E100", Roles.AGENT)).andExpect(jsonPath("$.body.totalElements").value(2));
        listAs(base, TestAuth.as("E200", Roles.AGENT)).andExpect(jsonPath("$.body.totalElements").value(1));
        listAs(base, TestAuth.as("E999", Roles.AGENT)).andExpect(jsonPath("$.body.totalElements").value(0));
        listAs(base, TestAuth.as("E300", Roles.UNDERWRITER)).andExpect(jsonPath("$.body.totalElements").value(3));

        // 페이징
        listAs(base + ", \"page\": 0, \"size\": 2", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(3))
                .andExpect(jsonPath("$.body.totalPages").value(2))
                .andExpect(jsonPath("$.body.items.length()").value(2));
        listAs(base + ", \"page\": 1, \"size\": 2", TestAuth.emp())
                .andExpect(jsonPath("$.body.items.length()").value(1));

        // 요약 필드와 담보/이력 미포함
        listAs(base + ", \"ctrStatCd\": \"20\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.items[0].regEmpId").value("E100"))
                .andExpect(jsonPath("$.body.items[0].totalPremium").value(100000))
                .andExpect(jsonPath("$.body.items[0].coverages").doesNotExist())
                .andExpect(jsonPath("$.body.items[0].statusHistory").doesNotExist());
    }

    @Test
    void 계약_목록을_번호_앞부분_피보험자_보험시기_보험료로_검색하고_정렬한다() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        String holder = "PH-" + suffix;
        // A: 10만원, 10/3 시작 / B: 30만원, 11/3 시작 / C: 20만원, 12/3 시작
        String contractA = applyForList("tx-ls2-a", holder, "IN-" + suffix + "-A", "100000", java.time.LocalDate.of(2026, 10, 3));
        applyForList("tx-ls2-b", holder, "IN-" + suffix + "-B", "300000", java.time.LocalDate.of(2026, 11, 3));
        applyForList("tx-ls2-c", holder, "IN-" + suffix + "-C", "200000", java.time.LocalDate.of(2026, 12, 3));
        String base = "\"policyholderId\": \"" + holder + "\"";

        // 정렬: 총보험료 오름/내림차순, 보험시기 오름차순
        assertThat(listColumn(base + ", \"sortBy\": \"TOTAL_PREMIUM\", \"sortDir\": \"ASC\"", "totalPremium"))
                .containsExactly(100000, 200000, 300000);
        assertThat(listColumn(base + ", \"sortBy\": \"total_premium\"", "totalPremium"))
                .containsExactly(300000, 200000, 100000);   // 방향을 생략하면 내림차순, 대소문자 무관
        assertThat(listColumn(base + ", \"sortBy\": \"START_DATE\", \"sortDir\": \"ASC\"", "startDate"))
                .containsExactly("2026-10-03", "2026-11-03", "2026-12-03");

        // 총보험료 범위 (양 끝 포함)
        listAs(base + ", \"premiumMin\": 150000", TestAuth.emp()).andExpect(jsonPath("$.body.totalElements").value(2));
        listAs(base + ", \"premiumMax\": 150000", TestAuth.emp()).andExpect(jsonPath("$.body.totalElements").value(1));
        listAs(base + ", \"premiumMin\": 100000, \"premiumMax\": 200000", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(2));

        // 보험시기 범위
        listAs(base + ", \"startDateFrom\": \"2026-11-01\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(2));
        listAs(base + ", \"startDateTo\": \"2026-11-30\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(2));
        listAs(base + ", \"startDateFrom\": \"2026-11-01\", \"startDateTo\": \"2026-11-30\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(1));

        // 피보험자 정확 일치, 증권번호/청약번호 앞부분 일치
        listAs(base + ", \"insuredId\": \"IN-" + suffix + "-B\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(1));
        listAs(base + ", \"contractNo\": \"" + contractA + "\"", TestAuth.emp())
                .andExpect(jsonPath("$.body.totalElements").value(1))
                .andExpect(jsonPath("$.body.items[0].contractNo").value(contractA));
        listAs(base + ", \"contractNo\": \"POL-CAR-\"", TestAuth.emp()).andExpect(jsonPath("$.body.totalElements").value(3));
        listAs(base + ", \"contractNo\": \"POL-FIRE-\"", TestAuth.emp()).andExpect(jsonPath("$.body.totalElements").value(0));
        listAs(base + ", \"applNo\": \"APP-CAR-\"", TestAuth.emp()).andExpect(jsonPath("$.body.totalElements").value(3));
        // LIKE 와이드카드는 글자 그대로 비교한다 (이스케이프하지 않으면 전부 일치해 버린다)
        listAs(base + ", \"contractNo\": \"%\"", TestAuth.emp()).andExpect(jsonPath("$.body.totalElements").value(0));
        listAs(base + ", \"contractNo\": \"POL_CAR\"", TestAuth.emp()).andExpect(jsonPath("$.body.totalElements").value(0));

        // 확장한 조건도 접수자 범위를 넘어서지 못한다
        listAs(base + ", \"contractNo\": \"POL-CAR-\"", TestAuth.as("E999", Roles.AGENT))
                .andExpect(jsonPath("$.body.totalElements").value(0));

        // 조건/정렬 오류
        listAs("\"sortBy\": \"CONTRACT_NO\"", TestAuth.emp())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-018"));
        listAs("\"premiumMin\": 200, \"premiumMax\": 100", TestAuth.emp())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-017"));
    }

    private String applyForList(String txId, String holder, String insured, String premium, java.time.LocalDate start) {
        ContractApplyRequest request = carRequest(premium, premium);
        request.setPolicyholderId(holder);
        request.setInsuredId(insured);
        request.setStartDate(start);
        request.setEndDate(start.plusYears(1));
        return contractService.applyContract(txId, "E100", request).getContractNo();
    }

    /** 목록 응답에서 한 필드의 값을 순서대로 뽑는다. */
    private List<Object> listColumn(String conditions, String field) throws Exception {
        String json = listAs(conditions, TestAuth.emp()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.body.items[*]." + field);
    }

    @Test
    void 사번이_컬럼_길이_64자를_넘어도_청약하고_본인_계약을_조회_목록에서_찾는다() throws Exception {
        String longEmpId = "E".repeat(70);
        String contractNo = contractService.applyContract("tx-long-emp", longEmpId, carRequest("100000", "100000"))
                .getContractNo();

        assertThat(contractRepository.findById(contractNo).orElseThrow().getRegEmpId()).isEqualTo("E".repeat(64));
        inquiryAs(contractNo, TestAuth.as(longEmpId, Roles.AGENT)).andExpect(status().isOk());
        listAs("\"contractNo\": \"" + contractNo + "\"", TestAuth.as(longEmpId, Roles.AGENT))
                .andExpect(jsonPath("$.body.totalElements").value(1));
        historyPage(contractNo, "", TestAuth.as(longEmpId, Roles.AGENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.items[0].empId").value("E".repeat(64)));
    }

    @Test
    void 담보_목록에_null_항목이_있는_청약은_500이_아니라_400_E_CTR_001로_응답한다() throws Exception {
        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "header": { "globalTxId": "tx-null-cvrg" },
                                  "body": { "insrClsCd": "01", "startDate": "2026-10-03", "endDate": "2027-10-03",
                                            "policyholderId": "C0001", "insuredId": "C0002", "totalPremium": 100,
                                            "coverages": [ null ] } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-001"));
    }

    @Test
    void 계약_목록_조건_오류는_400으로_응답한다() throws Exception {
        listAs("\"insrClsCd\": \"99\"", TestAuth.emp())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-SEQ-001"));
        listAs("\"ctrStatCd\": \"99\"", TestAuth.emp())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-013"));
        listAs("\"regDateFrom\": \"2026-10-10\", \"regDateTo\": \"2026-10-09\"", TestAuth.emp())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-017"));
        listAs("\"size\": 101", TestAuth.emp())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-016"));
    }

    private org.springframework.test.web.servlet.ResultActions listAs(
            String conditions, org.springframework.test.web.servlet.request.RequestPostProcessor auth)
            throws Exception {
        return mockMvc.perform(post("/api/contract/list").with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-list-req\" }, \"body\": { " + conditions + " } }"));
    }

    @Test
    void 같은_거래UUID로_다른_내용을_보내면_409로_거절하고_기존_계약은_바뀌지_않는다() {
        var first = contractService.applyContract("tx-hash-1", "E100", carRequest("100000", "100000"));
        long contractCount = contractRepository.count();

        assertThat(contractRepository.findById(first.getContractNo()).orElseThrow().getReqHash()).matches("[0-9a-f]{64}");
        // 같은 내용(금액 표기만 다름)은 기존 결과
        assertThat(contractService.applyContract("tx-hash-1", "E100", carRequest("100000.00", "100000.00"))
                .getContractNo()).isEqualTo(first.getContractNo());
        // 보험료가 다른 요청은 거절
        assertThatThrownBy(() -> contractService.applyContract("tx-hash-1", "E100", carRequest("300000", "300000")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("E-CTR-019");

        assertThat(contractRepository.count()).isEqualTo(contractCount);
        assertThat(contractRepository.findById(first.getContractNo()).orElseThrow().getTotalPremium())
                .isEqualByComparingTo("100000");
    }

    @Test
    void 산출한_보험료를_그대로_청약에_사용할_수_있다() throws Exception {
        String quoteJson = quote("01", "2026-10-10", "2027-10-10",
                "{\"cvrgCd\":\"CA001\",\"isrdAmt\":100000000},{\"cvrgCd\":\"CA002\",\"isrdAmt\":20000000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.responseCode").value("0000"))
                .andExpect(jsonPath("$.body.days").value(365))
                .andExpect(jsonPath("$.body.coverages[0].cvrgNm").value("대인배상"))
                .andExpect(jsonPath("$.body.coverages[0].prem").value(500000))
                .andExpect(jsonPath("$.body.coverages[1].prem").value(350000))
                .andExpect(jsonPath("$.body.totalPremium").value(850000))
                .andReturn().getResponse().getContentAsString();

        // 산출 결과(담보별 보험료와 총보험료)를 그대로 청약 요청에 넣으면 보험료 검증을 통과한다
        ContractApplyRequest apply = carRequest("850000",
                JsonPath.<Integer>read(quoteJson, "$.body.coverages[0].prem").toString(),
                JsonPath.<Integer>read(quoteJson, "$.body.coverages[1].prem").toString());
        apply.setTotalPremium(new BigDecimal(JsonPath.<Integer>read(quoteJson, "$.body.totalPremium")));
        assertThat(contractService.applyContract("tx-quote-apply", "E100", apply).getCtrStatCd()).isEqualTo("10");
    }

    @Test
    void 합계만_맞추고_담보별_보험료를_바꾼_청약은_400_E_CTR_020으로_거절하고_저장하지_않는다() throws Exception {
        long contractCount = contractRepository.count();
        long coverageCount = coverageRepository.count();
        // 산출 보험료는 대인 500,000 + 대물 350,000 = 850,000. 합계는 같지만 담보별 금액을 임의로 바꿨다
        String json = """
                { "header": { "globalTxId": "tx-tamper" },
                  "body": { "insrClsCd": "01", "startDate": "2026-10-10", "endDate": "2027-10-10",
                            "policyholderId": "C0001", "insuredId": "C0002", "totalPremium": 850000,
                            "rating": { "driverAge": 35, "discountGrade": "3" },
                            "coverages": [ { "cvrgCd": "CA001", "isrdAmt": 100000000, "prem": 400000 },
                                           { "cvrgCd": "CA002", "isrdAmt": 20000000,  "prem": 450000 } ] } }
                """;

        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-020"))
                .andExpect(jsonPath("$.header.responseMsg").value(org.hamcrest.Matchers.containsString("CA001")));

        assertThat(contractRepository.count()).isEqualTo(contractCount);
        assertThat(coverageRepository.count()).isEqualTo(coverageCount);
        // 요율표에 없는 담보코드로도 접수할 수 없다
        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.replace("\"CA002\"", "\"ZZ999\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-PRM-001"));
    }

    @Test
    void 요율_계수를_반영해_산출하고_그_조건으로_청약하면_입력값이_저장되고_조회된다() throws Exception {
        // 자동차: 만 22세(1.5) × 할인 1등급(0.8) = 1.2 → 대인배상 1억 × 0.5% × 1.2 = 600,000
        String rating = "\"rating\":{\"driverAge\":22,\"discountGrade\":\"1\"},";
        String quote = mockMvc.perform(post("/api/premium/quote").with(TestAuth.as("E100", Roles.AGENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"header\": {}, \"body\": { \"insrClsCd\": \"01\", \"startDate\": \"2026-10-10\","
                                + " \"endDate\": \"2027-10-10\"," + rating
                                + " \"coverages\": [{\"cvrgCd\":\"CA001\",\"isrdAmt\":100000000}] } }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.combinedFactor").value(1.2))
                .andExpect(jsonPath("$.body.factors.length()").value(2))
                .andExpect(jsonPath("$.body.factors[0].factorType").value("DRIVER_AGE"))
                .andExpect(jsonPath("$.body.factors[0].factorNm").value("만 18~25세"))
                .andExpect(jsonPath("$.body.factors[1].factorType").value("DISCOUNT_GRADE"))
                .andExpect(jsonPath("$.body.coverages[0].annualRate").value(0.005))
                .andExpect(jsonPath("$.body.coverages[0].prem").value(600000))
                .andExpect(jsonPath("$.body.totalPremium").value(600000))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(quote, "$.body.totalPremium")).isEqualTo(600000);

        String applyJson = """
                { "header": { "globalTxId": "tx-rating-1" },
                  "body": { "insrClsCd": "01", "startDate": "2026-10-10", "endDate": "2027-10-10",
                            "policyholderId": "C0001", "insuredId": "C0002", "totalPremium": %d,
                            "rating": { "driverAge": 22, "discountGrade": "1" },
                            "coverages": [ { "cvrgCd": "CA001", "isrdAmt": 100000000, "prem": %d } ] } }
                """;
        // 계수를 무시한 기본 보험료(500,000)로는 거절된다
        mockMvc.perform(post("/api/contract/apply").with(TestAuth.as("E100", Roles.AGENT))
                        .contentType(MediaType.APPLICATION_JSON).content(applyJson.formatted(500000, 500000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-020"));
        String contractNo = JsonPath.read(mockMvc.perform(post("/api/contract/apply")
                        .with(TestAuth.as("E100", Roles.AGENT))
                        .contentType(MediaType.APPLICATION_JSON).content(applyJson.formatted(600000, 600000)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.body.contractNo");

        inquiryAs(contractNo, TestAuth.as("E100", Roles.AGENT))
                .andExpect(jsonPath("$.body.driverAge").value(22))
                .andExpect(jsonPath("$.body.discountGrade").value("1"))
                .andExpect(jsonPath("$.body.structureGrade").doesNotExist());
        // 계수를 입력하지 않은 청약은 기본 요율 그대로이며 계수 컬럼은 비어 있다
        String plain = contractService.applyContract("tx-rating-2", "E100", carRequest("500000", "500000")).getContractNo();
        inquiryAs(plain, TestAuth.emp()).andExpect(jsonPath("$.body.driverAge").value(35))   // 필수 계수라 기본 입력(만 35세, 3등급)이 들어 있다
                .andExpect(jsonPath("$.body.discountGrade").value("3"));
    }

    @Test
    void 요율을_개정하면_보험시기에_따라_개정_전후_요율과_계수가_적용된다() throws Exception {
        // 대인배상 요율을 2027-01-01 부터 0.5% → 0.6% 로, 만 18~25세 계수를 1.5 → 1.8 로 개정한다 (테스트 트랜잭션 안이라 롤백된다)
        jdbcTemplate.update("update tb_prm_rate set eff_to = date '2026-12-31' where insr_cls_cd = '01' and cvrg_cd = 'CA001'");
        premiumRateRepository.save(PremiumRate.builder().insrClsCd("01").cvrgCd("CA001").cvrgNm("대인배상")
                .annualRate(new BigDecimal("0.006")).effFrom(LocalDate.of(2027, 1, 1)).build());
        jdbcTemplate.update("update tb_prm_factor set eff_to = date '2026-12-31' "
                + "where insr_cls_cd = '01' and factor_type = 'DRIVER_AGE' and range_from = 18");
        premiumFactorRepository.save(PremiumFactor.builder().insrClsCd("01").factorType("DRIVER_AGE")
                .rangeFrom(18).rangeTo(25).factorNm("만 18~25세(개정)").factorValue(new BigDecimal("1.8000"))
                .effFrom(LocalDate.of(2027, 1, 1)).build());

        // 개정 전날 시작: 기존 요율/계수 (1억 × 0.5% × 1.5 = 750,000)
        quoteWithRating("2026-12-31", "2027-12-31", "{\"driverAge\":22,\"discountGrade\":\"3\"}")
                .andExpect(jsonPath("$.body.coverages[0].annualRate").value(0.005))
                .andExpect(jsonPath("$.body.factors[0].factorNm").value("만 18~25세"))
                .andExpect(jsonPath("$.body.totalPremium").value(750000));
        // 개정 당일 시작: 개정 요율/계수 (1억 × 0.6% × 1.8 = 1,080,000)
        quoteWithRating("2027-01-01", "2028-01-01", "{\"driverAge\":22,\"discountGrade\":\"3\"}")
                .andExpect(jsonPath("$.body.coverages[0].annualRate").value(0.006))
                .andExpect(jsonPath("$.body.factors[0].factorNm").value("만 18~25세(개정)"))
                .andExpect(jsonPath("$.body.totalPremium").value(1080000));
        // 개정하지 않은 구간(만 30~49세)은 시기와 무관하게 그대로 (1억 × 0.6% × 1.0, 365일)
        quoteWithRating("2027-06-01", "2028-05-31", "{\"driverAge\":35,\"discountGrade\":\"3\"}")
                .andExpect(jsonPath("$.body.factors[0].factorNm").value("만 30~49세"))
                .andExpect(jsonPath("$.body.totalPremium").value(600000));
    }

    private org.springframework.test.web.servlet.ResultActions quoteWithRating(String start, String end, String rating)
            throws Exception {
        return mockMvc.perform(post("/api/premium/quote").with(TestAuth.emp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": {}, \"body\": { \"insrClsCd\": \"01\", \"startDate\": \"" + start
                        + "\", \"endDate\": \"" + end + "\", \"rating\": " + rating
                        + ", \"coverages\": [{\"cvrgCd\":\"CA001\",\"isrdAmt\":100000000}] } }"))
                .andExpect(status().isOk());
    }

    @Test
    void 화재_건물_구조_등급_계수와_계수_입력_오류() throws Exception {
        // 화재 건물 5천만 원 × 0.12% × 구조 3급(1.6) = 96,000
        mockMvc.perform(post("/api/premium/quote").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"header\": {}, \"body\": { \"insrClsCd\": \"02\", \"startDate\": \"2026-10-10\","
                                + " \"endDate\": \"2027-10-10\", \"rating\": {\"structureGrade\":\"3\"},"
                                + " \"coverages\": [{\"cvrgCd\":\"FI001\",\"isrdAmt\":50000000}] } }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.coverages[0].prem").value(96000));

        String body = "{ \"header\": {}, \"body\": { \"insrClsCd\": \"%s\", \"startDate\": \"2026-10-10\","
                + " \"endDate\": \"2027-10-10\", \"rating\": %s,"
                + " \"coverages\": [{\"cvrgCd\":\"%s\",\"isrdAmt\":1000000}] } }";
        // 자동차에 건물 구조 등급, 화재에 운전자 연령: 그 보험종목에서 쓰지 않는 계수
        mockMvc.perform(post("/api/premium/quote").with(TestAuth.emp()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("01", "{\"driverAge\":30,\"discountGrade\":\"3\",\"structureGrade\":\"1\"}", "CA001")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-PRM-003"));
        mockMvc.perform(post("/api/premium/quote").with(TestAuth.emp()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("02", "{\"structureGrade\":\"1\",\"driverAge\":30}", "FI001")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-PRM-003"));
        // 계수표에 없는 값: 만 17세, 없는 등급
        mockMvc.perform(post("/api/premium/quote").with(TestAuth.emp()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("01", "{\"driverAge\":17,\"discountGrade\":\"3\"}", "CA001")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-PRM-004"));
        mockMvc.perform(post("/api/premium/quote").with(TestAuth.emp()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("01", "{\"driverAge\":30,\"discountGrade\":\"9\"}", "CA001")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-PRM-004"));
    }

    @Test
    void 보험료_산출_오류는_400으로_응답한다() throws Exception {
        quote("01", "2026-10-10", "2027-10-10", "{\"cvrgCd\":\"FI001\",\"isrdAmt\":1000}")   // 화재 담보를 자동차에
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-PRM-001"));
        quote("02", "2026-10-10", "2027-10-10", "{\"cvrgCd\":\"FI001\",\"isrdAmt\":1000},{\"cvrgCd\":\"FI001\",\"isrdAmt\":2000}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-PRM-002"));
        quote("01", "2026-10-10", "2026-10-10", "{\"cvrgCd\":\"CA001\",\"isrdAmt\":1000}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-004"));
        quote("01", "2026-10-10", "2027-10-10", "{\"cvrgCd\":\"CA001\",\"isrdAmt\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-CTR-007"));
    }

    private org.springframework.test.web.servlet.ResultActions quote(
            String insrClsCd, String start, String end, String coverages) throws Exception {
        return mockMvc.perform(post("/api/premium/quote").with(TestAuth.as("E100", Roles.AGENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-quote-req\" }, \"body\": { \"insrClsCd\": \""
                        + insrClsCd + "\", \"startDate\": \"" + start + "\", \"endDate\": \"" + end
                        + "\", \"rating\": " + neutralRating(insrClsCd) + ", \"coverages\": [" + coverages + "] } }"));
    }

    @Test
    void 다른_사번이_접수한_거래UUID를_재사용하면_409로_거절된다() {
        contractService.applyContract("tx-own-2", "E100", carRequest("100000", "100000"));

        assertThatThrownBy(() -> contractService.applyContract("tx-own-2", "E200", carRequest("100000", "100000")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("E-CTR-015");
    }

    private org.springframework.test.web.servlet.ResultActions inquiryAs(
            String contractNo, org.springframework.test.web.servlet.request.RequestPostProcessor auth)
            throws Exception {
        return mockMvc.perform(post("/api/contract/inquiry").with(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-own-inq\" }, \"body\": { \"contractNo\": \""
                        + contractNo + "\" } }"));
    }

    @Test
    void 연속_청약시_일련번호가_증가한다() {
        String first = contractService.applyContract("tx-seq-1", "E12345", carRequest("100000", "100000")).getContractNo();
        String second = contractService.applyContract("tx-seq-2", "E12345", carRequest("100000", "100000")).getContractNo();

        long firstSerial = Long.parseLong(first.substring(first.lastIndexOf('-') + 1));
        long secondSerial = Long.parseLong(second.substring(second.lastIndexOf('-') + 1));
        assertThat(secondSerial).isEqualTo(firstSerial + 1);
    }

    @Test
    void 같은_거래UUID로_재요청하면_계약이_추가되지_않고_기존_결과를_돌려준다() {
        long contractCount = contractRepository.count();

        var first = contractService.applyContract("tx-idem-1", "E12345", carRequest("100000", "100000"));
        var retry = contractService.applyContract("tx-idem-1", "E12345", carRequest("100000", "100000"));

        assertThat(retry.getContractNo()).isEqualTo(first.getContractNo());
        assertThat(retry.getCoverageCount()).isEqualTo(1);
        assertThat(contractRepository.count()).isEqualTo(contractCount + 1);
    }

    @Test
    void 총보험료와_담보보험료_합계가_다르면_BusinessException이_발생하고_저장되지_않는다() {
        long contractCount = contractRepository.count();
        long coverageCount = coverageRepository.count();

        assertThatThrownBy(() -> contractService.applyContract("tx-fail", "E12345", carRequest("900000", "500000", "350000")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("E-CTR-003");

        assertThat(contractRepository.count()).isEqualTo(contractCount);
        assertThat(coverageRepository.count()).isEqualTo(coverageCount);
    }

    @Test
    void 보험료_불일치_요청은_오류코드_전문으로_응답한다() throws Exception {
        String json = """
                {
                  "header": { "globalTxId": "tx-0002", "empId": "E12345" },
                  "body": {
                    "insrClsCd": "02",
                    "startDate": "2026-10-03",
                    "endDate": "2027-10-03",
                    "policyholderId": "C0001",
                    "insuredId": "C0001",
                    "totalPremium": 100,
                    "coverages": [ { "cvrgCd": "FI001", "isrdAmt": 500000000, "prem": 99 } ]
                  }
                }
                """;

        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.globalTxId").value("tx-0002"))
                .andExpect(jsonPath("$.header.empId").value("E12345"))
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-003"));
    }

    @Test
    void 요청에_globalTxId가_없으면_채번한_값을_오류_응답에도_담는다() throws Exception {
        String json = """
                {
                  "header": { "empId": "E12345" },
                  "body": {
                    "insrClsCd": "99",
                    "totalPremium": 100,
                    "coverages": [ { "cvrgCd": "FI001", "isrdAmt": 500000000, "prem": 100 } ]
                  }
                }
                """;

        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.globalTxId").isNotEmpty())
                .andExpect(jsonPath("$.header.empId").value("E12345"))
                .andExpect(jsonPath("$.header.responseCode").value("E-SEQ-001"));
    }

    @Test
    void 요청_바디가_없는_오류에도_요청_globalTxId를_담는다() throws Exception {
        mockMvc.perform(post("/api/contract/apply").with(TestAuth.emp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "header": { "globalTxId": "tx-0003", "empId": "E12345" } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.globalTxId").value("tx-0003"))
                .andExpect(jsonPath("$.header.responseCode").value("E-CMN-001"));
    }

    private static ContractApplyRequest carRequest(String totalPremium, String... prems) {
        // 담보코드는 순서대로 CA001, CA002, ... 이고, 가입금액은 요율표(V7 샘플 요율) 기준 산출 보험료가 prems 와 같아지도록 맞춘다.
        // 365일 기준: 보험료 = 가입금액 × 연 요율 이므로 가입금액 = 보험료 ÷ 연 요율
        String[] rates = {"0.005", "0.0175", "0.008", "0.03"};
        List<CoverageRequest> coverages = java.util.stream.IntStream.range(0, prems.length)
                .mapToObj(i -> CoverageRequest.builder()
                        .cvrgCd("CA00" + (i + 1))
                        .isrdAmt(new BigDecimal(prems[i]).divide(new BigDecimal(rates[i]), 0, java.math.RoundingMode.HALF_UP))
                        .prem(new BigDecimal(prems[i]))
                        .build())
                .toList();
        return ContractApplyRequest.builder()
                .insrClsCd("01")
                .startDate(LocalDate.of(2026, 10, 3))
                .endDate(LocalDate.of(2027, 10, 3))
                .policyholderId("C0001")
                .insuredId("C0002")
                .totalPremium(new BigDecimal(totalPremium))
                .rating(RatingInput.builder().driverAge(35).discountGrade("3").build())   // 필수 계수. 둘 다 계수 1.0 이라 보험료는 그대로
                .coverages(coverages)
                .build();
    }

    /** 필수 요율 계수를 모두 채우되 보험료를 바꾸지 않는 입력(계수 1.0). 화재는 구조 등급 계수가 1.0 인 값이 없어 2급(1.1)을 쓴다. */
    private static String neutralRating(String insrClsCd) {
        return "02".equals(insrClsCd) ? "{\"structureGrade\":\"2\"}" : "{\"driverAge\":35,\"discountGrade\":\"3\"}";
    }
}
