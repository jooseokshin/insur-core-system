package com.insur.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.insur.auth.domain.Employee;
import com.insur.auth.domain.EmployeeRepository;
import com.insur.auth.domain.LoginHistoryRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth 로그인(Authorization Code + PKCE)·비밀번호 변경·사원 관리를 실제 DB(auth 스키마)로 검증한다. 테스트가 끝나면 롤백된다.
 * 브라우저가 하는 일(인가 요청 → 로그인 화면 → 인가 코드 → 토큰 교환)을 세션 하나로 그대로 따라간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {

    private static final String PASSWORD = "Good#Pass123";
    private static final String CLIENT_ID = "insur-web";
    private static final String REDIRECT_URI = "http://127.0.0.1:8088/callback";

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

    // ---- 브라우저 흉내 ----

    /** PKCE 값 한 쌍 (verifier 는 화면이 보관, challenge 는 인가 요청에 싣는다) */
    private record Pkce(String verifier, String challenge) {
        static Pkce create() throws Exception {
            String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
        }
    }

    /** 인가 요청. 인가 엔드포인트는 GET 파라미터를 쿼리 문자열에서 읽으므로 실제 브라우저처럼 주소에 싣는다. */
    private ResultActions authorize(MockHttpSession session, Pkce pkce) throws Exception {
        return authorize(session, pkce, REDIRECT_URI);
    }

    private ResultActions authorize(MockHttpSession session, Pkce pkce, String redirectUri) throws Exception {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/auth/oauth2/authorize")
                .queryParam("response_type", "code").queryParam("client_id", CLIENT_ID).queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "openid profile").queryParam("state", "st-1");
        if (pkce != null) {
            uri.queryParam("code_challenge", pkce.challenge()).queryParam("code_challenge_method", "S256");
        }
        return mockMvc.perform(get(uri.encode().build().toUri()).session(session).accept(MediaType.TEXT_HTML));
    }

    private ResultActions formLogin(MockHttpSession session, String empId, String password) throws Exception {
        return mockMvc.perform(post("/auth/login").session(session).with(csrf())
                .param("empId", empId).param("password", password));
    }

    /** 실패한 로그인 뒤 화면에 보이는 오류 문구 */
    private String loginError(MockHttpSession session) throws Exception {
        return (String) mockMvc.perform(get("/auth/login").param("error", "").session(session))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("error");
    }

    private static String location(ResultActions result) {
        return result.andReturn().getResponse().getHeader("Location");
    }

    private static String codeFrom(String location) {
        assertThat(location).startsWith(REDIRECT_URI);
        var params = UriComponentsBuilder.fromUri(URI.create(location)).build().getQueryParams();
        assertThat(params.getFirst("state")).isEqualTo("st-1");
        return params.getFirst("code");
    }

    private ResultActions exchange(String code, String verifier) throws Exception {
        return mockMvc.perform(post("/auth/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code").param("code", code).param("redirect_uri", REDIRECT_URI)
                .param("client_id", CLIENT_ID).param("code_verifier", verifier));
    }

    /** 로그인 화면에서 로그인을 마친 세션 */
    private MockHttpSession loggedIn(String empId, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        authorize(session, Pkce.create()).andExpect(status().is3xxRedirection());
        formLogin(session, empId, password).andExpect(status().is3xxRedirection());
        return session;
    }

    /** 세션으로 인가 코드를 받아 토큰으로 바꾼다 */
    private ResultActions tokenWith(MockHttpSession session) throws Exception {
        Pkce pkce = Pkce.create();
        String code = codeFrom(location(authorize(session, pkce).andExpect(status().is3xxRedirection())));
        return exchange(code, pkce.verifier());
    }

    private String accessToken(String empId, String password) throws Exception {
        String res = tokenWith(loggedIn(empId, password)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.access_token");
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject(adminId)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    // ---- OAuth 로그인 ----

    @Test
    void 인가_요청부터_토큰까지_코어가_검증하는_규약의_토큰을_받는다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Pkce pkce = Pkce.create();
        // 1) 로그인하지 않았으면 인가 서버의 로그인 화면으로 보낸다
        assertThat(location(authorize(session, pkce).andExpect(status().is3xxRedirection()))).endsWith("/auth/login");
        mockMvc.perform(get("/auth/login").session(session)).andExpect(status().isOk())
                .andExpect(model().attribute("devHint", false));
        // 2) 로그인하면 원래 인가 요청으로 돌아가고 3) 인가 코드를 받는다
        String back = location(formLogin(session, agentId, PASSWORD).andExpect(status().is3xxRedirection()));
        assertThat(back).startsWith("/auth/oauth2/authorize?");
        String code = codeFrom(location(mockMvc.perform(get(URI.create(back)).session(session).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())));
        // 4) 코드와 PKCE verifier 로 토큰을 받는다
        String res = exchange(code, pkce.verifier()).andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").isNumber())
                .andExpect(jsonPath("$.id_token").isString())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String accessToken = JsonPath.read(res, "$.access_token");
        Jwt jwt = jwtDecoder.decode(accessToken);
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsKey("kid");
        assertThat(jwt.getSubject()).isEqualTo(agentId);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("http://127.0.0.1:8088");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("AGENT", "OPERATOR");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("테스트" + agentId);
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
        assertThat(jwtDecoder.decode(JsonPath.read(res, "$.id_token")).getClaimAsString("name")).isEqualTo("테스트" + agentId);
        assertThat(loginHistoryRepository.findByEmpId(agentId, PageRequest.of(0, 10)).getContent())
                .singleElement().satisfies(h -> assertThat(h.getResultCd()).isEqualTo("SUCCESS"));

        // 받은 토큰으로 본인 정보를 조회할 수 있다
        mockMvc.perform(post("/auth/me").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"header\": {} }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.empId").value(agentId))
                .andExpect(jsonPath("$.body.lastLoginDt").exists());
        // 같은 코드는 한 번만 쓸 수 있다
        exchange(code, pkce.verifier()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void PKCE_없이는_코드를_주지_않고_verifier_가_다르면_토큰을_주지_않는다() throws Exception {
        MockHttpSession session = loggedIn(agentId, PASSWORD);
        assertThat(location(authorize(session, null).andExpect(status().is3xxRedirection())))
                .startsWith(REDIRECT_URI).contains("error=invalid_request");

        Pkce pkce = Pkce.create();
        String code = codeFrom(location(authorize(session, pkce)));
        exchange(code, Pkce.create().verifier()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void 등록되지_않은_주소로는_돌려보내지_않는다() throws Exception {
        MockHttpSession session = loggedIn(agentId, PASSWORD);
        Pkce pkce = Pkce.create();
        authorize(session, pkce, "https://evil.example/callback").andExpect(status().isBadRequest());
    }

    @Test
    void 공개_메타데이터와_공개키를_내놓는다() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://127.0.0.1:8088"))
                .andExpect(jsonPath("$.authorization_endpoint").value("http://127.0.0.1:8088/auth/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value("http://127.0.0.1:8088/auth/oauth2/token"))
                .andExpect(jsonPath("$.jwks_uri").value("http://127.0.0.1:8088/auth/oauth2/jwks"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));
        mockMvc.perform(get("/auth/oauth2/jwks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void 없는_사번과_틀린_비밀번호는_같은_오류이고_다섯_번_틀리면_잠긴다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertThat(location(formLogin(session, "nobody-" + agentId, PASSWORD))).isEqualTo("/auth/login?error");
        assertThat(loginError(session)).isEqualTo("사번 또는 비밀번호가 올바르지 않습니다.");

        for (int i = 1; i <= 4; i++) {
            formLogin(session, agentId, "wrong");
            assertThat(loginError(session))
                    .isEqualTo("사번 또는 비밀번호가 올바르지 않습니다. (" + (5 - i) + "회 더 틀리면 계정이 잠깁니다)");
        }
        formLogin(session, agentId, "wrong");
        assertThat(loginError(session)).contains("계정이 잠겼습니다");
        // 잠긴 뒤에는 맞는 비밀번호도 거절한다
        formLogin(session, agentId, PASSWORD);
        assertThat(loginError(session)).contains("계정이 잠겼습니다");

        Employee locked = employeeRepository.findById(agentId).orElseThrow();
        assertThat(locked.isLocked()).isTrue();
        assertThat(locked.getLoginFailCnt()).isEqualTo(5);
        assertThat(loginHistoryRepository.findByEmpId(agentId, PageRequest.of(0, 10)).getContent())
                .extracting("resultCd").containsOnly("BAD_CREDENTIALS", "LOCKED");
    }

    @Test
    void 로그인_폼은_CSRF_토큰이_없으면_거절한다() throws Exception {
        mockMvc.perform(post("/auth/login").param("empId", agentId).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        assertThat(employeeRepository.findById(agentId).orElseThrow().getLastLoginDt()).isNull();
    }

    @Test
    void 성공하면_연속_실패_횟수가_초기화된다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        formLogin(session, agentId, "wrong");
        formLogin(session, agentId, "wrong");
        assertThat(location(formLogin(session, agentId, PASSWORD))).isEqualTo("/");
        assertThat(employeeRepository.findById(agentId).orElseThrow().getLoginFailCnt()).isZero();
    }

    @Test
    void 사용_중지된_계정은_로그인할_수_없다() throws Exception {
        call(post("/auth/admin/emp-update").with(admin()),
                "{ \"empId\": \"" + agentId + "\", \"empNm\": \"테스트\", \"roles\": [\"AGENT\"], \"active\": false }")
                .andExpect(status().isOk());
        MockHttpSession session = new MockHttpSession();
        formLogin(session, agentId, PASSWORD);
        assertThat(loginError(session)).isEqualTo("사용이 중지된 계정입니다. 관리자에게 문의해 주세요.");
    }

    @Test
    void 로그인_세션이_남아_있어도_사용_중지되면_다음_토큰을_받지_못한다() throws Exception {
        MockHttpSession session = loggedIn(agentId, PASSWORD);
        tokenWith(session).andExpect(status().isOk());
        call(post("/auth/admin/emp-update").with(admin()),
                "{ \"empId\": \"" + agentId + "\", \"empNm\": \"테스트\", \"roles\": [\"AGENT\"], \"active\": false }")
                .andExpect(status().isOk());
        tokenWith(session).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.error_description").value(org.hamcrest.Matchers.startsWith("E-AUTH-103")));
    }

    @Test
    void 임시_비밀번호_사원은_변경을_마쳐야_토큰을_받는다() throws Exception {
        String newbie = "nb" + agentId;
        employee(newbie, Set.of("UNDERWRITER"), true);
        MockHttpSession session = new MockHttpSession();
        Pkce pkce = Pkce.create();
        authorize(session, pkce);
        assertThat(location(formLogin(session, newbie, PASSWORD))).isEqualTo("/auth/password-change");
        // 변경 전에는 로그인 세션이 없어서 인가 코드를 받을 수 없다
        assertThat(location(authorize(session, pkce))).endsWith("/auth/login");
        authorize(session, pkce); // 원래 가려던 인가 요청을 다시 저장해 둔다

        mockMvc.perform(get("/auth/password-change").session(session)).andExpect(status().isOk())
                .andExpect(model().attribute("empId", newbie));
        changeForm(session, "bad", "New#Pass456", "New#Pass456").andExpect(model().attribute("error", "현재 비밀번호가 올바르지 않습니다."));
        changeForm(session, PASSWORD, PASSWORD, PASSWORD).andExpect(model().attribute("error", "새 비밀번호는 현재 비밀번호와 달라야 합니다."));
        changeForm(session, PASSWORD, "onlyletters", "onlyletters").andExpect(status().isOk());
        changeForm(session, PASSWORD, newbie + "#9", newbie + "#9").andExpect(model().attribute("error", "비밀번호에 사번을 포함할 수 없습니다."));
        changeForm(session, PASSWORD, "New#Pass456", "New#Pass457").andExpect(model().attribute("error", "새 비밀번호가 서로 다릅니다."));

        String back = location(changeForm(session, PASSWORD, "New#Pass456", "New#Pass456").andExpect(status().is3xxRedirection()));
        assertThat(back).startsWith("/auth/oauth2/authorize?");
        String code = codeFrom(location(mockMvc.perform(get(URI.create(back)).session(session).accept(MediaType.TEXT_HTML))));
        String res = exchange(code, pkce.verifier()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(jwtDecoder.decode(JsonPath.<String>read(res, "$.access_token")).getClaimAsStringList("roles"))
                .containsExactly("UNDERWRITER");
        assertThat(employeeRepository.findById(newbie).orElseThrow().isPasswordChangeRequired()).isFalse();
    }

    private ResultActions changeForm(MockHttpSession session, String current, String next, String confirm) throws Exception {
        return mockMvc.perform(post("/auth/password-change").session(session).with(csrf())
                .param("currentPassword", current).param("newPassword", next).param("confirmPassword", confirm));
    }

    @Test
    void 업무_화면에서_본인_비밀번호를_바꾼다() throws Exception {
        String bearer = "Bearer " + accessToken(agentId, PASSWORD);
        call(post("/auth/password").header("Authorization", bearer),
                "{ \"currentPassword\": \"bad\", \"newPassword\": \"New#Pass456\" }")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.responseCode").value("E-AUTH-105"));
        call(post("/auth/password").header("Authorization", bearer),
                "{ \"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"onlyletters\" }")
                .andExpect(jsonPath("$.header.responseCode").value("E-AUTH-104"));
        call(post("/auth/password").header("Authorization", bearer),
                "{ \"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"New#Pass456\" }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.empId").value(agentId))
                .andExpect(jsonPath("$.body.pwdChgDt").exists());
        MockHttpSession session = new MockHttpSession();
        formLogin(session, agentId, PASSWORD);
        assertThat(loginError(session)).startsWith("사번 또는 비밀번호가 올바르지 않습니다.");
        assertThat(location(formLogin(new MockHttpSession(), agentId, "New#Pass456"))).isEqualTo("/");
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

        assertThat(location(formLogin(new MockHttpSession(), newId, temporary))).isEqualTo("/auth/password-change");
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
    void 역할을_바꾸면_다음_토큰부터_반영되고_감사_이력이_남는다() throws Exception {
        call(post("/auth/admin/emp-update").with(admin()),
                "{ \"empId\": \"" + agentId + "\", \"empNm\": \"테스트" + agentId + "\", \"deptNm\": \"테스트팀\", \"roles\": [\"UNDERWRITER\"] }")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.roles[0]").value("UNDERWRITER"));
        assertThat(jwtDecoder.decode(accessToken(agentId, PASSWORD)).getClaimAsStringList("roles"))
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
        MockHttpSession failures = new MockHttpSession();
        for (int i = 0; i < 5; i++) {
            formLogin(failures, agentId, "wrong");
        }
        call(post("/auth/admin/emp-list").with(admin()), "{ \"keyword\": \"" + agentId + "\", \"lockedOnly\": true }")
                .andExpect(jsonPath("$.body.totalElements").value(1))
                .andExpect(jsonPath("$.body.items[0].locked").value(true));

        String temporary = JsonPath.read(call(post("/auth/admin/emp-reset-password").with(admin()),
                "{ \"empId\": \"" + agentId + "\" }").andExpect(status().isOk())
                .andExpect(jsonPath("$.body.employee.locked").value(false))
                .andReturn().getResponse().getContentAsString(), "$.body.temporaryPassword");

        assertThat(location(formLogin(new MockHttpSession(), agentId, PASSWORD))).isEqualTo("/auth/login?error");
        assertThat(location(formLogin(new MockHttpSession(), agentId, temporary))).isEqualTo("/auth/password-change");
        call(post("/auth/admin/emp-unlock").with(admin()), "{ \"empId\": \"" + agentId + "\" }")
                .andExpect(jsonPath("$.header.responseCode").value("E-EMP-007"));
    }

    @Test
    void 잠금_해제는_비밀번호를_그대로_두고_다시_로그인하게_한다() throws Exception {
        MockHttpSession failures = new MockHttpSession();
        for (int i = 0; i < 5; i++) {
            formLogin(failures, agentId, "wrong");
        }
        call(post("/auth/admin/emp-unlock").with(admin()), "{ \"empId\": \"" + agentId + "\" }")
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.locked").value(false));
        assertThat(location(formLogin(new MockHttpSession(), agentId, PASSWORD))).isEqualTo("/");
    }

    @Test
    void 사원_목록은_역할과_이름으로_찾고_로그인_이력은_최근_순이다() throws Exception {
        call(post("/auth/admin/emp-list").with(admin()), "{ \"keyword\": \"" + agentId + "\", \"roleCd\": \"OPERATOR\" }")
                .andExpect(jsonPath("$.body.totalElements").value(1))
                .andExpect(jsonPath("$.body.items[0].roles.length()").value(2));
        call(post("/auth/admin/emp-list").with(admin()), "{ \"keyword\": \"" + agentId + "\", \"roleCd\": \"ADMIN\" }")
                .andExpect(jsonPath("$.body.totalElements").value(0));

        formLogin(new MockHttpSession(), agentId, "wrong");
        formLogin(new MockHttpSession(), agentId, PASSWORD);
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
