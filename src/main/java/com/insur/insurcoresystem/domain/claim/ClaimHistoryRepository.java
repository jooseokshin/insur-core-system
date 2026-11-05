package com.insur.insurcoresystem.domain.claim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimHistoryRepository extends JpaRepository<ClaimHistory, Long> {

    /** 이 사고에서 이 거래 UUID 를 이미 처리했는지 (재전송 판별) */
    boolean existsByClmNoAndGlobalTxId(String clmNo, String globalTxId);

    /** 사고의 상태 이력 (발생 순) */
    List<ClaimHistory> findByClmNoOrderByHistId(String clmNo);
}
