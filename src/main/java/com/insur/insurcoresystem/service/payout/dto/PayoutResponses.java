package com.insur.insurcoresystem.service.payout.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지급 API 응답 바디
 */
public final class PayoutResponses {

    private PayoutResponses() {
    }

    /** 예금주 조회 결과 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyResult {
        @Schema(description = "계좌가 정상이고 예금주가 같은지")
        private boolean matched;
        @Schema(description = "은행 응답코드 (0000 정상, B101 없는 계좌, B102 해지, B103 지급정지)")
        private String resultCd;
        @Schema(description = "결과 설명")
        private String resultMsg;
        @Schema(description = "은행 예금주 (가운데 글자를 가림, 계좌 오류면 null)")
        private String bankHolderNm;
    }

    /** 지급 한 건 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Payout {
        @Schema(description = "지급 일련번호")
        private long payoutId;
        @Schema(description = "지급 원천 (CLAIM, MILEAGE)")
        private String srcType;
        @Schema(description = "원천 번호")
        private String srcNo;
        @Schema(description = "증권번호")
        private String contractNo;
        @Schema(description = "지급액 (원)")
        private BigDecimal payAmt;
        @Schema(description = "은행 코드")
        private String bankCd;
        @Schema(description = "계좌번호 (마스킹)")
        private String accountMask;
        @Schema(description = "예금주")
        private String holderNm;
        @Schema(description = "지급 상태 (10: 이체 대기, 30: 이체 완료, 70: 결과 불명 - 결과 조회 중, 80: 실패)")
        private String statCd;
        @Schema(description = "이체 거래고유번호")
        private String traceNo;
        @Schema(description = "이체 전문 전송 횟수")
        private int sendCount;
        @Schema(description = "결과 조회 횟수")
        private int inquiryCount;
        @Schema(description = "다음 처리 일시")
        private LocalDateTime nextTryDt;
        @Schema(description = "마지막 은행 응답코드")
        private String resultCd;
        @Schema(description = "마지막 처리 결과")
        private String resultMsg;
        @Schema(description = "요청자")
        private String reqEmpId;
        @Schema(description = "요청 일시")
        private LocalDateTime reqDt;
        @Schema(description = "처음 전송 일시")
        private LocalDateTime sentDt;
        @Schema(description = "완료·실패 일시")
        private LocalDateTime doneDt;
    }
}
