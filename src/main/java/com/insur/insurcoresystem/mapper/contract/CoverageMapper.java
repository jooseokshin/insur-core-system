package com.insur.insurcoresystem.mapper.contract;

import com.insur.insurcoresystem.domain.contract.Coverage;
import java.util.List;

/**
 * 계약 담보(TB_CTR_CVRG) 매퍼.
 */
public interface CoverageMapper {

    /** 증권번호에 속한 계약 담보 목록 (ID 순) */
    List<Coverage> findByContractNo(String contractNo);

    /** 담보를 넣고 생성된 ID 를 채운다 */
    void insert(Coverage coverage);

    /** 전체 담보 건수 */
    long count();

    /** 한 계약의 담보를 지운다 (시험 정리용) */
    int deleteByContractNo(String contractNo);
}
