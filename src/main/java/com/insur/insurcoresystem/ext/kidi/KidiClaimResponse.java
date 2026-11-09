package com.insur.insurcoresystem.ext.kidi;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 실손 청구 전산화 수신 결과 (중계시스템이 환자에게 접수 결과를 알리는 데 쓴다)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KidiClaimResponse {
    /** 중계 전송번호 */
    @Schema(description = "중계 전송번호")
    private String transmissionId;
    /** 접수한 사고번호 */
    @Schema(description = "접수한 사고번호")
    private String clmNo;
    /** 청구를 연결한 증권번호 */
    @Schema(description = "청구를 연결한 증권번호")
    private String contractNo;
    /** 보상 상태코드 (10: 접수) */
    @Schema(description = "보상 상태코드 (10: 접수)")
    private String clmStatCd;
    /** 접수 일시 */
    @Schema(description = "접수 일시")
    private LocalDateTime rptDt;
}
