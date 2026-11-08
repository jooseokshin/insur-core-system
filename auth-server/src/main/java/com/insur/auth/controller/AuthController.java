package com.insur.auth.controller;

import com.insur.auth.common.CommonMessage;
import com.insur.auth.service.AuthService;
import com.insur.auth.service.dto.AuthDto;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 본인 정보와 비밀번호 변경 API (Bearer 토큰 필요). 요청과 응답은 코어 백엔드와 같은 공통 전문(header + body)이다.
 * 로그인은 API 가 아니라 OAuth 인가 흐름의 로그인 화면({@link LoginPageController})에서 한다.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 토큰 주인의 사원 정보를 조회한다. 바디는 비워 둔다. */
    @PostMapping("/me")
    public CommonMessage<AuthDto.Profile> me(@RequestBody CommonMessage<Map<String, Object>> request,
                                             Authentication authentication) {
        return CommonMessage.success(request.getHeader(), authService.profile(authentication.getName()));
    }

    /** 본인 비밀번호를 바꾼다. 쓰던 토큰은 만료될 때까지 그대로 쓸 수 있다. */
    @PostMapping("/password")
    public CommonMessage<AuthDto.Profile> changePassword(
            @RequestBody CommonMessage<AuthDto.PasswordChangeRequest> request, Authentication authentication) {
        AuthDto.PasswordChangeRequest body = request.requireBody();
        return CommonMessage.success(request.getHeader(),
                authService.changePassword(authentication.getName(), body, request.getHeader().getGlobalTxId()));
    }
}
