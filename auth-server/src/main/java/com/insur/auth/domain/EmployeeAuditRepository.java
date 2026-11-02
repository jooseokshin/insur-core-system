package com.insur.auth.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeAuditRepository extends JpaRepository<EmployeeAudit, Long> {

    /** 한 사원의 감사 이력 (최근 순) */
    List<EmployeeAudit> findByEmpIdOrderByAuditIdDesc(String empId);
}
