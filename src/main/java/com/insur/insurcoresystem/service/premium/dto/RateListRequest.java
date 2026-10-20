package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 요율 목록 조회 요청 바디. 조건은 모두 선택이며, 개정 이력 전체(과거, 현재, 예약된 미래 행)를 돌려준다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateListRequest {

    /** 보험종목코드 (01:자동차, 02:화재). 생략하면 전체 */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재). 생략하면 전체")
    private String insrClsCd;
    /** 담보코드. 생략하면 전체 */
    @Schema(description = "담보코드. 생략하면 전체")
    private String cvrgCd;
}
