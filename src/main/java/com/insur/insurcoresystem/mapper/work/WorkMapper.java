package com.insur.insurcoresystem.mapper.work;

import com.insur.insurcoresystem.common.paging.PageQuery;
import com.insur.insurcoresystem.service.work.dto.DashboardResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import org.apache.ibatis.annotations.Param;

/**
 * 현업 화면 집계(업무 현황, 할 일함, 만기·갱신 대상) 매퍼. 읽기 전용 집계 SQL 만 있다.
 */
public interface WorkMapper {

    /** 계약 상태별 건수와 총보험료 */
    List<DashboardResponse.StatusCount> countByStatus(@Param("c") Condition condition);

    /** 오늘 접수·승인·청약취소·해지·납입 */
    DashboardResponse.Today todayStats(@Param("c") Condition condition);

    /** 보상 진행 현황 (고액 기준 threshold) */
    DashboardResponse.Claims claimCounts(@Param("c") Condition condition);

    /** 할 일 유형(todoType)에 해당하는 계약 수 */
    long countTodo(@Param("c") Condition condition);

    /** 할 일 목록 (접수 오래된 순) */
    List<TodoRow> findTodo(@Param("c") Condition condition, @Param("page") PageQuery page);

    /** 만기·갱신 대상 수 (dueTo 까지 만기, 유효한 갱신 계약이 없는 유효 계약) */
    long countRenewalTargets(@Param("c") Condition condition);

    /** 만기·갱신 대상 (만기 이른 순) */
    List<RenewalRow> findRenewalTargets(@Param("c") Condition condition, @Param("page") PageQuery page);

    /**
     * 집계 조건.
     *
     * @param owner       접수 사번 (본인 계약만 보는 호출자). null 이면 전체
     * @param staleBefore 이 일시 전에 접수된 청약이 오래된 청약 (오늘 - (7 - 1)일 00:00)
     * @param todoType    PENDING_APPROVAL, UNPAID, STALE_APPLICATION
     * @param dueTo       만기 조회 상한일 (포함)
     * @param insrClsCd   만기 대상 보험종목 (null 이면 전체)
     * @param threshold   고액 보상 기준액
     */
    @Builder(toBuilder = true)
    record Condition(String owner, LocalDate today, LocalDateTime dayStart, LocalDateTime dayEnd, LocalDateTime staleBefore,
                     String todoType, LocalDate dueTo, String insrClsCd, BigDecimal threshold) {
    }

    record TodoRow(String contractNo, String applNo, String insrClsCd, String policyholderId, LocalDate startDate,
                   BigDecimal totalPremium, BigDecimal paid, LocalDateTime sysRegDt, String regEmpId) {
    }

    record RenewalRow(String contractNo, String insrClsCd, String policyholderId, String insuredId, LocalDate startDate,
                      LocalDate endDate, BigDecimal totalPremium, String regEmpId) {
    }
}
