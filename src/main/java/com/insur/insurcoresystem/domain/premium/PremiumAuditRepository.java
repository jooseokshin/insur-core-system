package com.insur.insurcoresystem.domain.premium;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 요율 관리 감사 이력 Repository. 조회의 선택 조건은 Specification 으로 조합한다(JpaSpecificationExecutor).
 */
public interface PremiumAuditRepository extends JpaRepository<PremiumAudit, Long>, JpaSpecificationExecutor<PremiumAudit> {

    /**
     * 같은 대상 종류에서 같은 거래 UUID 로 이미 처리한 관리 작업을 찾는다. 응답을 못 받은 클라이언트의 재전송을 가려내는 데 쓴다.
     *
     * @param targetType 대상 종류 (RATE, FACTOR)
     * @param globalTxId 거래 UUID
     * @return 처음 처리한 감사 이력. 없으면 빈 Optional
     */
    Optional<PremiumAudit> findByTargetTypeAndGlobalTxId(String targetType, String globalTxId);
}
