package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 보험료 산출 요청 바디
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PremiumQuoteRequest {

    /** 보험종목코드 (01:자동차, 02:화재) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재)")
    private String insrClsCd;
    /** 보험시기 */
    @Schema(description = "보험시기")
    private LocalDate startDate;
    /** 보험종기 (보험시기 이후여야 함) */
    @Schema(description = "보험종기 (보험시기 이후여야 함)")
    private LocalDate endDate;
    /** 요율 계수 입력 (선택). 입력하지 않은 계수는 적용하지 않는다 */
    @Schema(description = "요율 계수 입력 (선택). 입력하지 않은 계수는 적용하지 않는다")
    private RatingInput rating;
    /** 보험료를 산출할 담보 목록 (1건 이상, 담보코드 중복 불가) */
    @Schema(description = "보험료를 산출할 담보 목록 (1건 이상, 담보코드 중복 불가)")
    private List<QuoteCoverageRequest> coverages;

    /** 산출 대상 담보 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuoteCoverageRequest {

        /** 담보코드 */
        @Schema(description = "담보코드")
        private String cvrgCd;
        /** 가입금액 (0보다 큰 원 단위 정수) */
        @Schema(description = "가입금액 (0보다 큰 원 단위 정수)")
        private java.math.BigDecimal isrdAmt;
    }
}
