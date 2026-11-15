package com.insur.insurcoresystem.mapper.premium;

import com.insur.insurcoresystem.domain.premium.PremiumFactor;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 요율 계수(TB_PRM_FACTOR, 적용 기간 관리) 매퍼.
 */
public interface PremiumFactorMapper {

    /**
     * 보험종목에서 기준일에 적용되는 모든 계수. 계수표는 작아서 한 번에 읽어 서비스에서 입력값에 맞는 것을 고른다.
     * 적용 기간이 겹치는 행이 있으면 적용 시작일이 늦은 행이 먼저 나온다.
     */
    List<PremiumFactor> findEffective(@Param("insrClsCd") String insrClsCd, @Param("on") LocalDate on);

    /** 한 보험종목·계수 종류의 개정 이력 (적용 시작일 순) */
    List<PremiumFactor> findByInsrClsCdAndFactorTypeOrderByEffFromAsc(@Param("insrClsCd") String insrClsCd,
                                                                      @Param("factorType") String factorType);

    /** 전체 계수 행 */
    List<PremiumFactor> findAll();

    void insert(PremiumFactor factor);

    int updateEffTo(PremiumFactor factor);
}
