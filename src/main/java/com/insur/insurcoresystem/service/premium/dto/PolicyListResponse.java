package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 필수 요율 계수 정책 목록 조회 응답 바디. 보험종목, 계수 종류, 적용 시작일 순으로 정렬한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyListResponse {

    /** 정책 행 */
    @Schema(description = "정책 행")
    private List<PolicyItem> items;

    /** 정책 행 하나 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PolicyItem {

        /** 정책 일련번호 */
        @Schema(description = "정책 일련번호")
        private Long policyId;
        /** 보험종목코드 */
        @Schema(description = "보험종목코드")
        private String insrClsCd;
        /** 계수 종류 */
        @Schema(description = "계수 종류")
        private String factorType;
        /** 이 기간에 입력이 필수인지 */
        @Schema(description = "이 기간에 입력이 필수인지")
        private boolean required;
        /** 적용 시작일 (포함) */
        @Schema(description = "적용 시작일 (포함)")
        private LocalDate effFrom;
        /** 적용 종료일 (포함). 없으면 현재까지 유효 */
        @Schema(description = "적용 종료일 (포함). 없으면 현재까지 유효")
        private LocalDate effTo;
        /** 오늘(서울 기준) 기준 상태: ACTIVE(적용 중), FUTURE(적용 예정), CLOSED(종료) */
        @Schema(description = "오늘(서울 기준) 기준 상태: ACTIVE(적용 중), FUTURE(적용 예정), CLOSED(종료)")
        private String status;
    }
}
