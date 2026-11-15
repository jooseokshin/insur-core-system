package com.insur.insurcoresystem.mapper.claim;

import com.insur.insurcoresystem.domain.claim.ClaimHistory;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 사고 처리 이력(TB_CLM_HIST, 추가만 한다) 매퍼.
 */
public interface ClaimHistoryMapper {

    /** 같은 사고에 같은 거래 UUID 로 처리한 이력이 있는지 (재전송 판별) */
    boolean existsByClmNoAndGlobalTxId(@Param("clmNo") String clmNo, @Param("globalTxId") String globalTxId);

    /** 사고의 이력 (발생 순) */
    List<ClaimHistory> findByClmNoOrderByHistId(String clmNo);

    void insert(ClaimHistory history);
}
