package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 요율 관리 감사 이력 조회 요청 바디. 조건은 모두 선택이며 최근 처리 순으로 페이지 단위로 돌려준다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditListRequest {

    /** 대상 종류 (RATE, FACTOR, POLICY). 생략하면 전체 */
    @Schema(description = "대상 종류 (RATE, FACTOR, POLICY). 생략하면 전체")
    private String targetType;
    /** 대상 식별의 앞부분 (예: 01/CA001, 01/). 생략하면 전체 */
    @Schema(description = "대상 식별의 앞부분 (예: 01/CA001, 01/). 생략하면 전체")
    private String targetKey;
    /** 처리 사번. 생략하면 전체 */
    @Schema(description = "처리 사번. 생략하면 전체")
    private String empId;
    /** 처리일 시작 (서울 기준, 포함). 생략하면 제한 없음 */
    @Schema(description = "처리일 시작 (서울 기준, 포함). 생략하면 제한 없음")
    private LocalDate fromDate;
    /** 처리일 종료 (서울 기준, 포함). 생략하면 제한 없음 */
    @Schema(description = "처리일 종료 (서울 기준, 포함). 생략하면 제한 없음")
    private LocalDate toDate;
    /** 페이지 번호 (0부터 시작, 생략하면 0) */
    @Schema(description = "페이지 번호 (0부터 시작, 생략하면 0)")
    private Integer page;
    /** 페이지 크기 (1~100, 생략하면 20) */
    @Schema(description = "페이지 크기 (1~100, 생략하면 20)")
    private Integer size;
}
