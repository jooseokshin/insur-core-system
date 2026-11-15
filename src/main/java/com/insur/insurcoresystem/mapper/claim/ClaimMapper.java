package com.insur.insurcoresystem.mapper.claim;

import com.insur.insurcoresystem.domain.claim.Claim;
import com.insur.insurcoresystem.domain.claim.ClaimCoverage;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * 사고 접수(TB_CLM)와 담보별 청구·사정 금액(TB_CLM_CVRG) 매퍼. 사고를 읽으면 담보 행도 담보코드 순으로 함께 채운다.
 */
public interface ClaimMapper {

    Optional<Claim> findById(String clmNo);

    /** 사고를 조회하면서 행을 잠근다 (SELECT ... FOR UPDATE). 트랜잭션 안에서만 부른다. */
    Optional<Claim> findForUpdateByClmNo(String clmNo);

    /** 접수 거래 UUID 로 조회 (재전송 판별) */
    Optional<Claim> findByGlobalTxId(String globalTxId);

    /** 계약에 주어진 상태의 사고가 있는지 */
    boolean existsByContractNoAndClmStatCdIn(@Param("contractNo") String contractNo,
                                             @Param("clmStatCds") Collection<String> clmStatCds);

    /**
     * 한 계약의 한 담보에 이미 지급한 보험금 합계. 다른 사고(지급 완료)의 지급액만 센다.
     *
     * @param excludeClmNo 계산에서 뺄 사고번호 (지금 처리 중인 사고)
     */
    BigDecimal sumPaid(@Param("contractNo") String contractNo, @Param("cvrgCd") String cvrgCd,
                       @Param("excludeClmNo") String excludeClmNo);

    /** 사고와 담보 행을 함께 넣는다. */
    default void insert(Claim claim) {
        insertHeader(claim);
        for (ClaimCoverage item : claim.getItems()) {
            insertItem(claim.getClmNo(), item);
        }
    }

    /** 사정 결과(담보별 사정·공제·지급액과 사고 헤더)를 저장한다. */
    default void updateAssessment(Claim claim) {
        updateHeader(claim);
        for (ClaimCoverage item : claim.getItems()) {
            updateItem(claim.getClmNo(), item);
        }
    }

    void insertHeader(Claim claim);

    void insertItem(@Param("clmNo") String clmNo, @Param("item") ClaimCoverage item);

    /** 상태, 지급 합계, 사정·결정 정보를 저장한다. */
    int updateHeader(Claim claim);

    int updateItem(@Param("clmNo") String clmNo, @Param("item") ClaimCoverage item);
}
