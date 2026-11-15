package com.insur.insurcoresystem.service.payout.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 지급 API 요청 바디
 */
public final class PayoutRequests {

    private PayoutRequests() {
    }

    /** 예금주 조회 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Verify {
        /** 은행 코드 (숫자 3자리) */
        @Schema(description = "은행 코드 (숫자 3자리)")
        private String bankCd;
        /** 계좌번호 (숫자 10~16자리, '-' 없이) */
        @Schema(description = "계좌번호 (숫자 10~16자리, '-' 없이)")
        private String accountNo;
        /** 받는 분이 알려 준 예금주 (은행 값과 비교) */
        @Schema(description = "받는 분이 알려 준 예금주 (은행 값과 비교)")
        private String holderNm;
    }

    /** 지급 요청 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        /** 지급 원천 (CLAIM: 보험금, MILEAGE: 마일리지 환급금) */
        @Schema(description = "지급 원천 (CLAIM: 보험금, MILEAGE: 마일리지 환급금)")
        private String srcType;
        /** 원천 번호 (보험금은 사고번호, 마일리지는 증권번호) */
        @Schema(description = "원천 번호 (보험금은 사고번호, 마일리지는 증권번호)")
        private String srcNo;
        /** 은행 코드 (숫자 3자리) */
        @Schema(description = "은행 코드 (숫자 3자리)")
        private String bankCd;
        /** 계좌번호 (숫자 10~16자리) */
        @Schema(description = "계좌번호 (숫자 10~16자리)")
        private String accountNo;
        /** 예금주 (은행 예금주 조회 값과 같아야 한다) */
        @Schema(description = "예금주 (은행 예금주 조회 값과 같아야 한다)")
        private String holderNm;
    }

    /** 지급 목록 조회 (조건은 모두 선택) */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListCond {
        /** 지급 원천 (CLAIM, MILEAGE) */
        @Schema(description = "지급 원천 (CLAIM, MILEAGE)")
        private String srcType;
        /** 원천 번호 */
        @Schema(description = "원천 번호")
        private String srcNo;
        /** 증권번호 */
        @Schema(description = "증권번호")
        private String contractNo;
        /** 지급 상태 (10, 30, 70, 80) */
        @Schema(description = "지급 상태 (10, 30, 70, 80)")
        private String statCd;
        /** 최대 건수 (1~200, 기본 50) */
        @Schema(description = "최대 건수 (1~200, 기본 50)")
        private Integer limit;
    }
}
