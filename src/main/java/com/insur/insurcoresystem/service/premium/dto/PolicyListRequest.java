package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 필수 요율 계수 정책 목록 조회 요청 바디. 조건은 선택이며 개정 이력 전체를 돌려준다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyListRequest {

    /** 보험종목코드 (01:자동차, 02:화재). 생략하면 전체 */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재). 생략하면 전체")
    private String insrClsCd;
}
