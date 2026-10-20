package com.insur.insurcoresystem.service.premium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.common.security.TestAuth;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 요율/계수 관리 통합 테스트 (docker compose Postgres 필요). 테스트 트랜잭션은 종료 시 롤백되므로 요율표는 바뀌지 않는다.
 * DB 오류가 나면 PostgreSQL 이 그 트랜잭션을 중단시키므로, DB 가 거절하는 확인은 테스트의 마지막에 한 번만 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PremiumAdminIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final String BASE = "/api/admin/premium/";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private ResultActions call(String path, String txId, String body, RequestPostProcessor auth) throws Exception {
        return mockMvc.perform(post(path).with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"" + txId + "\" }, \"body\": " + body + " }"));
    }

    private ResultActions reviseRate(String txId, String fields) throws Exception {
        return call(BASE + "rate-revise", txId, "{ " + fields + " }", TestAuth.as("A500", Roles.ADMIN));
    }

    private ResultActions reviseFactor(String txId, String fields) throws Exception {
        return call(BASE + "factor-revise", txId, "{ " + fields + " }", TestAuth.as("A500", Roles.ADMIN));
    }

    /** 필수 요율 계수를 모두 채우되 보험료를 바꾸지 않는 입력(계수 1.0) */
    private static final String NEUTRAL = "{\"driverAge\":35,\"discountGrade\":\"3\"}";

    /** 365일짜리 자동차 보험료를 산출한다. 대인배상(CA001) 가입금액 1억, 요율 계수는 rating 으로 지정한다(생략하면 계수 1.0). */
    private ResultActions quote(LocalDate start, String rating) throws Exception {
        return call("/api/premium/quote", "tx-q", "{ \"insrClsCd\": \"01\", \"startDate\": \"" + start + "\", \"endDate\": \""
                + start.plusDays(365) + "\", \"rating\": " + (rating == null ? NEUTRAL : rating)
                + ", \"coverages\": [{\"cvrgCd\":\"CA001\",\"isrdAmt\":100000000}] }", TestAuth.emp());
    }

    private String rateFields(String cvrgCd, String name, String rate, LocalDate effFrom) {
        return "\"insrClsCd\": \"01\", \"cvrgCd\": \"" + cvrgCd + "\"" + (name == null ? "" : ", \"cvrgNm\": \"" + name + "\"")
                + ", \"annualRate\": " + rate + ", \"effFrom\": \"" + effFrom + "\"";
    }

    private int auditCount(String txId) {
        return jdbcTemplate.queryForObject("select count(*) from tb_prm_audit where global_tx_id = ?", Integer.class, txId);
    }

    @Test
    void 요율을_개정하면_이전_행이_닫히고_보험시기에_따라_산출과_목록에_반영되며_감사_이력이_남는다() throws Exception {
        LocalDate effFrom = TODAY.plusDays(30);

        reviseRate("tx-rate-1", rateFields("CA001", null, "0.006", effFrom))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.responseCode").value("0000"))
                .andExpect(jsonPath("$.body.action").value("REVISE"))
                .andExpect(jsonPath("$.body.previous.effTo").value(effFrom.minusDays(1).toString()))
                .andExpect(jsonPath("$.body.previous.annualRate").value(0.005))
                .andExpect(jsonPath("$.body.previous.status").value("ACTIVE"))
                .andExpect(jsonPath("$.body.current.annualRate").value(0.006))
                .andExpect(jsonPath("$.body.current.cvrgNm").value("대인배상"))
                .andExpect(jsonPath("$.body.current.status").value("FUTURE"));

        // 개정 전날에 시작하는 보험은 기존 요율, 개정일에 시작하는 보험은 새 요율
        quote(effFrom.minusDays(1), null).andExpect(jsonPath("$.body.totalPremium").value(500000));
        quote(effFrom, null).andExpect(jsonPath("$.body.totalPremium").value(600000));

        // 목록: 같은 담보의 이력이 시작일 순으로 나온다
        call(BASE + "rate-list", "tx-l", "{ \"insrClsCd\": \"01\", \"cvrgCd\": \"CA001\" }", TestAuth.as("U300", Roles.UNDERWRITER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.items.length()").value(2))
                .andExpect(jsonPath("$.body.items[0].annualRate").value(0.005))
                .andExpect(jsonPath("$.body.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.body.items[1].annualRate").value(0.006))
                .andExpect(jsonPath("$.body.items[1].status").value("FUTURE"));

        // 감사 이력
        assertThat(jdbcTemplate.queryForMap("select target_key, action, before_val, after_val, emp_id from tb_prm_audit "
                + "where global_tx_id = 'tx-rate-1'"))
                .containsEntry("target_key", "01/CA001").containsEntry("action", "REVISE")
                .containsEntry("before_val", "0.005").containsEntry("after_val", "0.006").containsEntry("emp_id", "A500");
    }

    @Test
    void 같은_거래UUID의_재전송은_처음_결과를_돌려주고_다른_내용은_거절하며_이력이_늘지_않는다() throws Exception {
        LocalDate effFrom = TODAY.plusDays(30);
        String fields = rateFields("CA001", null, "0.006", effFrom);
        reviseRate("tx-rate-2", fields).andExpect(status().isOk());

        reviseRate("tx-rate-2", fields).andExpect(status().isOk())          // E-ADM-003 이 아니라 처음 결과
                .andExpect(jsonPath("$.body.action").value("REVISE"))
                .andExpect(jsonPath("$.body.current.annualRate").value(0.006))
                .andExpect(jsonPath("$.body.previous.effTo").value(effFrom.minusDays(1).toString()));
        reviseRate("tx-rate-2", rateFields("CA001", null, "0.007", effFrom)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-019"));

        assertThat(auditCount("tx-rate-2")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from tb_prm_rate where insr_cls_cd = '01' and cvrg_cd = 'CA001'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void 요율_개정의_입력_오류와_시작일_규칙() throws Exception {
        reviseRate("tx-e1", rateFields("CA001", null, "0.006", TODAY.minusDays(1)))                  // 과거
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-ADM-002"));
        reviseRate("tx-e2", rateFields("CA001", null, "1.5", TODAY.plusDays(1)))                     // 요율 범위
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-ADM-001"));
        reviseRate("tx-e3", rateFields("ZZ001", null, "0.01", TODAY.plusDays(1)))                    // 새 담보인데 이름 없음
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-ADM-001"));

        // 개정 후 같은 날짜나 그 이전으로 또 개정할 수는 없다
        reviseRate("tx-e4", rateFields("CA001", null, "0.006", TODAY.plusDays(30))).andExpect(status().isOk());
        reviseRate("tx-e5", rateFields("CA001", null, "0.007", TODAY.plusDays(30)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-ADM-003"));
        reviseRate("tx-e6", rateFields("CA001", null, "0.007", TODAY.plusDays(10)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-ADM-003"));
        // 더 늦은 날짜로는 다시 개정할 수 있고, 예약돼 있던 행이 닫힌다
        reviseRate("tx-e7", rateFields("CA001", null, "0.007", TODAY.plusDays(60))).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.previous.effTo").value(TODAY.plusDays(59).toString()))
                .andExpect(jsonPath("$.body.previous.status").value("FUTURE"));
        assertThat(auditCount("tx-e1") + auditCount("tx-e2") + auditCount("tx-e3") + auditCount("tx-e5")).isZero();
    }

    @Test
    void 새_담보_요율을_만들면_바로_산출에_쓸_수_있다() throws Exception {
        reviseRate("tx-new", rateFields("ZZ001", "신규 특약", "0.01", TODAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.action").value("CREATE"))
                .andExpect(jsonPath("$.body.previous").doesNotExist())
                .andExpect(jsonPath("$.body.current.status").value("ACTIVE"));

        call("/api/premium/quote", "tx-q", "{ \"insrClsCd\": \"01\", \"startDate\": \"" + TODAY + "\", \"endDate\": \""
                + TODAY.plusDays(365) + "\", \"rating\": " + NEUTRAL
                + ", \"coverages\": [{\"cvrgCd\":\"ZZ001\",\"isrdAmt\":10000000}] }", TestAuth.emp())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.coverages[0].cvrgNm").value("신규 특약"))
                .andExpect(jsonPath("$.body.totalPremium").value(100000));   // 1천만 원 × 1%
        assertThat(jdbcTemplate.queryForObject("select action from tb_prm_audit where global_tx_id = 'tx-new'", String.class))
                .isEqualTo("CREATE");
    }

    @Test
    void 요율_계수를_개정하고_새_키를_만들면_보험시기에_따라_산출에_반영된다() throws Exception {
        LocalDate effFrom = TODAY.plusDays(30);
        String grade1 = "\"insrClsCd\": \"01\", \"factorType\": \"DISCOUNT_GRADE\", \"factorKey\": \"1\", \"factorValue\": 0.75, \"effFrom\": \"" + effFrom + "\"";

        reviseFactor("tx-f1", grade1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.action").value("REVISE"))
                .andExpect(jsonPath("$.body.previous.factorValue").value(0.8))
                .andExpect(jsonPath("$.body.previous.effTo").value(effFrom.minusDays(1).toString()))
                .andExpect(jsonPath("$.body.current.factorValue").value(0.75))
                .andExpect(jsonPath("$.body.current.factorNm").value("1등급(최우량 할인)"));

        String rating = "{\"driverAge\":35,\"discountGrade\":\"1\"}";
        quote(effFrom.minusDays(1), rating).andExpect(jsonPath("$.body.totalPremium").value(400000));   // 1억 × 0.5% × 0.80
        quote(effFrom, rating).andExpect(jsonPath("$.body.totalPremium").value(375000));                // × 0.75

        // 구간형 개정과 목록 필터
        reviseFactor("tx-f2", "\"insrClsCd\": \"01\", \"factorType\": \"DRIVER_AGE\", \"rangeFrom\": 18, \"rangeTo\": 25, "
                + "\"factorValue\": 1.8, \"effFrom\": \"" + effFrom + "\"")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.previous.factorValue").value(1.5));
        call(BASE + "factor-list", "tx-fl", "{ \"insrClsCd\": \"01\", \"factorType\": \"DRIVER_AGE\" }", TestAuth.as("O400", Roles.OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.items.length()").value(6))   // 5개 구간 + 개정으로 늘어난 1행
                .andExpect(jsonPath("$.body.items[0].rangeFrom").value(18))
                .andExpect(jsonPath("$.body.items[1].rangeFrom").value(18))
                .andExpect(jsonPath("$.body.items[1].status").value("FUTURE"));

        // 아직 없는 키(6등급)를 새로 만든다
        reviseFactor("tx-f3", "\"insrClsCd\": \"01\", \"factorType\": \"DISCOUNT_GRADE\", \"factorKey\": \"6\", "
                + "\"factorNm\": \"6등급(특별 할증)\", \"factorValue\": 1.5, \"effFrom\": \"" + TODAY + "\"")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.action").value("CREATE"));
        quote(TODAY, "{\"driverAge\":35,\"discountGrade\":\"6\"}").andExpect(jsonPath("$.body.totalPremium").value(750000));
        assertThat(auditCount("tx-f1") + auditCount("tx-f2") + auditCount("tx-f3")).isEqualTo(3);
    }

    @Test
    void 요율_관리_API는_조회는_심사_계약관리_ADMIN만_개정은_ADMIN만_할_수_있다() throws Exception {
        String list = "{}";
        String revise = "{ " + rateFields("CA001", null, "0.006", TODAY.plusDays(30)) + " }";

        for (String role : new String[] {Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN}) {
            call(BASE + "rate-list", "tx-r", list, TestAuth.withRoles(role)).andExpect(status().isOk());
            call(BASE + "factor-list", "tx-r", list, TestAuth.withRoles(role)).andExpect(status().isOk());
        }
        call(BASE + "rate-list", "tx-r", list, TestAuth.withRoles(Roles.AGENT)).andExpect(status().isForbidden());
        for (String role : new String[] {Roles.AGENT, Roles.UNDERWRITER, Roles.OPERATOR}) {
            call(BASE + "rate-revise", "tx-r", revise, TestAuth.withRoles(role)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-002"));
            call(BASE + "factor-revise", "tx-r", "{}", TestAuth.withRoles(role)).andExpect(status().isForbidden());
        }
        assertThat(auditCount("tx-r")).isZero();   // 권한이 없으면 아무것도 바뀌지 않는다
    }

    @Test
    void 구간이_겹치는_계수를_새로_만들려_하면_DB_트리거가_거절하고_충돌_오류로_응답한다() throws Exception {
        // 기존 만 18~25세 구간(현재까지 유효)과 겹치는 20~30세를 내일부터 새 칸으로 만들려는 경우
        reviseFactor("tx-ov", "\"insrClsCd\": \"01\", \"factorType\": \"DRIVER_AGE\", \"rangeFrom\": 20, \"rangeTo\": 30, "
                + "\"factorNm\": \"겹치는 구간\", \"factorValue\": 1.4, \"effFrom\": \"" + TODAY.plusDays(1) + "\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.responseCode").value("E-ADM-004"));
    }

    @Test
    void 필수_계수를_입력하지_않으면_산출이_거절되고_정책을_선택으로_바꾸면_시작일부터_생략할_수_있다() throws Exception {
        LocalDate effFrom = TODAY.plusDays(30);
        String body = "{ \"insrClsCd\": \"01\", \"startDate\": \"%s\", \"endDate\": \"%s\", "
                + "\"coverages\": [{\"cvrgCd\":\"CA001\",\"isrdAmt\":100000000}] }";

        // 현재: 필수 계수(운전자 나이, 할인할증 등급)를 빠뜨리면 400
        call("/api/premium/quote", "tx-p0", String.format(body, TODAY, TODAY.plusDays(365)), TestAuth.emp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-PRM-005"))
                .andExpect(jsonPath("$.header.responseMsg").value(org.hamcrest.Matchers.containsString("DRIVER_AGE(driverAge)")));
        // 필수 계수를 채우면 응답이 필수 종류를 알려 준다
        quote(TODAY, null).andExpect(jsonPath("$.body.requiredFactors.length()").value(2));

        // 운전자 나이를 effFrom 부터 선택으로 개정
        call(BASE + "policy-revise", "tx-pol-1", "{ \"insrClsCd\": \"01\", \"factorType\": \"DRIVER_AGE\", "
                + "\"required\": false, \"effFrom\": \"" + effFrom + "\" }", TestAuth.as("A500", Roles.ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.action").value("REVISE"))
                .andExpect(jsonPath("$.body.previous.required").value(true))
                .andExpect(jsonPath("$.body.current.required").value(false))
                .andExpect(jsonPath("$.body.current.status").value("FUTURE"));

        // 보험시기에 따라 적용: 개정 전날은 여전히 필수, 개정일부터는 운전자 나이 없이도 산출 (할인할증 등급은 계속 필수)
        call("/api/premium/quote", "tx-p1", String.format(body, effFrom.minusDays(1), effFrom.plusDays(364)), TestAuth.emp())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-PRM-005"));
        call("/api/premium/quote", "tx-p2", String.format(body.replace("\"coverages\"", "\"rating\": {\"discountGrade\":\"3\"}, \"coverages\""),
                effFrom, effFrom.plusDays(365)), TestAuth.emp())
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.requiredFactors.length()").value(1));

        // 정책 목록: 시작일 순
        call(BASE + "policy-list", "tx-pl", "{ \"insrClsCd\": \"01\" }", TestAuth.as("U300", Roles.UNDERWRITER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.items[?(@.factorType=='DRIVER_AGE')].required").value(org.hamcrest.Matchers.contains(true, false)));
        assertThat(auditCount("tx-pol-1")).isEqualTo(1);
    }

    @Test
    void 정책_API는_조회는_심사_계약관리_ADMIN만_개정은_ADMIN만_할_수_있다() throws Exception {
        for (String role : new String[] {Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN}) {
            call(BASE + "policy-list", "tx-r", "{}", TestAuth.withRoles(role)).andExpect(status().isOk());
        }
        call(BASE + "policy-list", "tx-r", "{}", TestAuth.withRoles(Roles.AGENT)).andExpect(status().isForbidden());
        for (String role : new String[] {Roles.AGENT, Roles.UNDERWRITER, Roles.OPERATOR}) {
            call(BASE + "policy-revise", "tx-r", "{}", TestAuth.withRoles(role)).andExpect(status().isForbidden());
        }
        assertThat(auditCount("tx-r")).isZero();
    }

    @Test
    void 청약도_필수_계수가_없으면_접수하지_않는다() throws Exception {
        LocalDate start = TODAY.plusDays(1);
        call("/api/contract/apply", "tx-ap-p", "{ \"insrClsCd\": \"01\", \"startDate\": \"" + start + "\", \"endDate\": \""
                + start.plusDays(365) + "\", \"policyholderId\": \"C0001\", \"insuredId\": \"C0002\", \"totalPremium\": 500000, "
                + "\"coverages\": [{\"cvrgCd\":\"CA001\",\"isrdAmt\":100000000,\"prem\":500000}] }", TestAuth.emp())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-PRM-005"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from tb_ctr_lgd where global_tx_id = 'tx-ap-p'", Integer.class)).isZero();
    }

    @Test
    void 감사_이력_조회는_조건으로_거르고_최근_처리순으로_페이지를_나눈다() throws Exception {
        LocalDate effFrom = TODAY.plusDays(30);
        reviseRate("tx-au-1", rateFields("CA001", null, "0.006", effFrom)).andExpect(status().isOk());
        call(BASE + "policy-revise", "tx-au-2", "{ \"insrClsCd\": \"01\", \"factorType\": \"DRIVER_AGE\", "
                + "\"required\": false, \"effFrom\": \"" + effFrom + "\" }", TestAuth.as("A501", Roles.ADMIN)).andExpect(status().isOk());
        reviseFactor("tx-au-3", "\"insrClsCd\": \"01\", \"factorType\": \"DISCOUNT_GRADE\", \"factorKey\": \"3\", "
                + "\"factorNm\": \"3등급\", \"factorValue\": 1.05, \"effFrom\": \"" + effFrom + "\"").andExpect(status().isOk());

        var uw = TestAuth.as("U300", Roles.UNDERWRITER);
        // 이 테스트가 만든 3건이 최근 처리순(마지막 개정이 맨 앞)으로 나온다
        call(BASE + "audit-list", "tx-l", "{ \"fromDate\": \"" + TODAY + "\", \"toDate\": \"" + TODAY + "\", \"size\": 2 }", uw)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.size").value(2))
                .andExpect(jsonPath("$.body.items.length()").value(2))
                .andExpect(jsonPath("$.body.items[0].globalTxId").value("tx-au-3"))
                .andExpect(jsonPath("$.body.items[1].globalTxId").value("tx-au-2"))
                .andExpect(jsonPath("$.body.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
        // 대상 종류 / 대상 식별 앞부분 / 처리 사번 조건
        call(BASE + "audit-list", "tx-l", "{ \"targetType\": \"POLICY\", \"empId\": \"A501\" }", uw)
                .andExpect(jsonPath("$.body.items[0].targetKey").value("01/DRIVER_AGE"))
                .andExpect(jsonPath("$.body.items[0].beforeVal").value("true"))
                .andExpect(jsonPath("$.body.items[0].afterVal").value("false"))
                .andExpect(jsonPath("$.body.items[0].empId").value("A501"));
        call(BASE + "audit-list", "tx-l", "{ \"targetType\": \"RATE\", \"targetKey\": \"01/CA00\" }", uw)
                .andExpect(jsonPath("$.body.items[0].globalTxId").value("tx-au-1"));
        // LIKE 와일드카드는 글자 그대로 취급한다
        call(BASE + "audit-list", "tx-l", "{ \"targetKey\": \"%\" }", uw).andExpect(jsonPath("$.body.totalElements").value(0));
        // 입력 오류
        call(BASE + "audit-list", "tx-l", "{ \"targetType\": \"COLOR\" }", uw).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-ADM-001"));
        call(BASE + "audit-list", "tx-l", "{ \"size\": 0 }", uw).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.responseCode").value("E-CTR-016"));
    }

    @Test
    void 감사_이력_조회_API는_심사_계약관리_ADMIN만_할_수_있다() throws Exception {
        for (String role : new String[] {Roles.UNDERWRITER, Roles.OPERATOR, Roles.ADMIN}) {
            call(BASE + "audit-list", "tx-r", "{}", TestAuth.withRoles(role)).andExpect(status().isOk());
        }
        call(BASE + "audit-list", "tx-r", "{}", TestAuth.withRoles(Roles.AGENT)).andExpect(status().isForbidden());
    }
}
