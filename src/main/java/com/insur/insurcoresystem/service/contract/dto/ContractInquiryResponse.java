package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 조회 응답 바디. 상태 변경 이력은 포함하지 않으며 상태 이력 조회 API 로 따로 조회한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractInquiryResponse {

    /** 증권번호 */
    @Schema(description = "증권번호")
    private String contractNo;
    /** 청약번호 */
    @Schema(description = "청약번호")
    private String applNo;
    /** 보험종목코드 (01:자동차, 02:화재) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재)")
    private String insrClsCd;
    /** 계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소) */
    @Schema(description = "계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소)")
    private String ctrStatCd;
    /** 보험시기 */
    @Schema(description = "보험시기")
    private LocalDate startDate;
    /** 보험종기 */
    @Schema(description = "보험종기")
    private LocalDate endDate;
    /** 계약자 ID */
    @Schema(description = "계약자 ID")
    private String policyholderId;
    /** 피보험자 ID */
    @Schema(description = "피보험자 ID")
    private String insuredId;
    /** 총보험료 */
    @Schema(description = "총보험료")
    private BigDecimal totalPremium;
    /** 시스템 등록일시 */
    @Schema(description = "시스템 등록일시")
    private LocalDateTime sysRegDt;
    /** 청약 접수 사번 (이력 이전 계약은 null) */
    @Schema(description = "청약 접수 사번 (이력 이전 계약은 null)")
    private String regEmpId;
    /** 보험료 산출에 쓴 운전자 만 나이 (자동차, 입력하지 않았으면 null) */
    @Schema(description = "보험료 산출에 쓴 운전자 만 나이 (자동차, 입력하지 않았으면 null)")
    private Integer driverAge;
    /** 보험료 산출에 쓴 할인할증 등급 (자동차, 입력하지 않았으면 null) */
    @Schema(description = "보험료 산출에 쓴 할인할증 등급 (자동차, 입력하지 않았으면 null)")
    private String discountGrade;
    /** 보험료 산출에 쓴 건물 구조 등급 (화재, 입력하지 않았으면 null) */
    @Schema(description = "보험료 산출에 쓴 건물 구조 등급 (화재, 입력하지 않았으면 null)")
    private String structureGrade;
    /** 해지일 (해지하지 않았으면 null) */
    @Schema(description = "해지일 (해지하지 않았으면 null)")
    private LocalDate termDate;
    /** 해지 환급보험료 (해지하지 않았으면 null) */
    @Schema(description = "해지 환급보험료 (해지하지 않았으면 null)")
    private BigDecimal refundPremium;
    /** 갱신으로 만든 계약이면 원 계약의 증권번호 (아니면 null) */
    @Schema(description = "갱신으로 만든 계약이면 원 계약의 증권번호 (아니면 null)")
    private String renewedFrom;
    /** 가입 담보 목록 */
    @Schema(description = "가입 담보 목록")
    private List<CoverageResponse> coverages;
}
