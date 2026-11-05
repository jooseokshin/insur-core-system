package com.insur.insurcoresystem.domain.claim;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 사고 Repository
 */
public interface ClaimRepository extends JpaRepository<Claim, String> {

    /** 접수 거래 UUID 로 사고를 찾는다 (재전송 판별) */
    Optional<Claim> findByGlobalTxId(String globalTxId);

    /** 사고를 조회하면서 행을 잠근다. 같은 사고의 사정·지급이 동시에 들어와도 하나씩 처리된다. 트랜잭션 안에서만 쓴다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Claim> findForUpdateByClmNo(String clmNo);

    /** 계약에 해당 상태의 사고가 있는지 (진행 중 사고가 있는 계약의 해지를 막는 데 쓴다) */
    boolean existsByContractNoAndClmStatCdIn(String contractNo, Collection<String> clmStatCds);

    /**
     * 한 계약의 한 담보에 이미 지급한 보험금 합계. 다른 사고(지급 완료)의 지급액만 센다.
     *
     * @param excludeClmNo 계산에서 뺄 사고번호 (지금 처리 중인 사고)
     */
    @Query("""
            select coalesce(sum(i.payAmt), 0) from Claim c join c.items i
             where c.contractNo = :contractNo and c.clmStatCd = '40' and i.cvrgCd = :cvrgCd and c.clmNo <> :excludeClmNo
            """)
    BigDecimal sumPaid(@Param("contractNo") String contractNo, @Param("cvrgCd") String cvrgCd,
                       @Param("excludeClmNo") String excludeClmNo);
}
