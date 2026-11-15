package com.insur.insurcoresystem.mapper.payout;

import com.insur.insurcoresystem.service.payout.dto.PayoutResponses;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import org.apache.ibatis.annotations.Param;

/**
 * 지급 원장(TB_PAY_OUT) 매퍼. 계좌번호는 암호문(ACCOUNT_ENC)과 마스킹 값만 저장한다.
 */
public interface PayoutMapper {

    /** 지급 결정(40)되고 지급액이 있는 사고의 증권번호와 지급액 */
    Optional<Source> findClaimSource(String clmNo);

    /** 정산(20)되고 환급액이 있는 마일리지 특약의 증권번호와 환급액 */
    Optional<Source> findMileageSource(String contractNo);

    /** 실패(80)가 아닌 지급이 원천에 있는지 */
    boolean existsActive(@Param("srcType") String srcType, @Param("srcNo") String srcNo);

    /** 대기(10)로 넣는다 */
    void insert(NewPayout payout);

    Optional<PayoutResponses.Payout> findByGlobalTxId(String globalTxId);

    /** 조건에 맞는 지급 (최근 순). null 인 조건은 빠진다 */
    List<PayoutResponses.Payout> search(@Param("srcType") String srcType, @Param("srcNo") String srcNo,
                                        @Param("contractNo") String contractNo, @Param("statCd") String statCd,
                                        @Param("limit") int limit);

    /** 처리할 때가 된 대기(10)·결과 불명(70) 한 건을 잠그고 가져온다 (FOR UPDATE SKIP LOCKED) */
    Optional<Due> lockNextDue(LocalDateTime now);

    /**
     * 처리 결과를 저장한다.
     *
     * @param inquiryCount null 이면 결과 조회 횟수를 바꾸지 않는다
     * @param markSent     처음 보낸 일시(SENT_DT)를 남길지
     * @param done         끝난 일시(DONE_DT)를 남길지
     */
    int updateResult(@Param("payoutId") long payoutId, @Param("statCd") String statCd, @Param("resultCd") String resultCd,
                     @Param("resultMsg") String resultMsg, @Param("sendCount") int sendCount,
                     @Param("inquiryCount") Integer inquiryCount, @Param("nextTryDt") LocalDateTime nextTryDt,
                     @Param("markSent") boolean markSent, @Param("done") boolean done, @Param("now") LocalDateTime now);

    record Source(String contractNo, BigDecimal amount) {
    }

    @Builder
    record NewPayout(String srcType, String srcNo, String contractNo, BigDecimal payAmt, String bankCd, String accountEnc,
                     String accountMask, String holderNm, String traceNo, LocalDateTime now, String reqEmpId, String globalTxId) {
    }

    record Due(long payoutId, String statCd, String bankCd, String accountEnc, BigDecimal payAmt, String holderNm, String traceNo,
               int sendCount, int inquiryCount, String srcType) {
    }
}
