package com.insur.insurcoresystem.domain.premium;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 담보별 보험료 요율. 보험종목과 담보코드마다 적용 기간별로 연 요율 하나를 가진다. (개정하면 기존 행의 종료일을 닫고 새 행을 추가)
 */
@Entity
@Table(name = "TB_PRM_RATE")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PremiumRate {

    /** 요율 일련번호 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RATE_ID")
    private Long rateId;

    /** 보험종목코드 (01:자동차, 02:화재) */
    @Column(name = "INSR_CLS_CD", length = 2, nullable = false)
    private String insrClsCd;

    /** 담보코드 */
    @Column(name = "CVRG_CD", length = 10, nullable = false)
    private String cvrgCd;

    /** 담보명 */
    @Column(name = "CVRG_NM", length = 50, nullable = false)
    private String cvrgNm;

    /** 연 요율. 가입금액에 곱한 값이 1년 보험료다 (예: 0.005 = 0.5%) */
    @Column(name = "ANNUAL_RATE", precision = 10, scale = 6, nullable = false)
    private BigDecimal annualRate;

    /** 적용 시작일 (포함) */
    @Column(name = "EFF_FROM", nullable = false)
    private LocalDate effFrom;

    /** 적용 종료일 (포함). null 이면 현재까지 유효 */
    @Column(name = "EFF_TO")
    private LocalDate effTo;

    /** 적용 종료일을 정한다. (요율을 개정할 때 기존 행을 닫는다) */
    public void closeAt(LocalDate effTo) {
        this.effTo = effTo;
    }
}
