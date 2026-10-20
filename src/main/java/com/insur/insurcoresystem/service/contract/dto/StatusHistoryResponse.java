package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 조회 응답의 상태 변경 이력
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusHistoryResponse {

    /** 변경 전 계약상태코드 (청약 접수는 null) */
    @Schema(description = "변경 전 계약상태코드 (청약 접수는 null)")
    private String beforeCtrStatCd;
    /** 변경 후 계약상태코드 */
    @Schema(description = "변경 후 계약상태코드")
    private String afterCtrStatCd;
    /** 처리 사번 */
    @Schema(description = "처리 사번")
    private String empId;
    /** 변경 일시 */
    @Schema(description = "변경 일시")
    private LocalDateTime chgDt;
}
