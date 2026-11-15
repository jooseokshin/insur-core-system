package com.insur.insurcoresystem.domain.product;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보험 상품. 판매 기간과 담보 구성(주계약·특약, 가입금액 한도, 자기부담금)을 가진다. 상품은 마이그레이션으로만 관리한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    /** 상품코드 */
    private String prdCd;

    /** 상품명 */
    private String prdNm;

    /** 보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행, 05:실손의료) */
    private String insrClsCd;

    /** 상품 설명 */
    private String prdDesc;

    /** 판매 시작일 (포함) */
    private LocalDate saleFrom;

    /** 판매 종료일 (포함). 없으면 판매 중 */
    private LocalDate saleTo;

    /** 보험기간 최소 일수 (포함). 없으면 제한 없음 (예: 해외여행 1일) */
    private Integer minTermDays;

    /** 보험기간 최대 일수 (포함). 없으면 제한 없음 (예: 해외여행 90일) */
    private Integer maxTermDays;

    /** 담보 구성 (표시 순서대로) */
    private List<ProductCoverage> coverages = new ArrayList<>();

    /** 그 날짜에 판매 중인지 (판매 시작일~종료일, 양 끝 포함) */
    public boolean isOnSale(LocalDate date) {
        return !date.isBefore(saleFrom) && (saleTo == null || !date.isAfter(saleTo));
    }

    /** 보험기간 일수가 상품의 허용 범위 안인지 (양 끝 포함, 정하지 않은 쪽은 제한 없음) */
    public boolean allowsTerm(long days) {
        return (minTermDays == null || days >= minTermDays) && (maxTermDays == null || days <= maxTermDays);
    }

    /** 담보 구성에서 담보 하나를 찾는다 */
    public Optional<ProductCoverage> coverage(String cvrgCd) {
        return coverages.stream().filter(c -> c.getCvrgCd().equals(cvrgCd)).findFirst();
    }
}
