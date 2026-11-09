package com.insur.insurcoresystem.service.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운전자보험(03)과 해외여행자보험(04). 보험료는 손으로 계산한 값과 비교한다 (V24 예시 요율).
 * <pre>
 * 해외여행 TRV-STD-2026, 7일, 유럽(1.3), 30세(1.0)
 *   TR001 5천만 × 0.004 × 1.3 × 7 ÷ 365 = 4,986.30 → 4,986
 *   TR002 5천만 × 0.006 × 1.3 × 7 ÷ 365 = 7,479.45 → 7,479   합계 12,465
 *   다이렉트(0.9 더): TR001 4,487.67 → 4,488, TR002 6,731.51 → 6,732   합계 11,220
 * 운전자 DRV-STD-2026, 365일, DR001 1억 × 0.0006 = 60,000 (40세 1.0) / 22세(1.3) 78,000
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DriverTravelIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    private final LocalDate start = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
    private final RequestPostProcessor agent = TestAuth.as("E-PRD-T", Roles.AGENT);

    private ResultActions call(String path, RequestPostProcessor auth, String body) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-dt-" + UUID.randomUUID() + "\" }, \"body\": " + body + " }"));
    }

    private String travel(int days, String rating, String coverages) {
        return """
                { "insrClsCd": "04", "prdCd": "TRV-STD-2026", "startDate": "%s", "endDate": "%s", "rating": %s, "coverages": %s }
                """.formatted(start, start.plusDays(days), rating, coverages);
    }

    private static final String EUROPE_30 = "{ \"travelRegion\": \"EUROPE\", \"insuredAge\": 30 }";
    private static final String MEDICAL = "[ { \"cvrgCd\": \"TR001\", \"isrdAmt\": 50000000 }, { \"cvrgCd\": \"TR002\", \"isrdAmt\": 50000000 } ]";

    private String driver(LocalDate end, int age) {
        return """
                { "insrClsCd": "03", "prdCd": "DRV-STD-2026", "startDate": "%s", "endDate": "%s", "rating": { "driverAge": %d },
                  "coverages": [ { "cvrgCd": "DR001", "isrdAmt": 100000000 } ] }
                """.formatted(start, end, age);
    }

    @Test
    void 해외여행자보험은_여행지와_나이와_일수로_보험료를_낸다() throws Exception {
        call("/api/premium/quote", agent, travel(7, EUROPE_30, MEDICAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.days").value(7))
                .andExpect(jsonPath("$.body.coverages[0].prem").value(4986))
                .andExpect(jsonPath("$.body.coverages[1].prem").value(7479))
                .andExpect(jsonPath("$.body.totalPremium").value(12465))
                .andExpect(jsonPath("$.body.requiredFactors.length()").value(2));

        RequestPostProcessor direct = jwt().jwt(t -> t.subject("ch-dt").claim("channel_cd", "20"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + Roles.CHANNEL));
        call("/api/premium/quote", direct, travel(7, EUROPE_30, MEDICAL))
                .andExpect(jsonPath("$.body.totalPremium").value(11220));
    }

    @Test
    void 해외여행자보험의_가입_규칙() throws Exception {
        // 기간은 1~90일
        call("/api/premium/quote", agent, travel(91, EUROPE_30, MEDICAL))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRD-007"));
        call("/api/premium/quote", agent, travel(90, EUROPE_30, MEDICAL)).andExpect(status().isOk());
        // 80세 이상은 나이 구간이 없어 가입할 수 없다
        call("/api/premium/quote", agent, travel(7, "{ \"travelRegion\": \"ASIA\", \"insuredAge\": 80 }", MEDICAL))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRM-004"));
        // 여행 지역은 필수, 없는 지역 코드는 거절
        call("/api/premium/quote", agent, travel(7, "{ \"insuredAge\": 30 }", MEDICAL))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRM-005"));
        call("/api/premium/quote", agent, travel(7, "{ \"travelRegion\": \"MOON\", \"insuredAge\": 30 }", MEDICAL))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRM-004"));
        // 질병의료비 특약은 이 상품에서 필수
        call("/api/premium/quote", agent, travel(7, EUROPE_30, "[ { \"cvrgCd\": \"TR001\", \"isrdAmt\": 50000000 } ]"))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRD-005"));
        // 다른 종목의 계수는 쓸 수 없다
        call("/api/premium/quote", agent, travel(7, "{ \"travelRegion\": \"ASIA\", \"insuredAge\": 30, \"discountGrade\": \"3\" }", MEDICAL))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRM-003"));
    }

    @Test
    void 해외여행자보험_청약은_여행_계수를_계약에_남기고_여행_번호로_채번한다() throws Exception {
        String apply = """
                { "insrClsCd": "04", "prdCd": "TRV-STD-2026", "startDate": "%s", "endDate": "%s",
                  "policyholderId": "TRAVELER-1", "insuredId": "TRAVELER-1", "totalPremium": 12465, "rating": %s,
                  "coverages": [ { "cvrgCd": "TR001", "isrdAmt": 50000000, "prem": 4986 },
                                 { "cvrgCd": "TR002", "isrdAmt": 50000000, "prem": 7479 } ] }
                """.formatted(start, start.plusDays(7), EUROPE_30);
        String contractNo = JsonPath.read(call("/api/contract/apply", agent, apply).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.body.contractNo");
        assertThat(contractNo).startsWith("POL-TRAVEL-");
        call("/api/contract/inquiry", agent, "{ \"contractNo\": \"" + contractNo + "\" }")
                .andExpect(jsonPath("$.body.insrClsCd").value("04"))
                .andExpect(jsonPath("$.body.travelRegion").value("EUROPE"))
                .andExpect(jsonPath("$.body.insuredAge").value(30))
                .andExpect(jsonPath("$.body.prdCd").value("TRV-STD-2026"));
    }

    @Test
    void 운전자보험은_1년_상품이고_운전자_나이로_보험료가_달라진다() throws Exception {
        call("/api/premium/quote", agent, driver(start.plusDays(365), 40))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.totalPremium").value(60000));
        call("/api/premium/quote", agent, driver(start.plusDays(365), 22))
                .andExpect(jsonPath("$.body.totalPremium").value(78000));
        call("/api/premium/quote", agent, driver(start.plusDays(30), 40))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRD-007"));
        // 운전자 나이는 필수
        call("/api/premium/quote", agent, driver(start.plusDays(365), 40).replace("\"rating\": { \"driverAge\": 40 },", ""))
                .andExpect(jsonPath("$.header.responseCode").value("E-PRM-005"));
    }

    @Test
    void 상품_목록에_보험기간_범위가_나온다() throws Exception {
        call("/api/product/list", agent, "{ \"insrClsCd\": \"04\" }")
                .andExpect(jsonPath("$.body.items[0].prdCd").value("TRV-STD-2026"))
                .andExpect(jsonPath("$.body.items[0].minTermDays").value(1))
                .andExpect(jsonPath("$.body.items[0].maxTermDays").value(90));
    }

    @Test
    void 판매채널_계수에는_필수_정책을_둘_수_없다() throws Exception {
        call("/api/admin/premium/policy-revise", TestAuth.as("E-ADM-T", Roles.ADMIN), """
                { "insrClsCd": "04", "factorType": "SALES_CHANNEL", "required": true, "effFrom": "%s" }
                """.formatted(start))
                .andExpect(jsonPath("$.header.responseCode").value("E-ADM-001"));
    }
}
