package com.insur.insurcoresystem.mapper.premium;

import com.insur.insurcoresystem.domain.premium.PremiumRate;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 담보별 기본 요율(TB_PRM_RATE, 적용 기간 관리) 매퍼.
 */
public interface PremiumRateMapper {

    /**
     * 보험종목에서 주어진 담보코드들의 요율 중 기준일에 적용되는 것. 요율표에 없거나 기간 밖인 담보는 빠진다.
     * 적용 기간이 겹치는 행이 있으면 적용 시작일이 늦은 행이 먼저 나온다.
     */
    List<PremiumRate> findEffective(@Param("insrClsCd") String insrClsCd, @Param("cvrgCds") Collection<String> cvrgCds,
                                    @Param("on") LocalDate on);

    /** 한 담보의 요율 개정 이력 (적용 시작일 순) */
    List<PremiumRate> findByInsrClsCdAndCvrgCdOrderByEffFromAsc(@Param("insrClsCd") String insrClsCd,
                                                                @Param("cvrgCd") String cvrgCd);

    /** 전체 요율 행 (종목, 담보, 적용 시작일 순) */
    List<PremiumRate> findAll();

    /** 요율 행을 넣고 생성된 ID 를 채운다 */
    void insert(PremiumRate rate);

    /** 적용 종료일을 저장한다 (개정 때 이전 행을 닫는다) */
    int updateEffTo(PremiumRate rate);
}
