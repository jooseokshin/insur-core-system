package com.insur.insurcoresystem.ext.fep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FEP 운영 API 요청 바디
 */
public final class FepRequests {

    private FepRequests() {
    }

    /** 기관 하나 (개시·종료·에코) */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Inst {
        /** 기관 코드 (KCIS, BANK) */
        @Schema(description = "기관 코드 (KCIS, BANK)")
        private String instCd;
    }

    /** 전문 로그 조회 (조건은 모두 선택) */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageLog {
        /** 기관 코드 */
        @Schema(description = "기관 코드")
        private String instCd;
        /** 추적번호 (정확히 일치) */
        @Schema(description = "추적번호 (정확히 일치)")
        private String traceNo;
        /** 결과 (OK, ERROR, TIMEOUT, BLOCKED) */
        @Schema(description = "결과 (OK, ERROR, TIMEOUT, BLOCKED)")
        private String resultCd;
        /** 요청일 */
        @Schema(description = "요청일")
        private LocalDate date;
        /** 최대 건수 (1~500, 기본 100) */
        @Schema(description = "최대 건수 (1~500, 기본 100)")
        private Integer limit;
    }
}
