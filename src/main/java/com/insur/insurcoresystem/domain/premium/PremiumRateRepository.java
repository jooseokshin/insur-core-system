package com.insur.insurcoresystem.domain.premium;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PremiumRateRepository extends JpaRepository<PremiumRate, Long> {

    /**
     * 보험종목에서 주어진 담보코드들의 요율 중 기준일에 적용되는 것을 한 번에 조회한다.
     * 요율표에 없거나 기준일에 적용 기간이 아닌 담보코드는 결과에 포함되지 않는다.
     * 적용 기간이 겹치는 행이 있으면 적용 시작일이 늦은 행이 먼저 나온다.
     *
     * @param insrClsCd 보험종목코드
     * @param cvrgCds   담보코드 목록
     * @param on        요율 적용 기준일 (보험시기)
     * @return 기준일에 적용되는 요율
     */
    @Query("""
            select r from PremiumRate r
             where r.insrClsCd = :insrClsCd and r.cvrgCd in :cvrgCds
               and r.effFrom <= :on and (r.effTo is null or r.effTo >= :on)
             order by r.effFrom desc
            """)
    List<PremiumRate> findEffective(@Param("insrClsCd") String insrClsCd,
                                    @Param("cvrgCds") Collection<String> cvrgCds,
                                    @Param("on") LocalDate on);

    /**
     * 한 담보의 요율 개정 이력 전체를 적용 시작일 순으로 조회한다. 관리 작업에서 가장 최근 행을 찾는 데 쓴다.
     *
     * @param insrClsCd 보험종목코드
     * @param cvrgCd    담보코드
     * @return 오래된 행부터 정렬된 요율 행 목록
     */
    List<PremiumRate> findByInsrClsCdAndCvrgCdOrderByEffFromAsc(String insrClsCd, String cvrgCd);
}
