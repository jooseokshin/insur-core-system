package com.insur.insurcoresystem.service.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 만기·갱신 대상 조회 요청 바디. 조건은 모두 선택이다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenewalTargetRequest {

    /** 오늘부터 며칠 안에 보험종기가 오는 계약까지 볼지 (1~60, 생략 시 60) */
    @Schema(description = "오늘부터 며칠 안에 보험종기가 오는 계약까지 볼지 (1~60, 생략 시 60)")
    private Integer withinDays;
    /** 보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행, 05:실손의료) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재, 03:운전자, 04:해외여행, 05:실손의료)")
    private String insrClsCd;
    /** 페이지 번호 (0부터, 생략 시 0) */
    @Schema(description = "페이지 번호 (0부터, 생략 시 0)")
    private Integer page;
    /** 페이지 크기 (1~100, 생략 시 20) */
    @Schema(description = "페이지 크기 (1~100, 생략 시 20)")
    private Integer size;
}
