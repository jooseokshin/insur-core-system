package com.insur.auth.domain;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사원 계정. 상태는 아래 메서드로만 바꾼다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Employee {

    /** 사번 (JWT 의 sub) */
    private String empId;

    /** 이름 */
    private String empNm;

    /** 부서명 */
    private String deptNm;

    /** 비밀번호 BCrypt 해시 */
    private String pwdHash;

    /** 사용 여부. false 면 로그인할 수 없다 */
    private boolean active;

    /** 잠금 여부. 비밀번호를 연속으로 틀리면 잠기고, 관리자가 풀어야 한다 */
    private boolean locked;

    /** 비밀번호 변경 필요 여부 (최초 등록, 관리자 초기화 직후) */
    private boolean passwordChangeRequired;

    /** 연속 로그인 실패 횟수. 성공하면 0 으로 돌아간다 */
    private int loginFailCnt;

    /** 비밀번호 변경 일시 */
    private LocalDateTime pwdChgDt;

    /** 마지막 로그인 성공 일시 */
    private LocalDateTime lastLoginDt;

    private LocalDateTime regDt;

    private String regEmpId;

    private LocalDateTime updDt;

    private String updEmpId;

    /** 역할 (AGENT, UNDERWRITER, OPERATOR, ADMIN) */
    private Set<String> roles = new HashSet<>();

    /**
     * 새 사원을 만든다. 처음 받은 비밀번호는 임시 비밀번호로 보고 첫 로그인 때 바꾸게 한다.
     *
     * @param passwordChangeRequired 첫 로그인 때 비밀번호를 바꿔야 하는지 (개발용 계정만 false)
     */
    public static Employee create(String empId, String empNm, String deptNm, Set<String> roles, String pwdHash,
                                  boolean passwordChangeRequired, String actorEmpId, LocalDateTime now) {
        Employee emp = new Employee();
        emp.empId = empId;
        emp.empNm = empNm;
        emp.deptNm = deptNm;
        emp.roles = new HashSet<>(roles);
        emp.pwdHash = pwdHash;
        emp.active = true;
        emp.passwordChangeRequired = passwordChangeRequired;
        emp.regDt = now;
        emp.regEmpId = actorEmpId;
        emp.updDt = now;
        emp.updEmpId = actorEmpId;
        return emp;
    }

    /** 정렬된 역할 목록 (응답과 토큰에 일정한 순서로 담는다) */
    public Set<String> sortedRoles() {
        return new TreeSet<>(roles);
    }

    /** 로그인 성공: 실패 횟수를 지우고 마지막 로그인 일시를 남긴다 */
    public void loginSucceeded(LocalDateTime now) {
        this.loginFailCnt = 0;
        this.lastLoginDt = now;
    }

    /**
     * 비밀번호 불일치: 실패 횟수를 올리고, 한도에 이르면 잠근다.
     *
     * @return 이번 실패로 잠겼으면 true
     */
    public boolean loginFailed(int maxFailures) {
        this.loginFailCnt++;
        if (this.loginFailCnt >= maxFailures) {
            this.locked = true;
        }
        return this.locked;
    }

    /** 본인이 비밀번호를 바꾼다. 변경 필요 표시를 지운다 */
    public void changePassword(String pwdHash, LocalDateTime now) {
        this.pwdHash = pwdHash;
        this.passwordChangeRequired = false;
        this.pwdChgDt = now;
        this.updDt = now;
        this.updEmpId = this.empId;
    }

    /** 관리자가 임시 비밀번호로 초기화한다. 잠금도 풀고, 다음 로그인 때 바꾸게 한다 */
    public void resetPassword(String pwdHash, String actorEmpId, LocalDateTime now) {
        this.pwdHash = pwdHash;
        this.passwordChangeRequired = true;
        this.pwdChgDt = now;
        unlock(actorEmpId, now);
    }

    /** 잠금을 풀고 실패 횟수를 지운다 */
    public void unlock(String actorEmpId, LocalDateTime now) {
        this.locked = false;
        this.loginFailCnt = 0;
        this.updDt = now;
        this.updEmpId = actorEmpId;
    }

    /** 이름, 부서, 역할, 사용 여부를 바꾼다 */
    public void update(String empNm, String deptNm, Set<String> roles, boolean active, String actorEmpId, LocalDateTime now) {
        this.empNm = empNm;
        this.deptNm = deptNm;
        this.roles.clear();
        this.roles.addAll(roles);
        this.active = active;
        this.updDt = now;
        this.updEmpId = actorEmpId;
    }
}
