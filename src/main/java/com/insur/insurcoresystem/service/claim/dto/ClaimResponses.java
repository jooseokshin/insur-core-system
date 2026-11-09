package com.insur.insurcoresystem.service.claim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보상 API 응답 바디
 */
public final class ClaimResponses {

    private ClaimResponses() {
    }

    /** 사고 상세 (접수·사정·종결 처리 결과도 이 모양으로 돌려준다) */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Detail {
        @Schema(description = "사고번호")
        private String clmNo;
        @Schema(description = "보상 상태코드 (10:접수, 20:손해사정 완료, 40:지급 완료, 80:면책, 90:접수 취소)")
        private String clmStatCd;
        @Schema(description = "사고일")
        private LocalDate acdtDt;
        @Schema(description = "사고 내용")
        private String acdtDesc;
        @Schema(description = "청구인 ID")
        private String claimantId;
        @Schema(description = "청구 손해액 합계")
        private BigDecimal totalClaimAmt;
        @Schema(description = "지급액 합계 (손해사정 전이면 null)")
        private BigDecimal totalPayAmt;
        @Schema(description = "지급에 관리자 승인이 필요한지 (지급액 합계가 고액 기준을 넘음)")
        private boolean highValue;
        @Schema(description = "고액 기준 금액")
        private BigDecimal highValueThreshold;
        @Schema(description = "접수 일시")
        private LocalDateTime rptDt;
        @Schema(description = "접수 사번")
        private String rptEmpId;
        @Schema(description = "손해사정 사번")
        private String adjEmpId;
        @Schema(description = "손해사정 일시")
        private LocalDateTime adjDt;
        @Schema(description = "종결 처리 사번")
        private String decideEmpId;
        @Schema(description = "종결 일시")
        private LocalDateTime decideDt;
        @Schema(description = "종결 메모 (면책 사유 등)")
        private String decideMemo;
        @Schema(description = "사고가 난 계약")
        private ContractBrief contract;
        @Schema(description = "담보별 금액")
        private List<Item> items;
        @Schema(description = "상태 이력 (발생 순)")
        private List<History> histories;
        @Schema(description = "실손(05) 진료 내역과 진료별 계산 결과 (실손이 아니면 null)")
        private List<MedicalItem> medicalItems;
        @Schema(description = "실손(05) 비례보상 내역 (다른 보험사 실손이 없거나 손해사정 전이면 빈 목록, 실손이 아니면 null)")
        private List<MedicalShare> medicalShares;
    }

