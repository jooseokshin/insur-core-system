package com.insur.insurcoresystem.mapper.premium;

import com.insur.insurcoresystem.domain.premium.PremiumFactorPolicy;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 요율 계수 필수 정책(TB_PRM_FACTOR_POLICY, 적용 기간 관리) 매퍼.
 */
public interface PremiumFactorPolicyMapper {

    /** 보험종목에서 기준일(보험시기)에 입력이 필수인 계수 종류의 정책 행 */
    List<PremiumFactorPolicy> findRequired(@Param("insrClsCd") String insrClsCd, @Param("on") LocalDate on);

    /** 한 보험종목·계수 종류의 정책 개정 이력 (적용 시작일 순) */
    List<PremiumFactorPolicy> findByInsrClsCdAndFactorTypeOrderByEffFromAsc(@Param("insrClsCd") String insrClsCd,
                                                                            @Param("factorType") String factorType);

    List<PremiumFactorPolicy> findAll();

    void insert(PremiumFactorPolicy policy);

    int updateEffTo(PremiumFactorPolicy policy);
}
