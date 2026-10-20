package com.insur.insurcoresystem.domain.premium;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PremiumFactorPolicyRepository extends JpaRepository<PremiumFactorPolicy, Long> {

    /**
     * 보험종목에서 기준일에 입력이 필수인 요율 계수 정책을 조회한다.
     *
     * @param insrClsCd 보험종목코드
     * @param on        기준일 (보험시기)
     * @return 기준일에 필수인 계수 종류의 정책 행
     */
    @Query("""
            select p from PremiumFactorPolicy p
             where p.insrClsCd = :insrClsCd and p.required = true
               and p.effFrom <= :on and (p.effTo is null or p.effTo >= :on)
            """)
    List<PremiumFactorPolicy> findRequired(@Param("insrClsCd") String insrClsCd, @Param("on") LocalDate on);

    /**
     * 한 보험종목과 계수 종류의 정책 개정 이력을 적용 시작일 순으로 조회한다. 관리 작업에서 가장 최근 행을 찾는 데 쓴다.
     *
     * @param insrClsCd  보험종목코드
     * @param factorType 계수 종류
     * @return 오래된 행부터 정렬된 정책 행 목록
     */
    List<PremiumFactorPolicy> findByInsrClsCdAndFactorTypeOrderByEffFromAsc(String insrClsCd, String factorType);
}
