package com.insur.insurcoresystem.mapper.premium;

import com.insur.insurcoresystem.common.paging.PageQuery;
import com.insur.insurcoresystem.domain.premium.PremiumAudit;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import org.apache.ibatis.annotations.Param;

/**
 * 요율 관리 감사 이력(TB_PRM_AUDIT) 매퍼.
 */
public interface PremiumAuditMapper {

    /** 같은 대상 종류에서 같은 거래 UUID 로 처음 처리한 관리 작업 (재전송 판별) */
    Optional<PremiumAudit> findByTargetTypeAndGlobalTxId(@Param("targetType") String targetType,
                                                         @Param("globalTxId") String globalTxId);

    /** 조건에 맞는 감사 이력 한 페이지 (처리일시, 감사 ID 내림차순) */
    List<PremiumAudit> search(@Param("c") SearchCondition condition, @Param("page") PageQuery page);

    long countSearch(@Param("c") SearchCondition condition);

    void insert(PremiumAudit audit);

    /**
     * 감사 이력 검색 조건. null 인 항목은 조건에서 빠진다.
     *
     * @param targetKeyLike 대상 키 앞부분 LIKE 패턴 (이스케이프 문자 {@code \})
     * @param chgFrom       처리일시 하한 (포함)
     * @param chgTo         처리일시 상한 (미포함)
     */
    @Builder
    record SearchCondition(String targetType, String targetKeyLike, String empId, LocalDateTime chgFrom, LocalDateTime chgTo) {
    }
}
