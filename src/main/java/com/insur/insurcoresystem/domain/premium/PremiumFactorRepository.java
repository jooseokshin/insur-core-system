package com.insur.insurcoresystem.domain.premium;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PremiumFactorRepository extends JpaRepository<PremiumFactor, Long> {

    /**
     * 보험종목에서 기준일에 적용되는 모든 요율 계수를 조회한다. 계수표는 작아서 한 번에 읽어 서비스에서 입력값에 맞는 것을 고른다.
     * 적용 기간이 겹치는 행이 있으면 적용 시작일이 늦은 행이 먼저 나온다.
     *
     * @param insrClsCd 보험종목코드
     * @param on        계수 적용 기준일 (보험시기)
     * @return 기준일에 적용되는 계수 목록
     */
    @Query("""
            select f from PremiumFactor f
             where f.insrClsCd = :insrClsCd
               and f.effFrom <= :on and (f.effTo is null or f.effTo >= :on)
             order by f.effFrom desc
            """)
    List<PremiumFactor> findEffective(@Param("insrClsCd") String insrClsCd, @Param("on") LocalDate on);

    /**
     * 한 보험종목과 계수 종류의 모든 개정 이력을 적용 시작일 순으로 조회한다. 관리 작업에서 같은 칸(키 또는 구간)의 행을 찾는 데 쓴다.
     *
     * @param insrClsCd  보험종목코드
     * @param factorType 계수 종류
     * @return 오래된 행부터 정렬된 계수 행 목록
     */
    List<PremiumFactor> findByInsrClsCdAndFactorTypeOrderByEffFromAsc(String insrClsCd, String factorType);
}
