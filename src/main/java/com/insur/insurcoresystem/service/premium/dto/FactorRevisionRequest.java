package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 요율 계수 개정/신규 요청 바디. 같은 칸(보험종목, 계수 종류, 키 또는 구간)의 계수가 이미 있으면 가장 최근 행을 닫고
 * 새 행을 추가하는 개정이고, 없으면 새 칸을 만든다.
 * 계수 종류별 형태: DRIVER_AGE(자동차)는 구간(rangeFrom, rangeTo), DISCOUNT_GRADE(자동차)와 STRUCTURE_GRADE(화재)는 키(factorKey).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorRevisionRequest {

    /** 보험종목코드 (01:자동차, 02:화재) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재)")
    private String insrClsCd;
    /** 계수 종류 (DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE) */
    @Schema(description = "계수 종류 (DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE)")
    private String factorType;
    /** 키형 계수의 코드 (최대 10자). 범위형이면 생략 */
    @Schema(description = "키형 계수의 코드 (최대 10자). 범위형이면 생략")
    private String factorKey;
    /** 범위형 계수의 시작값 (0 이상). 키형이면 생략 */
    @Schema(description = "범위형 계수의 시작값 (0 이상). 키형이면 생략")
    private Integer rangeFrom;
    /** 범위형 계수의 종료값 (시작값 이상 150 이하). 키형이면 생략 */
    @Schema(description = "범위형 계수의 종료값 (시작값 이상 150 이하). 키형이면 생략")
    private Integer rangeTo;
    /** 계수 이름 (최대 50자). 새 칸을 만들 때만 필수이고, 개정할 때 생략하면 기존 이름을 쓴다 */
    @Schema(description = "계수 이름 (최대 50자). 새 칸을 만들 때만 필수이고, 개정할 때 생략하면 기존 이름을 쓴다")
    private String factorNm;
    /** 새 계수 값 (0보다 크고 9.9999 이하, 소수 4자리까지) */
    @Schema(description = "새 계수 값 (0보다 크고 9.9999 이하, 소수 4자리까지)")
    private BigDecimal factorValue;
    /** 새 계수의 적용 시작일. 오늘(서울 기준) 이후이고 같은 칸의 가장 최근 행의 시작일보다 늦어야 한다 */
    @Schema(description = "새 계수의 적용 시작일. 오늘(서울 기준) 이후이고 같은 칸의 가장 최근 행의 시작일보다 늦어야 한다")
    private LocalDate effFrom;
}
