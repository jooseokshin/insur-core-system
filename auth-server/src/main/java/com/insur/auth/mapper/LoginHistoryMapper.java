package com.insur.auth.mapper;

import com.insur.auth.common.paging.PageQuery;
import com.insur.auth.domain.LoginHistory;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 로그인 이력(TB_LOGIN_HIST) 매퍼.
 */
public interface LoginHistoryMapper {

    /** 로그인 이력 한 페이지 (최근 순). empId 가 null 이면 전체 */
    List<LoginHistory> findPage(@Param("empId") String empId, @Param("page") PageQuery page);

    long countByEmpId(@Param("empId") String empId);

    void insert(LoginHistory history);
}
