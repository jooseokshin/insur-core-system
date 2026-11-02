package com.insur.auth.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

/**
 * 사원 계정 Repository. 목록 검색 조건은 Specification 으로 조합한다.
 */
public interface EmployeeRepository extends JpaRepository<Employee, String>, JpaSpecificationExecutor<Employee> {

    /**
     * 사번으로 사원을 조회하면서 행을 잠근다(SELECT ... FOR UPDATE). 같은 사번으로 로그인 실패가 동시에 들어와도
     * 실패 횟수가 한 번씩 정확히 쌓이도록 로그인과 관리 변경은 이 메서드로 읽는다. 트랜잭션 안에서만 호출한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Employee> findForUpdateByEmpId(String empId);
}
