package com.insur.insurcoresystem.service.work.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 할 일함 목록 조회 요청 바디
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TodoRequest {

    /** 할 일 유형 (PENDING_APPROVAL: 승인 대기, UNPAID: 미납 청약, STALE_APPLICATION: 장기 미처리) */
    @Schema(description = "할 일 유형 (PENDING_APPROVAL: 승인 대기, UNPAID: 미납 청약, STALE_APPLICATION: 장기 미처리)")
    private String type;
    /** 페이지 번호 (0부터, 생략 시 0) */
    @Schema(description = "페이지 번호 (0부터, 생략 시 0)")
    private Integer page;
    /** 페이지 크기 (1~100, 생략 시 20) */
    @Schema(description = "페이지 크기 (1~100, 생략 시 20)")
    private Integer size;
}
