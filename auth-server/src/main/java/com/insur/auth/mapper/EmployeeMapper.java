package com.insur.auth.mapper;

import com.insur.auth.common.paging.PageQuery;
import com.insur.auth.domain.Employee;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import org.apache.ibatis.annotations.Param;

/**
 * 사원(TB_EMP)과 사원 역할(TB_EMP_ROLE) 매퍼. 사원을 읽으면 역할도 함께 채운다. SQL 은 {@code mapper/EmployeeMapper.xml}.
 */
public interface EmployeeMapper {

    Optional<Employee> findById(String empId);

    /**
     * 사번으로 사원을 조회하면서 행을 잠근다(SELECT ... FOR UPDATE). 같은 사번으로 로그인 실패가 동시에 들어와도
     * 실패 횟수가 한 번씩 정확히 쌓이도록 로그인과 관리 변경은 이 메서드로 읽는다. 트랜잭션 안에서만 호출한다.
     */
    Optional<Employee> findForUpdateByEmpId(String empId);

    boolean existsById(String empId);

    long count();

    /** 조건에 맞는 사원 한 페이지 (사번 순) */
    List<Employee> search(@Param("c") SearchCondition condition, @Param("page") PageQuery page);

    long countSearch(@Param("c") SearchCondition condition);

    /** 사원과 역할을 함께 넣는다 */
    default void insert(Employee employee) {
        insertRow(employee);
        employee.getRoles().forEach(role -> insertRole(employee.getEmpId(), role));
    }

    /** 사원 행과 역할을 함께 저장한다 (역할은 지우고 다시 넣는다). 관리자 변경에서 쓴다 */
    default void update(Employee employee) {
        updateRow(employee);
        deleteRoles(employee.getEmpId());
        employee.getRoles().forEach(role -> insertRole(employee.getEmpId(), role));
    }

    void insertRow(Employee employee);

    /** 역할을 뺀 사원 행 저장 (로그인 실패·성공, 비밀번호 변경, 잠금 해제) */
    int updateRow(Employee employee);

    void insertRole(@Param("empId") String empId, @Param("roleCd") String roleCd);

    int deleteRoles(String empId);

    /**
     * 사원 검색 조건. null 인 항목은 조건에서 빠진다.
     *
     * @param keyword    사번 앞부분 또는 이름 일부 (LIKE 이스케이프 문자 {@code \}, 와일드카드는 이스케이프한 값)
     * @param lockedOnly true 면 잠긴 계정만
     */
    @Builder
    record SearchCondition(String keyword, String roleCd, Boolean active, Boolean lockedOnly) {
    }
}
