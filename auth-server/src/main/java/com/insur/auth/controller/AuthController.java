package com.insur.auth.controller;

import com.insur.auth.common.CommonMessage;
import com.insur.auth.service.AuthService;
import com.insur.auth.service.dto.AuthDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인, 본인 정보, 비밀번호 변경. 요청과 응답은 코어 백엔드와 같은 공통 전문(header + body)이다.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 사번·비밀번호로 로그인해 토큰을 받는다. (토큰 불필요) */
    @PostMapping("/login")
    public CommonMessage<AuthDto.TokenResponse> login(@RequestBody CommonMessage<AuthDto.LoginRequest> request,
                                                      HttpServletRequest servletRequest) {
        AuthDto.LoginRequest body = request.requireBody();
        return CommonMessage.success(request.getHeader(),
                authService.login(body.getEmpId(), body.getPassword(), servletRequest.getRemoteAddr()));
    }

    /** 토큰 주인의 사원 정보를 조회한다. 바디는 비워 둔다. */
    @PostMapping("/me")
    public CommonMessage<AuthDto.Profile> me(@RequestBody CommonMessage<Map<String, Object>> request,
                                             Authentication authentication) {
        return CommonMessage.success(request.getHeader(), authService.profile(authentication.getName()));
    }

    /** 본인 비밀번호를 바꾸고 새 토큰을 받는다. 비밀번호 변경이 필요한 제한 토큰으로도 호출할 수 있다. */
    @PostMapping("/password")
    public CommonMessage<AuthDto.TokenResponse> changePassword(
            @RequestBody CommonMessage<AuthDto.PasswordChangeRequest> request, Authentication authentication) {
        AuthDto.PasswordChangeRequest body = request.requireBody();
        return CommonMessage.success(request.getHeader(),
                authService.changePassword(authentication.getName(), body, request.getHeader().getGlobalTxId()));
    }
}
