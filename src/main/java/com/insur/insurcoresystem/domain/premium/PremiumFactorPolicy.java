package com.insur.insurcoresystem.domain.premium;

import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 필수 요율 계수 정책. 보험종목과 계수 종류마다 적용 기간별로 입력이 필수인지 정한다.
 * 개정하면 기존 행의 종료일을 닫고 새 행을 추가한다. 행이 없는 계수 종류는 필수가 아니다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PremiumFactorPolicy {

    /** 정책 일련번호 */
    private Long policyId;

    /** 보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행, 05:실손의료) */
    private String insrClsCd;

    /** 계수 종류 (DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE) */
    private String factorType;

    /** 이 기간에 입력이 필수인지 */
    private boolean required;

    /** 적용 시작일 (포함) */
    private LocalDate effFrom;

    /** 적용 종료일 (포함). null 이면 현재까지 유효 */
    private LocalDate effTo;

    /** 적용 종료일을 정한다. (정책을 개정할 때 기존 행을 닫는다) */
    public void closeAt(LocalDate effTo) {
        this.effTo = effTo;
    }
}
