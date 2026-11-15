package com.insur.insurcoresystem.mapper.medical;

import com.insur.insurcoresystem.service.claim.dto.ClaimResponses;
import com.insur.insurcoresystem.service.medical.MedicalClaimCalculator;
import com.insur.insurcoresystem.service.medical.dto.MedicalCheckResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * 실손의료보험 매퍼: 중복가입 조회 기록(TB_CTR_MED_CHECK), 진료 내역(TB_CLM_MED_ITEM), 비례보상(TB_CLM_MED_SHARE),
 * 상품별 공제 규칙(TB_PRD_MED_RULE).
 */
public interface MedicalMapper {

    // ---- 중복가입 조회 ----

    /** 동의를 기록하고 조회 실패(80)로 시작한다 (조회가 성공하면 10 으로 바꾼다) */
    void insertCheck(@Param("contractNo") String contractNo, @Param("consentDt") LocalDateTime consentDt);

    Optional<LocalDateTime> findConsentDt(String contractNo);

    Optional<MedicalCheckResponse> findCheck(String contractNo);

    int markCheckDone(@Param("contractNo") String contractNo, @Param("otherCount") int otherCount,
                      @Param("now") LocalDateTime now, @Param("empId") String empId);

    int markCheckFailed(@Param("contractNo") String contractNo, @Param("now") LocalDateTime now, @Param("empId") String empId,
                        @Param("lastError") String lastError);

    /** 피보험자의 유효·해지 실손 계약 중 진료일을 보장하는 것 (증권번호 순) */
    List<String> findCoveringContractNos(@Param("insuredId") String insuredId, @Param("treatDate") LocalDate treatDate);

    // ---- 진료 내역·비례보상 ----

    void insertItem(@Param("clmNo") String clmNo, @Param("seq") int seq, @Param("treatDt") LocalDate treatDt,
                    @Param("careType") String careType, @Param("facilityType") String facilityType,
                    @Param("facilityNm") String facilityNm, @Param("coveredAmt") BigDecimal coveredAmt,
                    @Param("nonCoveredAmt") BigDecimal nonCoveredAmt, @Param("sourceCd") String sourceCd);

    /** 사정 계산 입력 (순번 순) */
    List<ClaimedItem> findClaimedItems(String clmNo);

    int updateItemResult(@Param("clmNo") String clmNo, @Param("seq") int seq, @Param("coveredAssessed") BigDecimal coveredAssessed,
                         @Param("nonCoveredAssessed") BigDecimal nonCoveredAssessed, @Param("coveredDeduct") BigDecimal coveredDeduct,
                         @Param("coveredPay") BigDecimal coveredPay, @Param("nonCoveredDeduct") BigDecimal nonCoveredDeduct,
                         @Param("nonCoveredPay") BigDecimal nonCoveredPay);

    /** 사고 상세의 진료 내역 (순번 순) */
    List<ClaimResponses.MedicalItem> findItems(String clmNo);

    int deleteShares(String clmNo);

    void insertShare(@Param("clmNo") String clmNo, @Param("cvrgCd") String cvrgCd, @Param("insurerCd") String insurerCd,
                     @Param("policyNo") String policyNo, @Param("liabilityAmt") BigDecimal liabilityAmt,
                     @Param("shareAmt") BigDecimal shareAmt);

    /** 사고 상세의 비례보상 내역. 우리 회사(ownInsurerCd) 행이 담보마다 먼저 */
    List<ClaimResponses.MedicalShare> findShares(@Param("clmNo") String clmNo, @Param("ownInsurerCd") String ownInsurerCd);

    /** 상품의 공제·한도 규칙 */
    List<MedicalClaimCalculator.Rule> findRules(String prdCd);

    /** 사정 계산에 쓰는 진료 한 건 (접수 금액) */
    record ClaimedItem(int seq, String careType, String facilityType, BigDecimal coveredAmt, BigDecimal nonCoveredAmt) {
    }
}
