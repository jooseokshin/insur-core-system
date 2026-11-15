package com.insur.insurcoresystem.domain.premium;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보험료 요율 계수. 보험종목과 계수 종류마다 입력값(구간 또는 코드)에 따라 계수가 정해지며, 담보 기본 요율에 곱해진다.
 * 범위형 계수는 {@code rangeFrom ≤ 입력값 ≤ rangeTo}(양 끝 포함)일 때, 키형 계수는 {@code factorKey} 가 입력 코드와 같을 때 적용된다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PremiumFactor {

    /** 계수 일련번호 */
    private Long factorId;

    /** 보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행, 05:실손의료) */
    private String insrClsCd;

    /** 계수 종류 (DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE) */
    private String factorType;

    /** 키형 계수의 코드. 범위형이면 null */
    private String factorKey;

    /** 범위형 계수의 시작값(포함). 키형이면 null */
    private Integer rangeFrom;

    /** 범위형 계수의 종료값(포함). 키형이면 null */
    private Integer rangeTo;

    /** 계수 이름 (예: 만 30~49세) */
    private String factorNm;

    /** 계수 값 (1.0000 이 기준, 1보다 크면 할증, 작으면 할인) */
    private BigDecimal factorValue;

    /** 적용 시작일 (포함) */
    private LocalDate effFrom;

    /** 적용 종료일 (포함). null 이면 현재까지 유효 */
    private LocalDate effTo;

    /** 적용 종료일을 정한다. (계수를 개정할 때 기존 행을 닫는다) */
    public void closeAt(LocalDate effTo) {
        this.effTo = effTo;
    }

    /** 범위형 계수인지 여부 */
    public boolean isRange() {
        return rangeFrom != null;
    }

    /** 범위형 계수가 주어진 값을 포함하는지 */
    public boolean contains(int value) {
        return isRange() && rangeFrom <= value && value <= rangeTo;
    }
}
