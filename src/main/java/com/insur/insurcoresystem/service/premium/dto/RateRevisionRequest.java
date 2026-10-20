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
 * 요율 개정/신규 요청 바디. 같은 보험종목과 담보코드의 요율이 이미 있으면 가장 최근 행을 닫고 새 행을 추가하는 개정이고,
 * 없으면 새 담보 요율을 만든다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateRevisionRequest {

    /** 보험종목코드 (01:자동차, 02:화재) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재)")
    private String insrClsCd;
    /** 담보코드 (최대 10자) */
    @Schema(description = "담보코드 (최대 10자)")
    private String cvrgCd;
    /** 담보명 (최대 50자). 새 담보를 만들 때만 필수이고, 개정할 때 생략하면 기존 담보명을 쓴다 */
    @Schema(description = "담보명 (최대 50자). 새 담보를 만들 때만 필수이고, 개정할 때 생략하면 기존 담보명을 쓴다")
    private String cvrgNm;
    /** 새 연 요율 (0보다 크고 1 이하, 소수 6자리까지) */
    @Schema(description = "새 연 요율 (0보다 크고 1 이하, 소수 6자리까지)")
    private BigDecimal annualRate;
    /** 새 요율의 적용 시작일. 오늘(서울 기준) 이후이고 가장 최근 행의 시작일보다 늦어야 한다 */
    @Schema(description = "새 요율의 적용 시작일. 오늘(서울 기준) 이후이고 가장 최근 행의 시작일보다 늦어야 한다")
    private LocalDate effFrom;
}
