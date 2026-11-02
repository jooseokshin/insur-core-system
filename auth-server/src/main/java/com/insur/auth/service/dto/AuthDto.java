package com.insur.auth.service.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 로그인·본인 정보·비밀번호 변경 요청과 응답 바디
 */
public final class AuthDto {

    private AuthDto() {
    }

    /** 로그인 요청 */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        /** 사번 */
        private String empId;
        /** 비밀번호 */
        private String password;
    }

    /** 로그인(또는 비밀번호 변경) 성공 응답 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenResponse {
        /** 액세스 토큰 (JWT). 코어 백엔드 호출 시 Authorization: Bearer 로 보낸다 */
        private String accessToken;
        /** 토큰 유형 (Bearer) */
        private String tokenType;
        /** 남은 유효시간(초) */
        private long expiresIn;
        /** 만료 시각 */
        private OffsetDateTime expiresAt;
        /** 비밀번호를 바꿔야 하는지. true 면 토큰에 역할이 없어 업무 API 를 쓸 수 없고 비밀번호 변경만 할 수 있다 */
        private boolean passwordChangeRequired;
        /** 사원 정보 */
        private Profile profile;
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
        /** 이번 로그인 이전의 마지막 로그인 일시 (처음이면 null) */
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
