package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 조회 응답의 담보 정보
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoverageResponse {

    /** 담보코드 */
    @Schema(description = "담보코드")
    private String cvrgCd;
    /** 가입금액 */
    @Schema(description = "가입금액")
    private BigDecimal isrdAmt;
    /** 담보보험료 */
    @Schema(description = "담보보험료")
    private BigDecimal prem;
}
