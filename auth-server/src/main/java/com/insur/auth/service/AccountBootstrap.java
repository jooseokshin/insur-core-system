package com.insur.auth.service;

import com.insur.auth.common.Roles;
import com.insur.auth.domain.Employee;
import com.insur.auth.domain.EmployeeRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동 시 계정 준비.
 * <ul>
 *     <li>운영 최초 기동: 사원이 한 명도 없고 {@code app.bootstrap.admin-password} 가 있으면 admin 계정을 만든다. 첫 로그인 때 비밀번호를 바꿔야 한다.</li>
 *     <li>개발({@link DevAccounts}, local 프로파일): 업무 역할별 개발용 계정을 만든다.</li>
 * </ul>
 */
@Slf4j
@Component
public class AccountBootstrap implements ApplicationRunner {

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String adminPassword;

    public AccountBootstrap(EmployeeRepository employeeRepository, PasswordEncoder passwordEncoder, Clock clock,
                            @Value("${app.bootstrap.admin-password:}") String adminPassword) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminPassword.isBlank() || employeeRepository.count() > 0) {
            return;
        }
        employeeRepository.save(Employee.create("admin", "시스템 관리자", null, Set.of(Roles.ADMIN),
                passwordEncoder.encode(adminPassword), true, "SYSTEM", LocalDateTime.now(clock)));
        log.warn("사원이 없어 초기 관리자(admin)를 만들었습니다. 첫 로그인 때 비밀번호를 바꿔야 합니다.");
    }

    /**
     * 개발용 계정. local 프로파일에서만 만들며, 이미 있는 사번은 건드리지 않는다. 비밀번호가 공개되어 있으므로 운영에서 켜면 안 된다.
     */
    @Slf4j
    @Component
    @Profile("local")
    static class DevAccounts implements ApplicationRunner {

        /** 개발용 공통 비밀번호 */
        static final String DEV_PASSWORD = "Insur!2026";

        private final EmployeeRepository employeeRepository;
        private final PasswordEncoder passwordEncoder;
        private final Clock clock;

        DevAccounts(EmployeeRepository employeeRepository, PasswordEncoder passwordEncoder, Clock clock) {
            this.employeeRepository = employeeRepository;
            this.passwordEncoder = passwordEncoder;
            this.clock = clock;
        }

        @Override
        @Transactional
        public void run(ApplicationArguments args) {
            create("admin01", "김관리", "IT운영팀", Set.of(Roles.ADMIN));
            create("agent01", "이설계", "강남지점", Set.of(Roles.AGENT));
            create("agent02", "박설계", "부산지점", Set.of(Roles.AGENT));
            create("uw01", "최심사", "언더라이팅팀", Set.of(Roles.UNDERWRITER));
            create("op01", "정관리", "계약관리팀", Set.of(Roles.OPERATOR));
            create("clm01", "한보상", "보상팀", Set.of(Roles.CLAIM));
        }

        private void create(String empId, String empNm, String deptNm, Set<String> roles) {
            if (employeeRepository.existsById(empId)) {
                return;
            }
            employeeRepository.save(Employee.create(empId, empNm, deptNm, roles, passwordEncoder.encode(DEV_PASSWORD),
                    false, "SYSTEM", LocalDateTime.now(clock)));
            log.info("개발용 계정 생성: {} ({})", empId, roles);
        }
    }
}
