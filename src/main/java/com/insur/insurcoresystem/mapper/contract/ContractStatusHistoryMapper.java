package com.insur.insurcoresystem.mapper.contract;

import com.insur.insurcoresystem.common.paging.PageQuery;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistory;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * 계약 상태 변경 이력(TB_CTR_STAT_HIST) 매퍼.
 */
public interface ContractStatusHistoryMapper {

    /** 계약의 상태 변경 이력 한 페이지 (이력 ID 순) */
    List<ContractStatusHistory> findPageByContractNo(@Param("contractNo") String contractNo, @Param("page") PageQuery page);

    /** 계약의 이력 건수 */
    long countByContractNo(String contractNo);

    /**
     * 같은 계약에서 같은 거래 UUID 로 처음 처리한 상태 변경. 응답을 못 받은 클라이언트의 재전송을 가려내는 데 쓴다.
     * 청약 접수 기록(변경 전 상태 없음)은 상태 변경이 아니므로 제외한다.
     */
    Optional<ContractStatusHistory> findFirstChange(@Param("contractNo") String contractNo, @Param("globalTxId") String globalTxId);

    /** 이력을 넣고 생성된 이력 ID 를 채운다 */
    void insert(ContractStatusHistory history);

    /** 한 계약의 이력을 지운다 (시험 정리용) */
    int deleteByContractNo(String contractNo);
}
