package com.insur.insurcoresystem.mapper.claim;

import com.insur.insurcoresystem.common.paging.PageQuery;
import com.insur.insurcoresystem.domain.claim.Claim;
import com.insur.insurcoresystem.domain.claim.ClaimCoverage;
import com.insur.insurcoresystem.service.claim.dto.ClaimResponses;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
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

    /** 조건에 맞는 사고 한 페이지 (최근 접수순) */
    List<ClaimResponses.Summary> search(@Param("c") SearchCondition condition, @Param("page") PageQuery page);

    long countSearch(@Param("c") SearchCondition condition);

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

    /**
     * 사고 목록 조건. null 인 항목은 조건에서 빠진다.
     *
     * @param regEmpId      계약 접수 사번 (본인 계약만 보는 호출자)
     * @param rptFrom       접수일시 하한 (포함)
     * @param rptTo         접수일시 상한 (미포함)
     * @param highValueOnly 손해사정(20) 중 지급액이 threshold 를 넘는 건만
     * @param threshold     고액 기준 (응답의 highValue 계산에도 쓴다)
     */
    @Builder
    record SearchCondition(String regEmpId, String clmStatCd, String contractNo, LocalDateTime rptFrom, LocalDateTime rptTo,
                           boolean highValueOnly, BigDecimal threshold) {
    }
}
