package com.insur.insurcoresystem.service.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계약 목록 조회 응답 바디 (한 페이지 분량)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractListResponse {

    /** 현재 페이지 번호 (0부터 시작) */
    @Schema(description = "현재 페이지 번호 (0부터 시작)")
    private int page;
    /** 요청한 페이지 크기 */
    @Schema(description = "요청한 페이지 크기")
    private int size;
    /** 조건에 맞는 전체 계약 건수 */
    @Schema(description = "조건에 맞는 전체 계약 건수")
    private long totalElements;
    /** 전체 페이지 수 (조건에 맞는 계약이 없으면 0) */
    @Schema(description = "전체 페이지 수 (조건에 맞는 계약이 없으면 0)")
    private int totalPages;
    /** 이 페이지의 계약 (최근 접수순) */
    @Schema(description = "이 페이지의 계약 (최근 접수순)")
    private List<ContractSummaryResponse> items;
}
