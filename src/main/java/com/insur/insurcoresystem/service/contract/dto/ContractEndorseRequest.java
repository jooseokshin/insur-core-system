package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 계약 변경(배서) 요청 바디. 바꿀 항목만 입력하며, 하나 이상은 현재 값과 달라야 한다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractEndorseRequest {

    /** 변경할 계약의 증권번호 */
    @Schema(description = "변경할 계약의 증권번호")
    private String contractNo;
    /** 새 계약자 ID (최대 20자, 바꾸지 않으면 생략) */
    @Schema(description = "새 계약자 ID (최대 20자, 바꾸지 않으면 생략)")
    private String policyholderId;
    /** 새 피보험자 ID (최대 20자, 바꾸지 않으면 생략) */
    @Schema(description = "새 피보험자 ID (최대 20자, 바꾸지 않으면 생략)")
    private String insuredId;
}
