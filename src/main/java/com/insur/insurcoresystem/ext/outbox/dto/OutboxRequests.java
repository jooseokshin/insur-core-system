package com.insur.insurcoresystem.ext.outbox.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 대외기관 전송 대기열 API 요청 바디
 */
public final class OutboxRequests {

    private OutboxRequests() {
    }

    /** 대기열 조회 (조건은 모두 선택) */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListCond {
        /** 상태 (10: 전송 대기, 20: 전송 완료, 90: 재시도 한도 초과) */
        @Schema(description = "상태 (10: 전송 대기, 20: 전송 완료, 90: 재시도 한도 초과)")
        private String statCd;
        /** 업무 키 앞부분 (증권번호·사고번호) */
        @Schema(description = "업무 키 앞부분 (증권번호·사고번호)")
        private String bizKeyPrefix;
        /** 최대 건수 (1~200, 기본 50) */
        @Schema(description = "최대 건수 (1~200, 기본 50)")
        private Integer limit;
    }

    /** 멈춘 건 다시 보내기 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Retry {
        /** 대기열 일련번호 */
        @Schema(description = "대기열 일련번호")
        private Long outboxId;
    }
}