    /** 실손 진료 1건과 계산 결과 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicalItem {
        @Schema(description = "진료 순번 (1부터)")
        private int seq;
        @Schema(description = "진료일")
        private LocalDate treatDt;
        @Schema(description = "진료 구분 (IN: 입원, OUT: 통원)")
        private String careType;
        @Schema(description = "요양기관 종별 (CLINIC, HOSPITAL, GENERAL, TERTIARY)")
        private String facilityType;
        @Schema(description = "의료기관명")
        private String facilityNm;
        @Schema(description = "청구 급여 본인부담금 (원)")
        private BigDecimal coveredAmt;
        @Schema(description = "청구 비급여 금액 (원)")
        private BigDecimal nonCoveredAmt;
        @Schema(description = "인정 급여 본인부담금 (원, 손해사정 전 null)")
        private BigDecimal coveredAssessed;
        @Schema(description = "인정 비급여 금액 (원, 손해사정 전 null)")
        private BigDecimal nonCoveredAssessed;
        @Schema(description = "급여 공제금액 (원)")
        private BigDecimal coveredDeduct;
        @Schema(description = "급여 보상책임액 (원, 연간 한도·비례보상 전)")
        private BigDecimal coveredPay;
        @Schema(description = "비급여 공제금액 (원, 비급여 특약이 없으면 null)")
        private BigDecimal nonCoveredDeduct;
        @Schema(description = "비급여 보상책임액 (원, 비급여 특약이 없으면 null)")
        private BigDecimal nonCoveredPay;
        @Schema(description = "접수 경로 (MANUAL: 직원·채널 입력, KIDI: 실손 청구 전산화)")
        private String sourceCd;
    }

    /** 실손 비례보상 한 줄 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicalShare {
        @Schema(description = "담보코드 (ME001 급여, ME002 비급여)")
        private String cvrgCd;
        @Schema(description = "보험회사 코드 (우리 회사는 설정의 기관코드)")
        private String insurerCd;
        @Schema(description = "증권번호 (다른 회사는 마스킹)")
        private String policyNo;
        @Schema(description = "우리 회사 계약인지")
        private boolean own;
        @Schema(description = "단독 보상책임액 (원)")
        private BigDecimal liabilityAmt;
        @Schema(description = "비례보상 분담액 (원)")
        private BigDecimal shareAmt;
    }

    /** 사고 화면에 필요한 계약 요약 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContractBrief {
        @Schema(description = "증권번호")
        private String contractNo;
        @Schema(description = "보험종목코드")
        private String insrClsCd;
        @Schema(description = "상품코드")
        private String prdCd;
        @Schema(description = "계약상태코드")
        private String ctrStatCd;
        @Schema(description = "계약자 ID")
        private String policyholderId;
        @Schema(description = "피보험자 ID")
        private String insuredId;
        @Schema(description = "보험시기")
        private LocalDate startDate;
        @Schema(description = "보험종기")
        private LocalDate endDate;
        @Schema(description = "해지일")
        private LocalDate termDate;
    }

    /** 담보 하나의 청구·사정·지급 금액 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        @Schema(description = "담보코드")
        private String cvrgCd;
        @Schema(description = "가입금액")
        private BigDecimal isrdAmt;
        @Schema(description = "이 계약의 다른 사고로 이미 지급한 금액")
        private BigDecimal paidByOthers;
        @Schema(description = "청구 손해액")
        private BigDecimal claimAmt;
        @Schema(description = "사정 손해액")
        private BigDecimal assessedAmt;
        @Schema(description = "자기부담금")
        private BigDecimal deductibleAmt;
        @Schema(description = "지급액")
        private BigDecimal payAmt;
    }

    /** 상태 이력 한 건 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class History {
        @Schema(description = "변경 전 상태 (접수는 null)")
        private String beforeStatCd;
        @Schema(description = "변경 후 상태")
        private String afterStatCd;
        @Schema(description = "처리 사번")
        private String empId;
        @Schema(description = "메모")
        private String memo;
        @Schema(description = "처리 일시")
        private LocalDateTime chgDt;
    }

    /** 사고 목록 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Page {
        @Schema(description = "페이지 번호 (0부터)")
        private int page;
        @Schema(description = "페이지 크기")
        private int size;
        @Schema(description = "전체 건수")
        private long totalElements;
        @Schema(description = "전체 페이지 수")
        private int totalPages;
        @Schema(description = "사고 목록 (최근 접수순)")
        private List<Summary> items;
    }

    /** 목록의 사고 한 건 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        @Schema(description = "사고번호")
        private String clmNo;
        @Schema(description = "증권번호")
        private String contractNo;
        @Schema(description = "보험종목코드")
        private String insrClsCd;
        @Schema(description = "보상 상태코드")
        private String clmStatCd;
        @Schema(description = "사고일")
        private LocalDate acdtDt;
        @Schema(description = "청구인 ID")
        private String claimantId;
        @Schema(description = "청구 손해액 합계")
        private BigDecimal totalClaimAmt;
        @Schema(description = "지급액 합계")
        private BigDecimal totalPayAmt;
        @Schema(description = "고액 여부")
        private boolean highValue;
        @Schema(description = "접수 일시")
        private LocalDateTime rptDt;
        @Schema(description = "접수 사번")
        private String rptEmpId;
    }
}
