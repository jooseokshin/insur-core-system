package com.insur.insurcoresystem.service.contract.dto;

import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 청약 요청 바디
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractApplyRequest {

    /** 보험종목코드 (01:자동차, 02:화재) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재)")
    private String insrClsCd;
    /** 보험시기 (보험 개시일) */
    @Schema(description = "보험시기 (보험 개시일)")
    private LocalDate startDate;
    /** 보험종기 (보험 만료일, 보험시기 이후여야 함) */
    @Schema(description = "보험종기 (보험 만료일, 보험시기 이후여야 함)")
    private LocalDate endDate;
    /** 계약자 ID (최대 20자) */
    @Schema(description = "계약자 ID (최대 20자)")
    private String policyholderId;
    /** 피보험자 ID (최대 20자) */
    @Schema(description = "피보험자 ID (최대 20자)")
    private String insuredId;
    /** 총보험료 (담보보험료 합계와 일치해야 함) */
    @Schema(description = "총보험료 (담보보험료 합계와 일치해야 함)")
    private BigDecimal totalPremium;
    /** 요율 계수 입력 (선택). 보험료 산출에 쓴 조건이며, 이 값으로 산출한 보험료와 담보 보험료가 일치해야 한다 */
    @Schema(description = "요율 계수 입력 (선택). 보험료 산출에 쓴 조건이며, 이 값으로 산출한 보험료와 담보 보험료가 일치해야 한다")
    private RatingInput rating;
    /** 가입 담보 목록 (1건 이상) */
    @Schema(description = "가입 담보 목록 (1건 이상)")
    private List<CoverageRequest> coverages;
}
