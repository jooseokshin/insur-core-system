package com.insur.auth.service;

import com.insur.auth.common.BusinessException;
import com.insur.auth.domain.Employee;
import com.insur.auth.domain.EmployeeAudit;
import com.insur.auth.domain.EmployeeAuditRepository;
import com.insur.auth.domain.EmployeeRepository;
import com.insur.auth.domain.LoginHistory;
import com.insur.auth.domain.LoginHistoryRepository;
import com.insur.auth.service.dto.AuthDto;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인, 본인 정보, 비밀번호 변경.
 * <ul>
 *     <li>없는 사번과 틀린 비밀번호는 같은 오류(E-AUTH-101)로 답해 사번이 있는지 알 수 없게 한다. 없는 사번도 해시 비교를 한 번 해서 응답 시간으로도 구분되지 않게 한다.</li>
 *     <li>비밀번호를 연속 {@code app.login.max-failures}회 틀리면 잠그고(E-AUTH-102), 관리자가 잠금 해제나 비밀번호 초기화를 해야 한다.</li>
 *     <li>모든 시도는 성공·실패와 접속 IP 를 로그인 이력에 남긴다. 실패해도 이력과 실패 횟수는 저장되도록 업무 예외로 롤백하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
public class AuthService {

    private final EmployeeRepository employeeRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final EmployeeAuditRepository auditRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final Clock clock;
    private final int maxFailures;
    /** 없는 사번에도 해시 비교를 하기 위한 값 (응답 시간으로 사번 존재 여부를 알 수 없게) */
    private final String dummyHash;

