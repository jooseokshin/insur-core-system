package com.insur.insurcoresystem.service.contract.dto;

import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 계약 갱신 요청 바디. 보험기간, 계약자/피보험자, 담보와 가입금액은 원 계약을 그대로 이어받는다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRenewRequest {

    /** 갱신할 원 계약의 증권번호 */
    @Schema(description = "갱신할 원 계약의 증권번호")
    private String contractNo;
    /** 갱신 보험료 산출에 쓸 요율 계수 (선택). 생략하면 원 계약에 저장된 값을 쓴다. 필수 계수가 늘었으면 여기서 채워야 한다 */
    @Schema(description = "갱신 보험료 산출에 쓸 요율 계수 (선택). 생략하면 원 계약에 저장된 값을 쓴다. 필수 계수가 늘었으면 여기서 채워야 한다")
    private RatingInput rating;
}
