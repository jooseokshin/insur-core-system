package com.insur.auth.service.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 사원·권한 관리(ADMIN) 요청과 응답 바디
 */
public final class EmployeeDto {

    private EmployeeDto() {
    }

    /** 사원 목록 검색 조건 (모두 선택) */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListRequest {
        /** 사번 앞부분 또는 이름 일부 */
        private String keyword;
        /** 역할 (이 역할을 가진 사원) */
        private String roleCd;
        /** 사용 여부 */
        private Boolean active;
        /** 잠긴 계정만 */
        private Boolean lockedOnly;
        /** 페이지 번호 (0부터) */
        private Integer page;
        /** 페이지 크기 (1~100, 기본 20) */
        private Integer size;
    }

    /** 사원 목록 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListResponse {
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private List<Item> items;
    }

    /** 사원 한 명 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String empId;
        private String empNm;
        private String deptNm;
        private Set<String> roles;
        /** 사용 여부 */
        private boolean active;
        /** 잠금 여부 */
        private boolean locked;
        /** 연속 로그인 실패 횟수 */
        private int loginFailCnt;
        /** 비밀번호 변경 필요 여부 */
        private boolean passwordChangeRequired;
        private LocalDateTime lastLoginDt;
        private LocalDateTime pwdChgDt;
        private LocalDateTime regDt;
        private String regEmpId;
        private LocalDateTime updDt;
        private String updEmpId;
    }

    /** 사원 등록 요청. 비밀번호는 서버가 임시로 만들어 응답에 한 번만 담는다 */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        /** 사번 (영문·숫자·_.- 3~20자) */
        private String empId;
        /** 이름 */
        private String empNm;
        /** 부서명 */
        private String deptNm;
        /** 역할 (하나 이상) */
        private Set<String> roles;
    }

    /** 사원 정보 변경 요청 */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String empId;
        private String empNm;
        private String deptNm;
        /** 역할 (하나 이상) */
        private Set<String> roles;
        /** 사용 여부 */
        private Boolean active;
    }

    /** 사번 하나를 지정하는 요청 (상세, 비밀번호 초기화, 잠금 해제) */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyRequest {
        private String empId;
    }

    /** 임시 비밀번호 응답. 화면에 한 번만 보여 주고 서버에는 해시만 남는다 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemporaryPasswordResponse {
        private Item employee;
        /** 임시 비밀번호. 첫 로그인 때 반드시 바꿔야 한다 */
        private String temporaryPassword;
    }

    /** 사원 상세 (정보 + 관리 감사 이력) */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetailResponse {
        private Item employee;
        private List<Audit> audits;
    }

    /** 사원 관리 감사 이력 한 건 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Audit {
        /** 작업 (CREATE, UPDATE, RESET_PASSWORD, UNLOCK, CHANGE_PASSWORD) */
        private String action;
        private String detail;
        private String actEmpId;
        private LocalDateTime actDt;
    }

    /** 로그인 이력 조회 요청 */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginHistoryRequest {
        /** 사번 (생략하면 전체) */
        private String empId;
        private Integer page;
        private Integer size;
    }

    /** 로그인 이력 (최근 순) */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginHistoryResponse {
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private List<LoginHistoryItem> items;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginHistoryItem {
        private String empId;
        /** 결과 (SUCCESS, BAD_CREDENTIALS, LOCKED, DISABLED) */
        private String resultCd;
        private String clientIp;
        private LocalDateTime loginDt;
    }
}
