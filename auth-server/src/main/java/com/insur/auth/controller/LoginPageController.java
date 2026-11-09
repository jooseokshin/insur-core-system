package com.insur.auth.controller;

import com.insur.auth.common.BusinessException;
import com.insur.auth.domain.EmployeeRepository;
import com.insur.auth.security.EmployeeAuthenticationProvider;
import com.insur.auth.security.LoginSuccessHandler;
import com.insur.auth.service.AuthService;
import com.insur.auth.service.dto.AuthDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 인가 서버가 직접 그리는 화면. 비밀번호는 이 화면에만 입력하고, 업무 화면(SPA)은 비밀번호를 받지 않는다.
 * <ul>
 *     <li>GET /auth/login: 로그인 화면. 제출(POST)은 Spring Security 폼 로그인이 처리한다 (SecurityConfig).</li>
 *     <li>GET·POST /auth/password-change: 임시 비밀번호 사원의 첫 변경. 변경을 마쳐야 로그인 세션이 생기고, 그때 원래 인가 요청으로 돌아간다.</li>
 * </ul>
 */
@Controller
public class LoginPageController {

    private final AuthService authService;
    private final EmployeeRepository employeeRepository;
    private final Clock clock;
    private final boolean devHint;
    private final RequestCache requestCache = new HttpSessionRequestCache();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public LoginPageController(AuthService authService, EmployeeRepository employeeRepository, Clock clock,
                               @Value("${app.login.dev-hint:false}") boolean devHint) {
        this.authService = authService;
        this.employeeRepository = employeeRepository;
        this.clock = clock;
        this.devHint = devHint;
    }

    @GetMapping("/auth/login")
    public String login(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);
        if (session != null && request.getParameter("error") != null
                && session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION) instanceof AuthenticationException e) {
            model.addAttribute("error", e.getMessage());
            session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        }
        model.addAttribute("loggedOut", request.getParameter("logout") != null);
        model.addAttribute("devHint", devHint);
        return "login";
    }

    @GetMapping("/auth/password-change")
    public String passwordChangeForm(HttpServletRequest request, Model model) {
        String empId = pendingEmpId(request);
        if (empId == null) {
            return "redirect:/auth/login";
        }
        model.addAttribute("empId", empId);
        return "password-change";
    }

    @PostMapping("/auth/password-change")
    public String changePassword(@RequestParam(defaultValue = "") String currentPassword,
                                 @RequestParam(defaultValue = "") String newPassword,
                                 @RequestParam(defaultValue = "") String confirmPassword,
                                 HttpServletRequest request, HttpServletResponse response, Model model) {
        String empId = pendingEmpId(request);
        if (empId == null) {
            return "redirect:/auth/login";
        }
        model.addAttribute("empId", empId);
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "새 비밀번호가 서로 다릅니다.");
            return "password-change";
        }
        try {
            authService.changePassword(empId, new AuthDto.PasswordChangeRequest(currentPassword, newPassword),
                    UUID.randomUUID().toString());
        } catch (BusinessException e) {
            model.addAttribute("error", e.getErrorMessage());
            return "password-change";
        }
        // 변경을 마쳤으니 이제 로그인 세션을 만든다. 세션 ID 를 바꿔 고정 공격을 막는다.
        request.getSession().removeAttribute(LoginSuccessHandler.PENDING_PASSWORD_CHANGE);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(EmployeeAuthenticationProvider.authenticated(
                employeeRepository.findById(empId).orElseThrow(), clock.instant()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        return "redirect:" + LoginSuccessHandler.takeSavedTarget(requestCache, request, response);
    }

    private static String pendingEmpId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(LoginSuccessHandler.PENDING_PASSWORD_CHANGE);
    }
}
