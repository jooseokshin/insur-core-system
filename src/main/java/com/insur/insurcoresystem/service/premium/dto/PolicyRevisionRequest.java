package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 필수 요율 계수 정책 개정/신규 요청 바디. 같은 보험종목과 계수 종류의 정책이 있으면 가장 최근 행을 닫고 새 행을 추가하는
 * 개정이고, 없으면 새로 만든다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyRevisionRequest {

    /** 보험종목코드 (01:자동차, 02:화재) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재)")
    private String insrClsCd;
    /** 계수 종류 (DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE). 그 보험종목에서 쓰는 종류여야 한다 */
    @Schema(description = "계수 종류 (DRIVER_AGE, DISCOUNT_GRADE, STRUCTURE_GRADE). 그 보험종목에서 쓰는 종류여야 한다")
    private String factorType;
    /** 새 정책: true 면 입력 필수, false 면 선택 */
    @Schema(description = "새 정책: true 면 입력 필수, false 면 선택")
    private Boolean required;
    /** 새 정책의 적용 시작일. 오늘(서울 기준) 이후이고 가장 최근 행의 시작일보다 늦어야 한다. 보험시기가 이 날짜 이후인 산출/청약부터 적용된다 */
    @Schema(description = "새 정책의 적용 시작일. 오늘(서울 기준) 이후이고 가장 최근 행의 시작일보다 늦어야 한다. 보험시기가 이 날짜 이후인 산출/청약부터 적용된다")
    private LocalDate effFrom;
}
