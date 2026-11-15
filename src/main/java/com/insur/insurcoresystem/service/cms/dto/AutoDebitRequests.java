package com.insur.insurcoresystem.service.cms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 보험료 자동이체 API 요청 바디
 */
public final class AutoDebitRequests {

    private AutoDebitRequests() {
    }

    /** 자동이체 등록 (출금 동의) */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Register {
        /** 증권번호 (청약 또는 유효 계약) */
        @Schema(description = "증권번호 (청약 또는 유효 계약)")
        private String contractNo;
        /** 출금 은행 코드 (숫자 3자리) */
        @Schema(description = "출금 은행 코드 (숫자 3자리)")
        private String bankCd;
        /** 출금 계좌번호 (숫자 10~16자리) */
        @Schema(description = "출금 계좌번호 (숫자 10~16자리)")
        private String accountNo;
        /** 예금주 (출금 동의자, 은행 예금주와 같아야 한다) */
        @Schema(description = "예금주 (출금 동의자, 은행 예금주와 같아야 한다)")
        private String payerNm;
        /** 출금 동의 방식 (PAPER: 서면, RECORDING: 전화 녹취, ARS: 자동응답, E_SIGN: 전자서명) */
        @Schema(description = "출금 동의 방식 (PAPER: 서면, RECORDING: 전화 녹취, ARS: 자동응답, E_SIGN: 전자서명)")
        private String consentMethod;
        /** 동의 증빙 관리번호 (신청서 번호, 녹취 파일 키 등, 100자 이하) */
        @Schema(description = "동의 증빙 관리번호 (신청서 번호, 녹취 파일 키 등, 100자 이하)")
        private String consentRef;
    }

    /** 해지·조회 */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Contract {
        /** 증권번호 */
        @Schema(description = "증권번호")
        private String contractNo;
    }
}
