package com.insur.insurcoresystem.domain.premium;

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
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PremiumRate {

    /** 요율 일련번호 */
    private Long rateId;

    /** 보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행, 05:실손의료) */
    private String insrClsCd;

    /** 담보코드 */
    private String cvrgCd;

    /** 담보명 */
    private String cvrgNm;

    /** 연 요율. 가입금액에 곱한 값이 1년 보험료다 (예: 0.005 = 0.5%) */
    private BigDecimal annualRate;

    /** 적용 시작일 (포함) */
    private LocalDate effFrom;

    /** 적용 종료일 (포함). null 이면 현재까지 유효 */
    private LocalDate effTo;

    /** 적용 종료일을 정한다. (요율을 개정할 때 기존 행을 닫는다) */
    public void closeAt(LocalDate effTo) {
        this.effTo = effTo;
    }
}
