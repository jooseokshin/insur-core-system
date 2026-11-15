package com.insur.insurcoresystem.ext.kidi;

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
 * 보험개발원 실손 청구 전산화(실손24) 수신 전문. 환자가 병원·앱에서 청구를 요청하면 의료기관이 진료비 서류를 보험개발원 중계시스템에 보내고,
 * 중계시스템이 환자가 고른 보험회사에 이 형태로 넘긴다. 항목 구성은 이 프로젝트가 정한 것이며 실제 연계 규격이 아니다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KidiClaimRequest {
    /** 중계 전송번호 (보험개발원이 매긴다, 30자 이하). 같은 번호는 한 번만 접수한다 */
    @Schema(description = "중계 전송번호 (보험개발원이 매긴다, 30자 이하). 같은 번호는 한 번만 접수한다")
    private String transmissionId;
    /** 요양기관기호 (8자리 숫자) */
    @Schema(description = "요양기관기호 (8자리 숫자)")
    private String hospitalCode;
    /** 의료기관명 */
    @Schema(description = "의료기관명")
    private String hospitalName;
    /** 요양기관 종별 (CLINIC: 의원, HOSPITAL: 병원, GENERAL: 종합병원, TERTIARY: 상급종합병원) */
    @Schema(description = "요양기관 종별 (CLINIC: 의원, HOSPITAL: 병원, GENERAL: 종합병원, TERTIARY: 상급종합병원)")
    private String facilityType;
    /** 피보험자(환자) 식별값. 실제 연계는 주민등록번호로 찾고, 이 시스템은 고객 ID 를 쓴다 */
    @Schema(description = "피보험자(환자) 식별값. 실제 연계는 주민등록번호로 찾고, 이 시스템은 고객 ID 를 쓴다")
    private String insuredId;
    /** 청구인 ID (환자 본인이 아니면 보호자 등, 생략하면 피보험자) */
    @Schema(description = "청구인 ID (환자 본인이 아니면 보호자 등, 생략하면 피보험자)")
    private String claimantId;
    /** 진료 내역 (진료비 계산서·영수증 한 장 = 1건) */
    @Schema(description = "진료 내역 (진료비 계산서·영수증 한 장 = 1건)")
    private List<Treatment> treatments;

    /** 진료 1건 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Treatment {
        /** 진료일 (입원은 입원 시작일) */
        @Schema(description = "진료일 (입원은 입원 시작일)")
        private LocalDate treatDt;
        /** 진료 구분 (IN: 입원, OUT: 통원) */
        @Schema(description = "진료 구분 (IN: 입원, OUT: 통원)")
        private String careType;
        /** 급여 본인부담금 (원) */
        @Schema(description = "급여 본인부담금 (원)")
        private BigDecimal coveredAmt;
        /** 비급여 금액 (원) */
        @Schema(description = "비급여 금액 (원)")
        private BigDecimal nonCoveredAmt;
    }
}
