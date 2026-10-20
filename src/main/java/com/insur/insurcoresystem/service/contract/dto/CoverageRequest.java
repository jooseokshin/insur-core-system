package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 담보 청약 입력
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoverageRequest {

    /** 담보코드 (최대 10자) */
    @Schema(description = "담보코드 (최대 10자)")
    private String cvrgCd;
    /** 가입금액 */
    @Schema(description = "가입금액")
    private BigDecimal isrdAmt;
    /** 담보보험료 */
    @Schema(description = "담보보험료")
    private BigDecimal prem;
}
