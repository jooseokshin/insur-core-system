package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 계약 목록 조회 요청 바디. 검색 조건은 모두 선택이며 입력한 조건만 AND 로 적용한다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractListRequest {

    /** 증권번호 (앞부분 일치, 예: POL-CAR-2026) */
    @Schema(description = "증권번호 (앞부분 일치, 예: POL-CAR-2026)")
    private String contractNo;
    /** 청약번호 (앞부분 일치, 예: APP-FIRE) */
    @Schema(description = "청약번호 (앞부분 일치, 예: APP-FIRE)")
    private String applNo;
    /** 보험종목코드 (01:자동차, 02:화재) */
    @Schema(description = "보험종목코드 (01:자동차, 02:화재)")
    private String insrClsCd;
    /** 계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소) */
    @Schema(description = "계약상태코드 (10:청약, 20:유효, 30:해지, 90:청약취소)")
    private String ctrStatCd;
    /** 계약자 ID (정확히 일치) */
    @Schema(description = "계약자 ID (정확히 일치)")
    private String policyholderId;
    /** 피보험자 ID (정확히 일치) */
    @Schema(description = "피보험자 ID (정확히 일치)")
    private String insuredId;
    /** 보험시기 범위 시작 (포함) */
    @Schema(description = "보험시기 범위 시작 (포함)")
    private LocalDate startDateFrom;
    /** 보험시기 범위 종료 (포함) */
    @Schema(description = "보험시기 범위 종료 (포함)")
    private LocalDate startDateTo;
    /** 총보험료 최소 (포함, 0 이상) */
    @Schema(description = "총보험료 최소 (포함, 0 이상)")
    private BigDecimal premiumMin;
    /** 총보험료 최대 (포함) */
    @Schema(description = "총보험료 최대 (포함)")
    private BigDecimal premiumMax;
    /** 청약 접수일 시작 (포함) */
    @Schema(description = "청약 접수일 시작 (포함)")
    private LocalDate regDateFrom;
    /** 청약 접수일 종료 (당일 포함) */
    @Schema(description = "청약 접수일 종료 (당일 포함)")
    private LocalDate regDateTo;
    /** 정렬 기준: REG_DATE(기본, 접수일시), START_DATE(보험시기), TOTAL_PREMIUM(총보험료) */
    @Schema(description = "정렬 기준: REG_DATE(기본, 접수일시), START_DATE(보험시기), TOTAL_PREMIUM(총보험료)")
    private String sortBy;
    /** 정렬 방향: DESC(기본) 또는 ASC */
    @Schema(description = "정렬 방향: DESC(기본) 또는 ASC")
    private String sortDir;
    /** 페이지 번호 (0부터 시작, 생략하면 0) */
    @Schema(description = "페이지 번호 (0부터 시작, 생략하면 0)")
    private Integer page;
    /** 페이지 크기 (1~100, 생략하면 20) */
    @Schema(description = "페이지 크기 (1~100, 생략하면 20)")
    private Integer size;
}