    public AuthService(EmployeeRepository employeeRepository, LoginHistoryRepository loginHistoryRepository,
                       EmployeeAuditRepository auditRepository, PasswordEncoder passwordEncoder, TokenIssuer tokenIssuer,
                       Clock clock, @Value("${app.login.max-failures}") int maxFailures) {
        this.employeeRepository = employeeRepository;
        this.loginHistoryRepository = loginHistoryRepository;
        this.auditRepository = auditRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /**
     * 사번과 비밀번호로 로그인해 토큰을 발급한다. 비밀번호 변경이 필요한 사원은 역할 없는 제한 토큰을 받는다.
     *
     * @param clientIp 접속 IP (로그인 이력용)
     * @throws BusinessException 입력 누락(E-AUTH-100), 사번·비밀번호 불일치(E-AUTH-101, 401), 잠긴 계정(E-AUTH-102, 403), 사용 중지 계정(E-AUTH-103, 403)
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthDto.TokenResponse login(String empIdInput, String password, String clientIp) {
        if (empIdInput == null || empIdInput.isBlank() || password == null || password.isEmpty()) {
            throw new BusinessException("E-AUTH-100", "사번과 비밀번호를 입력해 주세요.");
        }
        String empId = empIdInput.trim();
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<Employee> found = employeeRepository.findForUpdateByEmpId(empId);

        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            record(empId, LoginHistory.BAD_CREDENTIALS, clientIp, now);
            throw badCredentials(null);
        }
        Employee employee = found.get();
        if (!employee.isActive()) {
            record(empId, LoginHistory.DISABLED, clientIp, now);
            throw new BusinessException(HttpStatus.FORBIDDEN, "E-AUTH-103", "사용이 중지된 계정입니다. 관리자에게 문의해 주세요.");
        }
        if (employee.isLocked()) {
            record(empId, LoginHistory.LOCKED, clientIp, now);
            throw locked();
        }
        if (!passwordEncoder.matches(password, employee.getPwdHash())) {
            boolean nowLocked = employee.loginFailed(maxFailures);
            record(empId, LoginHistory.BAD_CREDENTIALS, clientIp, now);
            log.warn("로그인 실패: 사번={}, 연속 실패 {}회{}", empId, employee.getLoginFailCnt(), nowLocked ? " (잠금)" : "");
            if (nowLocked) {
                throw locked();
            }
            throw badCredentials(maxFailures - employee.getLoginFailCnt());
        }

        LocalDateTime previousLogin = employee.getLastLoginDt();
        employee.loginSucceeded(now);
        record(empId, LoginHistory.SUCCESS, clientIp, now);
        log.info("로그인 성공: 사번={}, 비밀번호 변경 필요={}", empId, employee.isPasswordChangeRequired());
        return tokenResponse(employee, previousLogin);
    }

    /**
     * 본인 정보를 조회한다. 토큰을 받은 뒤 사용 중지되었으면 거절한다.
     *
     * @throws BusinessException 사원이 없거나 사용 중지된 경우(E-AUTH-103, 403)
     */
    @Transactional(readOnly = true)
    public AuthDto.Profile profile(String empId) {
        Employee employee = employeeRepository.findById(empId)
                .filter(Employee::isActive)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "E-AUTH-103", "사용할 수 없는 계정입니다."));
        return profileOf(employee, employee.getLastLoginDt());
    }

    /**
     * 본인 비밀번호를 바꾸고, 역할이 담긴 새 토큰을 발급한다. (비밀번호 변경이 필요했던 사원은 이 토큰부터 업무를 할 수 있다)
     *
     * @throws BusinessException 입력 누락(E-AUTH-100), 현재 비밀번호 불일치(E-AUTH-105), 규칙 위반(E-AUTH-104),
     *                           현재와 같은 비밀번호(E-AUTH-106), 사용할 수 없는 계정(E-AUTH-103)
     */
    @Transactional
    public AuthDto.TokenResponse changePassword(String empId, AuthDto.PasswordChangeRequest request, String globalTxId) {
        if (request.getCurrentPassword() == null || request.getNewPassword() == null) {
            throw new BusinessException("E-AUTH-100", "현재 비밀번호와 새 비밀번호를 입력해 주세요.");
        }
        Employee employee = employeeRepository.findForUpdateByEmpId(empId)
                .filter(emp -> emp.isActive() && !emp.isLocked())
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "E-AUTH-103", "사용할 수 없는 계정입니다."));
        if (!passwordEncoder.matches(request.getCurrentPassword(), employee.getPwdHash())) {
            throw new BusinessException("E-AUTH-105", "현재 비밀번호가 올바르지 않습니다.");
        }
        if (request.getCurrentPassword().equals(request.getNewPassword())) {
            throw new BusinessException("E-AUTH-106", "새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        }
        PasswordPolicy.validate(empId, request.getNewPassword());

        LocalDateTime now = LocalDateTime.now(clock);
        employee.changePassword(passwordEncoder.encode(request.getNewPassword()), now);
        auditRepository.save(EmployeeAudit.builder().empId(empId).action("CHANGE_PASSWORD").detail("본인 비밀번호 변경")
                .actEmpId(empId).globalTxId(globalTxId).actDt(now).build());
        log.info("비밀번호 변경: 사번={}", empId);
        return tokenResponse(employee, employee.getLastLoginDt());
    }

    private AuthDto.TokenResponse tokenResponse(Employee employee, LocalDateTime previousLogin) {
        TokenIssuer.IssuedToken token = tokenIssuer.issue(employee);
        return AuthDto.TokenResponse.builder()
                .accessToken(token.value())
                .tokenType("Bearer")
                .expiresIn(token.expiresInSeconds())
                .expiresAt(OffsetDateTime.ofInstant(token.expiresAt(), clock.getZone()))
                .passwordChangeRequired(employee.isPasswordChangeRequired())
                .profile(profileOf(employee, previousLogin))
                .build();
    }

    private static AuthDto.Profile profileOf(Employee employee, LocalDateTime lastLoginDt) {
        return AuthDto.Profile.builder()
                .empId(employee.getEmpId())
                .empNm(employee.getEmpNm())
                .deptNm(employee.getDeptNm())
                .roles(employee.sortedRoles())
                .lastLoginDt(lastLoginDt)
                .pwdChgDt(employee.getPwdChgDt())
                .build();
    }

    private void record(String empId, String resultCd, String clientIp, LocalDateTime now) {
        loginHistoryRepository.save(LoginHistory.builder()
                .empId(empId.length() > 64 ? empId.substring(0, 64) : empId)
                .resultCd(resultCd)
                .clientIp(clientIp != null && clientIp.length() > 45 ? clientIp.substring(0, 45) : clientIp)
                .loginDt(now)
                .build());
    }

    private static BusinessException badCredentials(Integer remaining) {
        String message = "사번 또는 비밀번호가 올바르지 않습니다.";
        if (remaining != null) {
            message += " (" + remaining + "회 더 틀리면 계정이 잠깁니다)";
        }
        return new BusinessException(HttpStatus.UNAUTHORIZED, "E-AUTH-101", message);
    }

    private BusinessException locked() {
        return new BusinessException(HttpStatus.FORBIDDEN, "E-AUTH-102",
                "비밀번호를 " + maxFailures + "회 틀려 계정이 잠겼습니다. 관리자에게 잠금 해제를 요청해 주세요.");
    }
}
