package com.insur.insurcoresystem.mapper.contract;

import com.insur.insurcoresystem.common.paging.PageQuery;
import com.insur.insurcoresystem.domain.contract.ContractEndorsement;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 계약 배서 이력(TB_CTR_ENDORSE) 매퍼.
 */
public interface ContractEndorsementMapper {

    /** 계약의 배서 이력 한 페이지 (배서 ID 순) */
    List<ContractEndorsement> findPageByContractNo(@Param("contractNo") String contractNo, @Param("page") PageQuery page);

    /** 계약의 배서 이력 건수 */
    long countByContractNo(String contractNo);

    /** 같은 계약에서 같은 거래 UUID 로 이미 처리한 배서 항목 (발생 순). 재전송을 가려내는 데 쓴다. */
    List<ContractEndorsement> findByContractNoAndGlobalTxId(@Param("contractNo") String contractNo,
                                                            @Param("globalTxId") String globalTxId);

    /** 배서 항목을 넣고 생성된 ID 를 채운다 */
    void insert(ContractEndorsement endorsement);
}
