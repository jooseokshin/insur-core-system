package com.insur.auth.mapper;

import com.insur.auth.domain.EmployeeAudit;
import java.util.List;

/**
 * 사원 관리 감사 이력(TB_EMP_AUDIT) 매퍼.
 */
public interface EmployeeAuditMapper {

    /** 사원의 감사 이력 (최근 순) */
    List<EmployeeAudit> findByEmpIdOrderByAuditIdDesc(String empId);

    void insert(EmployeeAudit audit);
}
