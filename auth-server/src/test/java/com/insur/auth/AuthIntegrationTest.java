package com.insur.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.auth.domain.Employee;
import com.insur.auth.domain.EmployeeRepository;
import com.insur.auth.domain.LoginHistoryRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인·토큰·비밀번호 변경·사원 관리를 실제 DB(auth 스키마)로 검증한다. 테스트가 끝나면 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {

    private static final String PASSWORD = "Good#Pass123";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    LoginHistoryRepository loginHistoryRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JwtDecoder jwtDecoder;

    private String agentId;
    private String adminId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        agentId = "ag" + suffix;
        adminId = "ad" + suffix;
        employee(agentId, Set.of("AGENT", "OPERATOR"), false);
        employee(adminId, Set.of("ADMIN"), false);
    }

    private Employee employee(String empId, Set<String> roles, boolean passwordChangeRequired) {
        return employeeRepository.save(Employee.create(empId, "테스트" + empId, "테스트팀", roles,
                passwordEncoder.encode(PASSWORD), passwordChangeRequired, "TEST", LocalDateTime.now()));
    }

    private ResultActions call(MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON)
                .content("{ \"header\": { \"globalTxId\": \"tx-" + UUID.randomUUID() + "\" }, \"body\": " + body + " }"));
    }

    private ResultActions login(String empId, String password) throws Exception {
        return call(post("/auth/login"), "{ \"empId\": \"" + empId + "\", \"password\": \"" + password + "\" }");
    }

    private String tokenOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.body.accessToken");
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject(adminId)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void 로그인하면_코어가_검증하는_규약의_토큰을_받는다() throws Exception {
        ResultActions result = login(agentId, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.responseCode").value("0000"))
                .andExpect(jsonPath("$.body.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.body.expiresIn").value(3600))
                .andExpect(jsonPath("$.body.passwordChangeRequired").value(false))
                .andExpect(jsonPath("$.body.profile.empId").value(agentId))
                .andExpect(jsonPath("$.body.profile.roles[0]").value("AGENT"))
                .andExpect(jsonPath("$.body.profile.lastLoginDt").doesNotExist());

        Jwt jwt = jwtDecoder.decode(tokenOf(result));
        assertThat(jwt.getSubject()).isEqualTo(agentId);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("AGENT", "OPERATOR");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("테스트" + agentId);
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
        assertThat(employeeRepository.findById(agentId).orElseThrow().getLastLoginDt()).isNotNull();
        assertThat(loginHistoryRepository.findByEmpId(agentId, PageRequest.of(0, 10)).getContent())
                .singleElement().satisfies(h -> assertThat(h.getResultCd()).isEqualTo("SUCCESS"));

        // 받은 토큰으로 본인 정보를 조회할 수 있다
        mockMvc.perform(post("/auth/me").header("Authorization", "Bearer " + tokenOf(login(agentId, PASSWORD)))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"header\": {} }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.empId").value(agentId))
                .andExpect(jsonPath("$.body.lastLoginDt").exists());
    }

    @Test
    void 없는_사번과_틀린_비밀번호는_같은_오류이고_다섯_번_틀리면_잠긴다() throws Exception {
        login("nobody-" + agentId, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-101"))
                .andExpect(jsonPath("$.header.responseMsg").value("사번 또는 비밀번호가 올바르지 않습니다."));

        for (int i = 1; i <= 4; i++) {
            login(agentId, "wrong")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-101"))
                    .andExpect(jsonPath("$.header.responseMsg").value(
                            "사번 또는 비밀번호가 올바르지 않습니다. (" + (5 - i) + "회 더 틀리면 계정이 잠깁니다)"));
        }
        login(agentId, "wrong").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-102"));
        // 잠긴 뒤에는 맞는 비밀번호도 거절한다
        login(agentId, PASSWORD).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-102"));

        Employee locked = employeeRepository.findById(agentId).orElseThrow();
        assertThat(locked.isLocked()).isTrue();
        assertThat(locked.getLoginFailCnt()).isEqualTo(5);
        assertThat(loginHistoryRepository.findByEmpId(agentId, PageRequest.of(0, 10)).getContent())
                .extracting("resultCd").containsOnly("BAD_CREDENTIALS", "LOCKED");
    }

    @Test
    void 성공하면_연속_실패_횟수가_초기화된다() throws Exception {
        login(agentId, "wrong");
        login(agentId, "wrong");
        login(agentId, PASSWORD).andExpect(status().isOk());
        assertThat(employeeRepository.findById(agentId).orElseThrow().getLoginFailCnt()).isZero();
    }

    @Test
    void 사용_중지된_계정은_로그인할_수_없다() throws Exception {
        call(post("/auth/admin/emp-update").with(admin()),
                "{ \"empId\": \"" + agentId + "\", \"empNm\": \"테스트\", \"roles\": [\"AGENT\"], \"active\": false }")
                .andExpect(status().isOk());
        login(agentId, PASSWORD).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-103"));
    }

    @Test
    void 비밀번호_변경이_필요하면_역할_없는_토큰을_받고_변경_후_정상_토큰을_받는다() throws Exception {
        String newbie = "nb" + agentId;
        employee(newbie, Set.of("UNDERWRITER"), true);

        String restricted = tokenOf(login(newbie, PASSWORD)
                .andExpect(jsonPath("$.body.passwordChangeRequired").value(true)));
        Jwt jwt = jwtDecoder.decode(restricted);
        assertThat(jwt.getClaimAsStringList("roles")).isEmpty();
        assertThat(jwt.getClaimAsBoolean("pwd_chg_req")).isTrue();

        String bearer = "Bearer " + restricted;
        call(post("/auth/password").header("Authorization", bearer),
                "{ \"currentPassword\": \"bad\", \"newPassword\": \"New#Pass456\" }")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-AUTH-105"));
        call(post("/auth/password").header("Authorization", bearer),
                "{ \"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"" + PASSWORD + "\" }")
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-106"));
        call(post("/auth/password").header("Authorization", bearer),
                "{ \"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"onlyletters\" }")
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-104"));
        call(post("/auth/password").header("Authorization", bearer),
                "{ \"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"" + newbie + "#9\" }")
                .andExpect(jsonPath("$.header.responseMsg").value("비밀번호에 사번을 포함할 수 없습니다."));

        String full = tokenOf(call(post("/auth/password").header("Authorization", bearer),
                "{ \"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"New#Pass456\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.passwordChangeRequired").value(false)));
        assertThat(jwtDecoder.decode(full).getClaimAsStringList("roles")).containsExactly("UNDERWRITER");
        login(newbie, "New#Pass456").andExpect(status().isOk());
    }

    @Test
    void 관리자는_사원을_등록하고_임시_비밀번호로_첫_로그인을_하게_한다() throws Exception {
        String newId = "nw" + agentId;
        String res = call(post("/auth/admin/emp-create").with(admin()),
                "{ \"empId\": \"" + newId + "\", \"empNm\": \"신입\", \"deptNm\": \"강남지점\", \"roles\": [\"AGENT\"] }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.employee.empId").value(newId))
                .andExpect(jsonPath("$.body.employee.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.body.employee.regEmpId").value(adminId))
                .andReturn().getResponse().getContentAsString();
        String temporary = JsonPath.read(res, "$.body.temporaryPassword");

        login(newId, temporary).andExpect(status().isOk()).andExpect(jsonPath("$.body.passwordChangeRequired").value(true));
        call(post("/auth/admin/emp-create").with(admin()),
                "{ \"empId\": \"" + newId + "\", \"empNm\": \"중복\", \"roles\": [\"AGENT\"] }")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.responseCode").value("E-EMP-002"));
        call(post("/auth/admin/emp-create").with(admin()),
                "{ \"empId\": \"x" + newId + "\", \"empNm\": \"역할오류\", \"roles\": [\"BOSS\"] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-EMP-005"));
        // 보상 담당(CLAIM) 역할도 부여할 수 있다 (V2)
        call(post("/auth/admin/emp-create").with(admin()),
                "{ \"empId\": \"c" + newId + "\", \"empNm\": \"보상\", \"roles\": [\"CLAIM\"] }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.employee.roles[0]").value("CLAIM"));
        call(post("/auth/admin/emp-create").with(admin()),
                "{ \"empId\": \"a b\", \"empNm\": \"사번오류\", \"roles\": [\"AGENT\"] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-EMP-001"));

        call(post("/auth/admin/emp-detail").with(admin()), "{ \"empId\": \"" + newId + "\" }")
                .andExpect(jsonPath("$.body.audits[0].action").value("CREATE"))
                .andExpect(jsonPath("$.body.audits[0].actEmpId").value(adminId));
    }

    @Test
    void 역할을_바꾸면_다음_로그인_토큰부터_반영되고_감사_이력이_남는다() throws Exception {
        call(post("/auth/admin/emp-update").with(admin()),
                "{ \"empId\": \"" + agentId + "\", \"empNm\": \"테스트" + agentId + "\", \"deptNm\": \"테스트팀\", \"roles\": [\"UNDERWRITER\"] }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.roles[0]").value("UNDERWRITER"));
        assertThat(jwtDecoder.decode(tokenOf(login(agentId, PASSWORD))).getClaimAsStringList("roles"))
                .containsExactly("UNDERWRITER");

        call(post("/auth/admin/emp-detail").with(admin()), "{ \"empId\": \"" + agentId + "\" }")
                .andExpect(jsonPath("$.body.audits[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.body.audits[0].detail").value("역할 [AGENT, OPERATOR]→[UNDERWRITER]"));
        // 같은 값으로 다시 보내면 바뀐 게 없다
        call(post("/auth/admin/emp-update").with(admin()),
                "{ \"empId\": \"" + agentId + "\", \"empNm\": \"테스트" + agentId + "\", \"deptNm\": \"테스트팀\", \"roles\": [\"UNDERWRITER\"] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-EMP-006"));
    }

    @Test
    void 관리자는_자기_ADMIN_역할을_빼거나_자신을_사용_중지할_수_없다() throws Exception {
        call(post("/auth/admin/emp-update").with(admin()),
                "{ \"empId\": \"" + adminId + "\", \"empNm\": \"관리\", \"roles\": [\"AGENT\"] }")
                .andExpect(jsonPath("$.header.responseCode").value("E-EMP-004"));
        call(post("/auth/admin/emp-update").with(admin()),
                "{ \"empId\": \"" + adminId + "\", \"empNm\": \"관리\", \"roles\": [\"ADMIN\"], \"active\": false }")
                .andExpect(jsonPath("$.header.responseCode").value("E-EMP-004"));
    }

    @Test
    void 비밀번호_초기화는_잠금을_풀고_다음_로그인_때_변경을_요구한다() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(agentId, "wrong");
        }
        call(post("/auth/admin/emp-list").with(admin()), "{ \"keyword\": \"" + agentId + "\", \"lockedOnly\": true }")
                .andExpect(jsonPath("$.body.totalElements").value(1))
                .andExpect(jsonPath("$.body.items[0].locked").value(true));

        String temporary = JsonPath.read(call(post("/auth/admin/emp-reset-password").with(admin()),
                "{ \"empId\": \"" + agentId + "\" }").andExpect(status().isOk())
                .andExpect(jsonPath("$.body.employee.locked").value(false))
                .andReturn().getResponse().getContentAsString(), "$.body.temporaryPassword");

        login(agentId, PASSWORD).andExpect(status().isUnauthorized());
        login(agentId, temporary).andExpect(status().isOk())
                .andExpect(jsonPath("$.body.passwordChangeRequired").value(true));
        call(post("/auth/admin/emp-unlock").with(admin()), "{ \"empId\": \"" + agentId + "\" }")
                .andExpect(jsonPath("$.header.responseCode").value("E-EMP-007"));
    }

    @Test
    void 잠금_해제는_비밀번호를_그대로_두고_다시_로그인하게_한다() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(agentId, "wrong");
        }
        call(post("/auth/admin/emp-unlock").with(admin()), "{ \"empId\": \"" + agentId + "\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.locked").value(false));
        login(agentId, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.body.passwordChangeRequired").value(false));
    }

    @Test
    void 사원_목록은_역할과_이름으로_찾고_로그인_이력은_최근_순이다() throws Exception {
        call(post("/auth/admin/emp-list").with(admin()), "{ \"keyword\": \"" + agentId + "\", \"roleCd\": \"OPERATOR\" }")
                .andExpect(jsonPath("$.body.totalElements").value(1))
                .andExpect(jsonPath("$.body.items[0].roles.length()").value(2));
        call(post("/auth/admin/emp-list").with(admin()), "{ \"keyword\": \"" + agentId + "\", \"roleCd\": \"ADMIN\" }")
                .andExpect(jsonPath("$.body.totalElements").value(0));

        login(agentId, "wrong");
        login(agentId, PASSWORD);
        String res = call(post("/auth/admin/login-history").with(admin()), "{ \"empId\": \"" + agentId + "\" }")
                .andExpect(jsonPath("$.body.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();
        List<String> results = JsonPath.read(res, "$.body.items[*].resultCd");
        assertThat(results).containsExactly("SUCCESS", "BAD_CREDENTIALS");
    }

    @Test
    void 사원_관리는_ADMIN만_쓸_수_있고_토큰이_없으면_401이다() throws Exception {
        call(post("/auth/admin/emp-list").with(jwt().jwt(t -> t.subject(agentId))
                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"))), "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-002"));
        call(post("/auth/admin/emp-list"), "{}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-001"));
        call(post("/auth/me"), "{}").andExpect(status().isUnauthorized());
    }
}
