package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 마일리지 특약 현황. 아직 등록하지 않았으면 상태와 주행거리가 비어 있고 등록·정산 가능 기간만 채워진다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MileageResponse {

    /** 증권번호 */
    @Schema(description = "증권번호")
    private String contractNo;
    /** 마일리지 상태코드 (10:시작 등록, 20:정산 완료). 미가입이면 null */
    @Schema(description = "마일리지 상태코드 (10:시작 등록, 20:정산 완료). 미가입이면 null")
    private String milStatCd;
    /** 시작 주행거리 등록 마감일 (보험시기 + 30일, 포함) */
    @Schema(description = "시작 주행거리 등록 마감일 (보험시기 + 30일, 포함)")
    private LocalDate registerUntil;
    /** 정산 가능 시작일 (보험종기 − 30일) */
    @Schema(description = "정산 가능 시작일 (보험종기 − 30일)")
    private LocalDate settleFrom;
    /** 정산 가능 마감일 (보험종기 + 60일) */
    @Schema(description = "정산 가능 마감일 (보험종기 + 60일)")
    private LocalDate settleUntil;
    /** 시작 주행거리 (km) */
    @Schema(description = "시작 주행거리 (km)")
    private Integer startOdometerKm;
    /** 시작 등록 일시 */
    @Schema(description = "시작 등록 일시")
    private LocalDateTime startRegDt;
    /** 만기 주행거리 (km) */
    @Schema(description = "만기 주행거리 (km)")
    private Integer endOdometerKm;
    /** 정산 일시 */
    @Schema(description = "정산 일시")
    private LocalDateTime endRegDt;
    /** 실제 주행거리 (km) */
    @Schema(description = "실제 주행거리 (km)")
    private Integer drivenKm;
    /** 1년 환산 주행거리 (km, 환급률 구간을 찾는 값) */
    @Schema(description = "1년 환산 주행거리 (km, 환급률 구간을 찾는 값)")
    private Integer annualKm;
    /** 적용 환급률 */
    @Schema(description = "적용 환급률")
    private BigDecimal refundRate;
    /** 환급 기준 보험료 (원) */
    @Schema(description = "환급 기준 보험료 (원)")
    private BigDecimal refundBasePrem;
    /** 환급액 (원) */
    @Schema(description = "환급액 (원)")
    private BigDecimal refundAmt;
}
