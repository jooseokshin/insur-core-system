package com.insur.auth.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {

    /** 한 사번의 로그인 이력 */
    Page<LoginHistory> findByEmpId(String empId, Pageable pageable);
}
