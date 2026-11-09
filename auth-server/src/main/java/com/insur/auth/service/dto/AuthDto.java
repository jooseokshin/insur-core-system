package com.insur.auth.service.dto;

import java.time.LocalDateTime;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 본인 정보·비밀번호 변경 요청과 응답 바디
 */
public final class AuthDto {

    private AuthDto() {
    }

    /** 사원 본인 정보 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Profile {
        /** 사번 */
        private String empId;
        /** 이름 */
        private String empNm;
        /** 부서명 */
        private String deptNm;
        /** 역할 */
        private Set<String> roles;
        /** 마지막 로그인 일시 (처음이면 null) */
        private LocalDateTime lastLoginDt;
        /** 비밀번호 변경 일시 */
        private LocalDateTime pwdChgDt;
    }

    /** 비밀번호 변경 요청 */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PasswordChangeRequest {
        /** 현재 비밀번호 */
        private String currentPassword;
        /** 새 비밀번호 (8~64자, 영문·숫자·특수문자 포함, 사번 포함 불가) */
        private String newPassword;
    }
}
