package com.insur.auth.security;

import org.springframework.security.core.AuthenticationException;

/** 로그인 화면에서 난 업무 오류(E-AUTH-10x). 메시지는 로그인 화면에 그대로 보여 준다. */
public class EmployeeLoginException extends AuthenticationException {

    private final String code;

    public EmployeeLoginException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
