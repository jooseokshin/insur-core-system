package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 변경(배서) 응답 바디. 바뀐 항목과 변경 후 계약자/피보험자를 담는다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractEndorseResponse {

    /** 증권번호 */
    @Schema(description = "증권번호")
    private String contractNo;
    /** 변경 후 계약자 ID */
    @Schema(description = "변경 후 계약자 ID")
    private String policyholderId;
    /** 변경 후 피보험자 ID */
    @Schema(description = "변경 후 피보험자 ID")
    private String insuredId;
    /** 실제로 바뀐 항목 (바뀐 순서) */
    @Schema(description = "실제로 바뀐 항목 (바뀐 순서)")
    private List<Change> changes;

    /** 바뀐 항목 하나 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Change {

        /** 변경한 항목 (POLICYHOLDER_ID, INSURED_ID) */
        @Schema(description = "변경한 항목 (POLICYHOLDER_ID, INSURED_ID)")
        private String fieldNm;
        /** 변경 전 값 */
        @Schema(description = "변경 전 값")
        private String beforeVal;
        /** 변경 후 값 */
        @Schema(description = "변경 후 값")
        private String afterVal;
    }
}
