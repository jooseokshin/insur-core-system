package com.insur.insurcoresystem.service.medical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.insur.insurcoresystem.ext.FixedLengthCodec;
import com.insur.insurcoresystem.ext.kcis.KcisLayouts;
import com.insur.insurcoresystem.ext.kcis.KcisSimulator;
import com.insur.insurcoresystem.ext.outbox.OutboxSender;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * 실손의료보험: 신용정보 조회 동의와 중복가입 조회(장애 시 승인 보류), 계약·지급정보 집중(대기열), 4세대 손해사정과 비례보상.
 * 신용정보원은 모의 기관(KcisSimulator)이 전문으로 답한다. 금액은 MedicalClaimCalculatorTest 의 손 계산과 같은 진료 내역이다.
 * <pre>
 * 보험료 (만 35세 계수 0.8, 365일): 급여 5천만 × 0.0024 × 0.8 = 96,000, 비급여 5천만 × 0.0020 × 0.8 = 80,000
 * 단독 보상책임액: 급여 940,000, 비급여 1,620,000 → 지급 2,560,000
 * DUP-001 (다른 보험사 K11 실손 1건): 급여 470,000, 비급여 810,000 → 지급 1,280,000
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MedicalIndemnityIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final String ITEMS = """
            [ { "treatDt": "%1$s", "careType": "OUT", "facilityType": "CLINIC", "facilityNm": "튼튼의원", "coveredAmt": 30000, "nonCoveredAmt": 50000 },
              { "treatDt": "%2$s", "careType": "OUT", "facilityType": "GENERAL", "facilityNm": "한빛종합병원", "coveredAmt": 150000, "nonCoveredAmt": 400000 },
              { "treatDt": "%2$s", "careType": "IN", "facilityType": "TERTIARY", "facilityNm": "서울대학교병원", "coveredAmt": 1000000, "nonCoveredAmt": 2000001 } ]
            """.formatted(TODAY.minusDays(20), TODAY.minusDays(10));

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KcisSimulator kcis;
    @Autowired
    OutboxSender outboxSender;

    private final RequestPostProcessor agent = TestAuth.as("E-AG-MED", Roles.AGENT);
    private final RequestPostProcessor operator = TestAuth.as("E-OP-MED", Roles.OPERATOR);
    private final RequestPostProcessor underwriter = TestAuth.as("E-UW-MED", Roles.UNDERWRITER);
    private final RequestPostProcessor claimHandler = TestAuth.as("E-CLM-MED", Roles.CLAIM);
    private final RequestPostProcessor admin = TestAuth.as("E-ADM-MED", Roles.ADMIN);

    @AfterEach
    void restore() {
        kcis.setFailAll(false);
    }

    private ResultActions call(String path, RequestPostProcessor auth, String body) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-med-" + UUID.randomUUID() + "\" }, \"body\": " + body + " }"));
    }

    private String application(String insuredId, String consent, String prdCd) {
        LocalDate start = TODAY.plusDays(1);
        return """
                { "insrClsCd": "05", %s "startDate": "%s", "endDate": "%s", "policyholderId": "%s", "insuredId": "%s",
                  "totalPremium": 176000, "rating": { "insuredAge": 35 } %s,
                  "coverages": [ { "cvrgCd": "ME001", "isrdAmt": 50000000, "prem": 96000 },
                                 { "cvrgCd": "ME002", "isrdAmt": 50000000, "prem": 80000 } ] }
                """.formatted(prdCd == null ? "" : "\"prdCd\": \"" + prdCd + "\",", start, start.plusDays(365), insuredId, insuredId,
                consent == null ? "" : ", \"creditInfoConsent\": " + consent);
    }

    private String apply(String insuredId) throws Exception {
        return JsonPath.read(call("/api/contract/apply", agent, application(insuredId, "true", "MED-STD-2026"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.body.contractNo");
    }

    private ResultActions approve(String contractNo) throws Exception {
        return call("/api/contract/status-change", underwriter, "{ \"contractNo\": \"" + contractNo + "\", \"ctrStatCd\": \"20\" }");
    }

    /** 유효한 실손 계약 (보험기간 1년, 급여·비급여 가입금액 = 연간 한도) */
    private String inForce(String insuredId, long coveredLimit, boolean nonCovered) {
        String no = "POL-MEDICAL-T-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO TB_CTR_LGD (CONTRACT_NO, APPL_NO, INSR_CLS_CD, PRD_CD, CTR_STAT_CD, START_DATE, END_DATE, POLICYHOLDER_ID, INSURED_ID,
                                        TOTAL_PREMIUM, SYS_REG_DT, REG_EMP_ID, INSURED_AGE)
                VALUES (?, ?, '05', 'MED-STD-2026', '20', ?, ?, ?, ?, 176000, NOW(), 'E-AG-MED', 35)
                """, no, "APP-" + no, TODAY.minusDays(100), TODAY.plusDays(265), insuredId, insuredId);
        jdbc.update("INSERT INTO TB_CTR_CVRG (CONTRACT_NO, CVRG_CD, ISRD_AMT, PREM) VALUES (?, 'ME001', ?, 96000)", no, coveredLimit);
        if (nonCovered) {
            jdbc.update("INSERT INTO TB_CTR_CVRG (CONTRACT_NO, CVRG_CD, ISRD_AMT, PREM) VALUES (?, 'ME002', 50000000, 80000)", no);
        }
        return no;
    }

    private String report(String contractNo) throws Exception {
        return JsonPath.read(call("/api/claim/report", agent, "{ \"contractNo\": \"" + contractNo + "\", \"acdtDesc\": \"허리 통증 치료\", \"medicalItems\": "
                + ITEMS + " }").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.body.clmNo");
    }

    private ResultActions assess(String clmNo, String adjustments) throws Exception {
        return call("/api/claim/assess", claimHandler, "{ \"clmNo\": \"" + clmNo + "\"" + (adjustments == null ? "" : ", \"medicalItems\": " + adjustments) + " }");
    }

    private Map<String, Object> outbox(String bizKey) {
        return jdbc.queryForMap("SELECT * FROM TB_EXT_OUTBOX WHERE BIZ_KEY = ?", bizKey);
    }

    @Test
    void 실손_청약은_상품과_신용정보_조회_동의가_있어야_하고_접수할_때_중복가입을_조회한다() throws Exception {
        call("/api/contract/apply", agent, application("MED-A1", null, "MED-STD-2026"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-MED-001"));
        call("/api/contract/apply", agent, application("MED-A1", "false", "MED-STD-2026"))
                .andExpect(jsonPath("$.header.responseCode").value("E-MED-001"));
        call("/api/contract/apply", agent, application("MED-A1", "true", null))
                .andExpect(jsonPath("$.header.responseCode").value("E-MED-002"));

        String clean = apply("MED-A1");
        assertThat(clean).startsWith("POL-MEDICAL-");
        call("/api/contract/inquiry", agent, "{ \"contractNo\": \"" + clean + "\" }")
                .andExpect(jsonPath("$.body.medicalCheck.checkStatCd").value("10"))
                .andExpect(jsonPath("$.body.medicalCheck.otherCount").value(0))
                .andExpect(jsonPath("$.body.medicalCheck.consentDt").exists());
        // DUP-001 은 다른 보험사(K11)에 실손이 있다. 조회 결과는 남지만 승인은 막지 않는다 (설명 의무는 판매 과정에서)
        String duplicate = apply("DUP-001");
        call("/api/contract/inquiry", agent, "{ \"contractNo\": \"" + duplicate + "\" }")
                .andExpect(jsonPath("$.body.medicalCheck.otherCount").value(1));
    }

    @Test
    void 신용정보원_장애로_조회하지_못한_청약은_다시_조회해야_승인되고_승인하면_계약정보를_집중한다() throws Exception {
        kcis.setFailAll(true);
        String contractNo = apply("MED-B1");   // 장애여도 청약은 받는다
        call("/api/contract/payment", operator, "{ \"contractNo\": \"" + contractNo + "\", \"payAmt\": 176000, \"payMethod\": \"CARD\" }")
                .andExpect(status().isOk());
        approve(contractNo).andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-MED-003"));
        call("/api/contract/medical/dup-check", underwriter, "{ \"contractNo\": \"" + contractNo + "\" }")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.header.responseCode").value("E-MED-004"));
        assertThat(jdbc.queryForObject("SELECT LAST_ERROR FROM TB_CTR_MED_CHECK WHERE CONTRACT_NO = ?", String.class, contractNo))
                .contains("시간 초과");

        kcis.setFailAll(false);
        call("/api/contract/medical/dup-check", agent, "{ \"contractNo\": \"" + contractNo + "\" }").andExpect(status().isForbidden());
        call("/api/contract/medical/dup-check", underwriter, "{ \"contractNo\": \"" + contractNo + "\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.checkStatCd").value("10"))
                .andExpect(jsonPath("$.body.otherContracts.length()").value(0));
        approve(contractNo).andExpect(status().isOk());

        // 승인 거래 안에서 R001(신규)이 대기열에 쌓이고, 보내면 신용정보원(모의)에 우리 계약이 등록된다
        Map<String, Object> queued = outbox(contractNo + "-N");
        assertThat(queued.get("stat_cd")).isEqualTo("10");
        String payload = (String) queued.get("payload");
        Map<String, String> header = FixedLengthCodec.decode(KcisLayouts.HEADER, payload, 0);
        assertThat(header.get("txType")).isEqualTo("R001");
        assertThat(header.get("traceNo")).hasSize(20).startsWith("K99");
        assertThat(Integer.parseInt(header.get("length"))).isEqualTo(FixedLengthCodec.byteLength(payload));
        outboxSender.sendDue();
        assertThat(outbox(contractNo + "-N").get("stat_cd")).isEqualTo("20");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TB_SIM_KCIS_CONTRACT WHERE INSURER_CD = 'K99' AND POLICY_NO = ?",
                Integer.class, contractNo)).isEqualTo(1);
    }

    @Test
    void 전송이_계속_실패하면_간격을_늘려_재시도하다가_멈추고_관리자가_다시_보낸다() throws Exception {
        String contractNo = inForce("MED-C1", 50_000_000, true);
        call("/api/contract/status-change", admin, "{ \"contractNo\": \"" + contractNo + "\", \"ctrStatCd\": \"30\" }")
                .andExpect(status().isOk());
        String key = contractNo + "-T";
        kcis.setFailAll(true);
        for (int attempt = 1; attempt <= OutboxSender.MAX_ATTEMPTS; attempt++) {
            jdbc.update("UPDATE TB_EXT_OUTBOX SET NEXT_TRY_DT = NOW() - INTERVAL '1 minute' WHERE BIZ_KEY = ?", key);
            outboxSender.sendDue();
            Map<String, Object> row = outbox(key);
            assertThat(row.get("attempts")).isEqualTo(attempt);
            assertThat(row.get("stat_cd")).isEqualTo(attempt < OutboxSender.MAX_ATTEMPTS ? "10" : "90");
        }
        // 실패한 건은 다음 시도가 미래라 바로 다시 보내지 않는다
        long id = ((Number) outbox(key).get("outbox_id")).longValue();
        call("/api/admin/ext/outbox-list", operator, "{ \"statCd\": \"90\", \"bizKeyPrefix\": \"" + contractNo + "\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body[0].outboxId").value(id))
                .andExpect(jsonPath("$.body[0].lastError").exists());
        call("/api/admin/ext/outbox-retry", operator, "{ \"outboxId\": " + id + " }").andExpect(status().isForbidden());

        kcis.setFailAll(false);
        call("/api/admin/ext/outbox-retry", admin, "{ \"outboxId\": " + id + " }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.statCd").value("10"));
        call("/api/admin/ext/outbox-retry", admin, "{ \"outboxId\": " + id + " }")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-EXT-003"));
        outboxSender.sendDue();
        assertThat(outbox(key).get("stat_cd")).isEqualTo("20");
    }

    @Test
    void 실손_손해사정은_진료마다_공제하고_통원_1회_한도를_적용하며_지급하면_지급정보를_집중한다() throws Exception {
        String contractNo = inForce("MED-D1", 50_000_000, true);
        String clmNo = report(contractNo);
        call("/api/claim/inquiry", claimHandler, "{ \"clmNo\": \"" + clmNo + "\" }")
                .andExpect(jsonPath("$.body.acdtDt").value(TODAY.minusDays(20).toString()))   // 가장 이른 진료일
                .andExpect(jsonPath("$.body.items.length()").value(2))
                .andExpect(jsonPath("$.body.items[0].claimAmt").value(1180000))
                .andExpect(jsonPath("$.body.items[1].claimAmt").value(2450001))
                .andExpect(jsonPath("$.body.medicalItems.length()").value(3))
                .andExpect(jsonPath("$.body.medicalItems[0].sourceCd").value("MANUAL"));

        assess(clmNo, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.clmStatCd").value("20"))
                .andExpect(jsonPath("$.body.items[0].payAmt").value(940000))
                .andExpect(jsonPath("$.body.items[1].payAmt").value(1620000))
                .andExpect(jsonPath("$.body.medicalItems[1].nonCoveredPay").value(200000))   // 280,000 → 통원 1회 한도
                .andExpect(jsonPath("$.body.medicalItems[2].nonCoveredDeduct").value(600001))
                .andExpect(jsonPath("$.body.medicalShares.length()").value(0))
                .andExpect(jsonPath("$.body.totalPayAmt").value(2560000));

        // 손해사정사가 상급종합병원 비급여 중 보장하지 않는 항목을 빼고 1,000,000 만 인정한다 → 공제 300,000, 책임액 700,000
        assess(clmNo, "[ { \"seq\": 3, \"nonCoveredAmt\": 1000000 } ]")
                .andExpect(jsonPath("$.body.items[1].assessedAmt").value(1450000))
                .andExpect(jsonPath("$.body.items[1].payAmt").value(920000))
                .andExpect(jsonPath("$.body.medicalItems[2].nonCoveredAssessed").value(1000000));
        assess(clmNo, "[ { \"seq\": 3, \"nonCoveredAmt\": 2000002 } ]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-MED-006"));
        assess(clmNo, "[ { \"seq\": 9, \"coveredAmt\": 0 } ]").andExpect(jsonPath("$.header.responseCode").value("E-MED-006"));
        assess(clmNo, null).andExpect(jsonPath("$.body.totalPayAmt").value(2560000));

        call("/api/claim/decide", claimHandler, "{ \"clmNo\": \"" + clmNo + "\", \"decision\": \"PAY\" }").andExpect(status().isOk());
        String payload = (String) outbox(clmNo).get("payload");
        Map<String, String> body = FixedLengthCodec.decode(KcisLayouts.R002_BODY, payload, FixedLengthCodec.length(KcisLayouts.HEADER));
        assertThat(body.get("claimNo")).isEqualTo(clmNo);
        assertThat(Long.parseLong(body.get("coveredPaid"))).isEqualTo(940000);
        assertThat(Long.parseLong(body.get("nonCoveredPaid"))).isEqualTo(1620000);
        assertThat(body.get("firstTreatDate")).isEqualTo(TODAY.minusDays(20).toString().replace("-", ""));
    }

    @Test
    void 다른_보험사_실손이_있으면_비례보상하고_연간_한도를_넘지_않는다() throws Exception {
        String clmNo = report(inForce("DUP-001", 50_000_000, true));
        assess(clmNo, null)
                .andExpect(jsonPath("$.body.items[0].payAmt").value(470000))
                .andExpect(jsonPath("$.body.items[1].payAmt").value(810000))
                .andExpect(jsonPath("$.body.medicalShares.length()").value(4))
                .andExpect(jsonPath("$.body.medicalShares[0].own").value(true))
                .andExpect(jsonPath("$.body.medicalShares[0].liabilityAmt").value(940000))
                .andExpect(jsonPath("$.body.medicalShares[0].shareAmt").value(470000))
                .andExpect(jsonPath("$.body.medicalShares[1].insurerCd").value("K11"))
                .andExpect(jsonPath("$.body.medicalShares[1].policyNo").value("K11**********123"));

        // 급여 연간 한도(가입금액) 600,000, 비급여 특약 없음 → 비급여 금액은 청구하지 않고 급여만 600,000 까지
        String limited = report(inForce("MED-E1", 600_000, false));
        assess(limited, null)
                .andExpect(jsonPath("$.body.items.length()").value(1))
                .andExpect(jsonPath("$.body.items[0].payAmt").value(600000))
                .andExpect(jsonPath("$.body.medicalItems[0].nonCoveredPay").doesNotExist());

        // 비례보상을 확인할 수 없으면 손해사정하지 않는다
        String other = report(inForce("MED-F1", 50_000_000, true));
        kcis.setFailAll(true);
        assess(other, null).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.header.responseCode").value("E-MED-004"));
    }

    @Test
    void 진료_내역은_실손_계약에만_받고_보장_기간_안이어야_한다() throws Exception {
        String contractNo = inForce("MED-G1", 50_000_000, true);
        call("/api/claim/report", agent, "{ \"contractNo\": \"" + contractNo + "\", \"acdtDt\": \"" + TODAY.minusDays(1)
                + "\", \"acdtDesc\": \"통원\", \"coverages\": [{ \"cvrgCd\": \"ME001\", \"amount\": 10000 }] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-MED-005"));
        call("/api/claim/report", agent, "{ \"contractNo\": \"" + contractNo + "\", \"acdtDesc\": \"통원\", \"medicalItems\": [ "
                + "{ \"treatDt\": \"" + TODAY.minusDays(200) + "\", \"careType\": \"OUT\", \"facilityType\": \"CLINIC\", \"facilityNm\": \"의원\", "
                + "\"coveredAmt\": 10000, \"nonCoveredAmt\": 0 } ] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-CLM-003"));
        call("/api/claim/report", agent, "{ \"contractNo\": \"" + contractNo + "\", \"acdtDesc\": \"통원\", \"medicalItems\": [ "
                + "{ \"treatDt\": \"" + TODAY.minusDays(1) + "\", \"careType\": \"DAY\", \"facilityType\": \"CLINIC\", \"facilityNm\": \"의원\", "
                + "\"coveredAmt\": 10000, \"nonCoveredAmt\": 0 } ] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-MED-005"));
    }

    private ResultActions kidi(RequestPostProcessor auth, String transmissionId, String insuredId, String facilityType) throws Exception {
        return call("/api/ext/kidi/medical-claim", auth, """
                { "transmissionId": "%s", "hospitalCode": "11100012", "hospitalName": "한빛종합병원", "facilityType": "%s",
                  "insuredId": "%s",
                  "treatments": [ { "treatDt": "%s", "careType": "OUT", "coveredAmt": 150000, "nonCoveredAmt": 400000 } ] }
                """.formatted(transmissionId, facilityType, insuredId, TODAY.minusDays(5)));
    }

    @Test
    void 실손_청구_전산화로_받은_청구는_피보험자의_실손_계약을_찾아_한_번만_접수한다() throws Exception {
        RequestPostProcessor relay = TestAuth.as("kidi-relay", Roles.EXTERNAL);
        String contractNo = inForce("MED-K1", 50_000_000, true);
        String transmissionId = "KD" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String clmNo = JsonPath.read(kidi(relay, transmissionId, "MED-K1", "GENERAL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.contractNo").value(contractNo))
                .andExpect(jsonPath("$.body.clmStatCd").value("10"))
                .andReturn().getResponse().getContentAsString(), "$.body.clmNo");
        // 중계가 응답을 못 받고 다시 보내도 같은 사고, 내용이 다르면 거절
        kidi(relay, transmissionId, "MED-K1", "GENERAL").andExpect(jsonPath("$.body.clmNo").value(clmNo));
        kidi(relay, transmissionId, "MED-K1", "CLINIC").andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CLM-009"));

        // 접수 뒤에는 직원 접수와 똑같이 자동 손해사정한다 (급여 120,000, 비급여 280,000 → 통원 1회 한도 200,000)
        call("/api/claim/inquiry", claimHandler, "{ \"clmNo\": \"" + clmNo + "\" }")
                .andExpect(jsonPath("$.body.rptEmpId").value("kidi-relay"))
                .andExpect(jsonPath("$.body.medicalItems[0].sourceCd").value("KIDI"));
        assess(clmNo, null).andExpect(jsonPath("$.body.totalPayAmt").value(320000));

        kidi(relay, "KD-NONE-1", "NOBODY", "GENERAL").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.responseCode").value("E-KIDI-002"));
        inForce("MED-K2", 50_000_000, true);
        inForce("MED-K2", 50_000_000, true);
        kidi(relay, "KD-TWO-1", "MED-K2", "GENERAL").andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-KIDI-003"));
        kidi(relay, "KD-BAD-1", "MED-K1", "PHARMACY").andExpect(jsonPath("$.header.responseCode").value("E-MED-005"));

        // 대외기관 토큰은 이 수신 API 만 쓸 수 있고, 직원은 이 API 를 부를 수 없다
        call("/api/contract/inquiry", relay, "{ \"contractNo\": \"" + contractNo + "\" }").andExpect(status().isForbidden());
        call("/api/work/dashboard", relay, "{}").andExpect(status().isForbidden());
        kidi(admin, "KD-ADM-1", "MED-K1", "GENERAL").andExpect(status().isForbidden());
    }
}
