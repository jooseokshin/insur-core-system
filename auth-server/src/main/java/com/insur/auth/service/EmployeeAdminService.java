package com.insur.auth.service;

import com.insur.auth.common.BusinessException;
import com.insur.auth.common.Roles;
import com.insur.auth.domain.Employee;
import com.insur.auth.domain.EmployeeAudit;
import com.insur.auth.domain.EmployeeAuditRepository;
import com.insur.auth.domain.EmployeeRepository;
import com.insur.auth.domain.LoginHistory;
import com.insur.auth.domain.LoginHistoryRepository;
import com.insur.auth.service.dto.EmployeeDto;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 사원·권한 관리 (ADMIN 전용). 모든 변경은 사원 관리 감사 이력에 남긴다.
 * 관리자가 자기 자신의 ADMIN 역할을 빼거나 사용 중지하면 관리자가 아무도 남지 않을 수 있어서 막는다.
 * 역할·사용 여부 변경은 이미 발급된 토큰에는 반영되지 않고 다음 로그인부터 적용된다(토큰 유효시간이 짧은 이유).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeAdminService {

    private static final Pattern EMP_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{3,20}$");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final EmployeeRepository employeeRepository;
    private final EmployeeAuditRepository auditRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /** 사원 목록을 검색한다. 사번순으로 돌려준다. */
    @Transactional(readOnly = true)
    public EmployeeDto.ListResponse list(EmployeeDto.ListRequest request) {
        PageRequest pageable = page(request.getPage(), request.getSize(), Sort.by("empId"));
        if (StringUtils.hasText(request.getRoleCd())) {
            requireRole(request.getRoleCd());
        }
        Specification<Employee> spec = (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (StringUtils.hasText(request.getKeyword())) {
                String keyword = request.getKeyword().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                conditions.add(cb.or(
                        cb.like(root.get("empId"), keyword + "%", '\\'),
                        cb.like(root.get("empNm"), "%" + keyword + "%", '\\')));
            }
            if (StringUtils.hasText(request.getRoleCd())) {
                query.distinct(true);
                conditions.add(cb.isMember(request.getRoleCd(), root.get("roles")));
            }
            if (request.getActive() != null) {
                conditions.add(cb.equal(root.get("active"), request.getActive()));
            }
            if (Boolean.TRUE.equals(request.getLockedOnly())) {
                conditions.add(cb.isTrue(root.get("locked")));
            }
            return cb.and(conditions.toArray(new Predicate[0]));
        };
        Page<Employee> result = employeeRepository.findAll(spec, pageable);
        return EmployeeDto.ListResponse.builder()
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .items(result.getContent().stream().map(EmployeeAdminService::toItem).toList())
                .build();
    }

    /**
     * 사원 상세와 관리 감사 이력을 조회한다.
     *
     * @throws BusinessException 사원이 없는 경우(E-EMP-003, 404)
     */
    @Transactional(readOnly = true)
    public EmployeeDto.DetailResponse detail(String empId) {
        Employee employee = employeeRepository.findById(nonBlank(empId)).orElseThrow(EmployeeAdminService::notFound);
        return EmployeeDto.DetailResponse.builder()
                .employee(toItem(employee))
                .audits(auditRepository.findByEmpIdOrderByAuditIdDesc(employee.getEmpId()).stream()
                        .map(a -> EmployeeDto.Audit.builder().action(a.getAction()).detail(a.getDetail())
                                .actEmpId(a.getActEmpId()).actDt(a.getActDt()).build())
                        .toList())
                .build();
    }

    /**
     * 사원을 등록하고 임시 비밀번호를 발급한다. 임시 비밀번호는 이 응답에서만 볼 수 있고 첫 로그인 때 바꿔야 한다.
     *
     * @throws BusinessException 입력 오류(E-EMP-001), 이미 있는 사번(E-EMP-002, 409), 없는 역할(E-EMP-005)
     */
    @Transactional
    public EmployeeDto.TemporaryPasswordResponse create(EmployeeDto.CreateRequest request, String actorEmpId, String globalTxId) {
        String empId = request.getEmpId() == null ? "" : request.getEmpId().trim();
        if (!EMP_ID_PATTERN.matcher(empId).matches()) {
            throw new BusinessException("E-EMP-001", "사번은 영문, 숫자, _.- 로 3~20자여야 합니다.");
        }
        String empNm = requireName(request.getEmpNm());
        Set<String> roles = requireRoles(request.getRoles());
        if (employeeRepository.existsById(empId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-EMP-002", "이미 등록된 사번입니다: " + empId);
        }
        String temporary = PasswordPolicy.temporary();
        LocalDateTime now = LocalDateTime.now(clock);
        Employee employee = employeeRepository.save(Employee.create(empId, empNm, trimToNull(request.getDeptNm()), roles,
                passwordEncoder.encode(temporary), true, actorEmpId, now));
        audit(empId, "CREATE", "이름=" + empNm + ", 부서=" + employee.getDeptNm() + ", 역할=" + new TreeSet<>(roles),
                actorEmpId, globalTxId, now);
        log.info("사원 등록: 사번={}, 역할={}, 처리={}", empId, roles, actorEmpId);
        return EmployeeDto.TemporaryPasswordResponse.builder().employee(toItem(employee)).temporaryPassword(temporary).build();
    }

    /**
     * 이름, 부서, 역할, 사용 여부를 바꾼다. 바뀐 항목만 감사 이력에 남긴다.
     *
     * @throws BusinessException 입력 오류(E-EMP-001), 사원 없음(E-EMP-003, 404), 본인의 ADMIN 해제·사용 중지(E-EMP-004), 없는 역할(E-EMP-005),
     *                           바뀐 항목 없음(E-EMP-006)
     */
    @Transactional
    public EmployeeDto.Item update(EmployeeDto.UpdateRequest request, String actorEmpId, String globalTxId) {
        Employee employee = employeeRepository.findForUpdateByEmpId(nonBlank(request.getEmpId())).orElseThrow(EmployeeAdminService::notFound);
        String empNm = requireName(request.getEmpNm());
        String deptNm = trimToNull(request.getDeptNm());
        Set<String> roles = requireRoles(request.getRoles());
        boolean active = request.getActive() == null ? employee.isActive() : request.getActive();
        if (employee.getEmpId().equals(actorEmpId) && (!roles.contains(Roles.ADMIN) || !active)) {
            throw new BusinessException("E-EMP-004", "본인의 관리자 역할을 빼거나 본인 계정을 사용 중지할 수 없습니다.");
        }

        List<String> changes = new ArrayList<>();
        if (!empNm.equals(employee.getEmpNm())) {
            changes.add("이름 " + employee.getEmpNm() + "→" + empNm);
        }
        if (!Objects.equals(deptNm, employee.getDeptNm())) {
            changes.add("부서 " + employee.getDeptNm() + "→" + deptNm);
        }
        if (!roles.equals(employee.getRoles())) {
            changes.add("역할 " + employee.sortedRoles() + "→" + new TreeSet<>(roles));
        }
        if (active != employee.isActive()) {
            changes.add("사용 " + (employee.isActive() ? "Y" : "N") + "→" + (active ? "Y" : "N"));
        }
        if (changes.isEmpty()) {
            throw new BusinessException("E-EMP-006", "바뀐 항목이 없습니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        employee.update(empNm, deptNm, roles, active, actorEmpId, now);
        audit(employee.getEmpId(), "UPDATE", String.join(", ", changes), actorEmpId, globalTxId, now);
        log.info("사원 정보 변경: 사번={}, {}, 처리={}", employee.getEmpId(), changes, actorEmpId);
        return toItem(employee);
    }

    /**
     * 비밀번호를 임시 비밀번호로 초기화하고 잠금을 푼다. 다음 로그인 때 비밀번호를 바꿔야 한다.
     *
     * @throws BusinessException 사원 없음(E-EMP-003, 404)
     */
    @Transactional
    public EmployeeDto.TemporaryPasswordResponse resetPassword(String empId, String actorEmpId, String globalTxId) {
        Employee employee = employeeRepository.findForUpdateByEmpId(nonBlank(empId)).orElseThrow(EmployeeAdminService::notFound);
        String temporary = PasswordPolicy.temporary();
        LocalDateTime now = LocalDateTime.now(clock);
        employee.resetPassword(passwordEncoder.encode(temporary), actorEmpId, now);
        audit(employee.getEmpId(), "RESET_PASSWORD", "임시 비밀번호 발급, 잠금 해제", actorEmpId, globalTxId, now);
        log.info("비밀번호 초기화: 사번={}, 처리={}", employee.getEmpId(), actorEmpId);
        return EmployeeDto.TemporaryPasswordResponse.builder().employee(toItem(employee)).temporaryPassword(temporary).build();
    }

    /**
     * 잠긴 계정을 푼다. 비밀번호는 그대로다.
     *
     * @throws BusinessException 사원 없음(E-EMP-003, 404), 잠기지 않은 계정(E-EMP-007)
     */
    @Transactional
    public EmployeeDto.Item unlock(String empId, String actorEmpId, String globalTxId) {
        Employee employee = employeeRepository.findForUpdateByEmpId(nonBlank(empId)).orElseThrow(EmployeeAdminService::notFound);
        if (!employee.isLocked()) {
            throw new BusinessException("E-EMP-007", "잠기지 않은 계정입니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        employee.unlock(actorEmpId, now);
        audit(employee.getEmpId(), "UNLOCK", "잠금 해제", actorEmpId, globalTxId, now);
        log.info("잠금 해제: 사번={}, 처리={}", employee.getEmpId(), actorEmpId);
        return toItem(employee);
    }

    /** 로그인 이력을 최근 순으로 조회한다. 사번을 생략하면 전체 이력이다. */
    @Transactional(readOnly = true)
    public EmployeeDto.LoginHistoryResponse loginHistory(EmployeeDto.LoginHistoryRequest request) {
        PageRequest pageable = page(request.getPage(), request.getSize(), Sort.by(Sort.Direction.DESC, "histId"));
        Page<LoginHistory> result = StringUtils.hasText(request.getEmpId())
                ? loginHistoryRepository.findByEmpId(request.getEmpId().trim(), pageable)
                : loginHistoryRepository.findAll(pageable);
        return EmployeeDto.LoginHistoryResponse.builder()
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .items(result.getContent().stream()
                        .map(h -> EmployeeDto.LoginHistoryItem.builder().empId(h.getEmpId()).resultCd(h.getResultCd())
                                .clientIp(h.getClientIp()).loginDt(h.getLoginDt()).build())
                        .toList())
                .build();
    }

    static EmployeeDto.Item toItem(Employee e) {
        return EmployeeDto.Item.builder()
                .empId(e.getEmpId()).empNm(e.getEmpNm()).deptNm(e.getDeptNm()).roles(e.sortedRoles())
                .active(e.isActive()).locked(e.isLocked()).loginFailCnt(e.getLoginFailCnt())
                .passwordChangeRequired(e.isPasswordChangeRequired())
                .lastLoginDt(e.getLastLoginDt()).pwdChgDt(e.getPwdChgDt())
                .regDt(e.getRegDt()).regEmpId(e.getRegEmpId()).updDt(e.getUpdDt()).updEmpId(e.getUpdEmpId())
                .build();
    }

    private void audit(String empId, String action, String detail, String actorEmpId, String globalTxId, LocalDateTime now) {
        auditRepository.save(EmployeeAudit.builder().empId(empId).action(action)
                .detail(detail.length() > 500 ? detail.substring(0, 500) : detail)
                .actEmpId(actorEmpId).globalTxId(globalTxId).actDt(now).build());
    }

    private static PageRequest page(Integer page, Integer size, Sort sort) {
        int pageNo = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageNo < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException("E-EMP-001", "페이지 번호는 0 이상, 페이지 크기는 1~" + MAX_PAGE_SIZE + " 사이여야 합니다.");
        }
        return PageRequest.of(pageNo, pageSize, sort);
    }

    private static String nonBlank(String empId) {
        if (!StringUtils.hasText(empId)) {
            throw new BusinessException("E-EMP-001", "사번을 입력해 주세요.");
        }
        return empId.trim();
    }

    private static String requireName(String empNm) {
        if (!StringUtils.hasText(empNm) || empNm.trim().length() > 50) {
            throw new BusinessException("E-EMP-001", "이름은 1~50자로 입력해 주세요.");
        }
        return empNm.trim();
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.trim().length() > 50) {
            throw new BusinessException("E-EMP-001", "부서명은 50자 이하여야 합니다.");
        }
        return value.trim();
    }

    private static Set<String> requireRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new BusinessException("E-EMP-001", "역할을 하나 이상 선택해 주세요.");
        }
        roles.forEach(EmployeeAdminService::requireRole);
        return Set.copyOf(roles);
    }

    private static void requireRole(String role) {
        if (role == null || !Roles.ALL.contains(role)) {   // Set.of().contains(null) 은 예외라 먼저 거른다
            throw new BusinessException("E-EMP-005", "없는 역할입니다: " + role);
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "E-EMP-003", "해당 사원이 없습니다.");
    }
}
