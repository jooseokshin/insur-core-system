package com.insur.insurcoresystem.service.medical.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 실손 청약의 신용정보 조회 동의와 중복가입 조회 결과
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicalCheckResponse {
    /** 증권번호 */
    @Schema(description = "증권번호")
    private String contractNo;
    /** 개인신용정보 조회 동의 일시 */
    @Schema(description = "개인신용정보 조회 동의 일시")
    private LocalDateTime consentDt;
    /** 중복가입 조회 상태 (10: 조회 완료, 80: 조회 실패 - 다시 조회해야 승인할 수 있음) */
    @Schema(description = "중복가입 조회 상태 (10: 조회 완료, 80: 조회 실패 - 다시 조회해야 승인할 수 있음)")
    private String checkStatCd;
    /** 다른 보험사 실손 계약 수 (조회 실패면 null) */
    @Schema(description = "다른 보험사 실손 계약 수 (조회 실패면 null)")
    private Integer otherCount;
    /** 마지막 조회 일시 */
    @Schema(description = "마지막 조회 일시")
    private LocalDateTime checkedDt;
    /** 마지막 조회 처리자 */
    @Schema(description = "마지막 조회 처리자")
    private String checkEmpId;
    /** 마지막 조회 실패 사유 */
    @Schema(description = "마지막 조회 실패 사유")
    private String lastError;
    /** 방금 조회한 다른 보험사 실손 계약 (다시 조회 응답에만, 증권번호는 마스킹) */
    @Schema(description = "방금 조회한 다른 보험사 실손 계약 (다시 조회 응답에만, 증권번호는 마스킹)")
    private List<OtherContract> otherContracts;

    /** 다른 보험사 실손 계약 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OtherContract {
        @Schema(description = "보험회사 코드")
        private String insurerCd;
        @Schema(description = "증권번호 (마스킹)")
        private String policyNo;
        @Schema(description = "계약 구분 (01: 개인, 02: 단체)")
        private String kindCd;
        @Schema(description = "보장 시작일")
        private LocalDate startDate;
        @Schema(description = "보장 종료일 (이 날은 보장하지 않음)")
        private LocalDate endDate;
        @Schema(description = "급여 보장 여부")
        private boolean covered;
        @Schema(description = "비급여 보장 여부")
        private boolean nonCovered;
    }
}
