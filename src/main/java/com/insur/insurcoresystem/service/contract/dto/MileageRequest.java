package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 마일리지 특약 요청 바디. 등록·정산은 주행거리를 넣고, 조회는 증권번호만 넣는다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MileageRequest {

    /** 증권번호 (자동차 계약) */
    @Schema(description = "증권번호 (자동차 계약)")
    private String contractNo;
    /** 계기판 누적 주행거리 (km, 0 이상 9,999,999 이하). 등록은 시작, 정산은 만기 주행거리 */
    @Schema(description = "계기판 누적 주행거리 (km, 0 이상 9,999,999 이하). 등록은 시작, 정산은 만기 주행거리")
    private Integer odometerKm;
}
