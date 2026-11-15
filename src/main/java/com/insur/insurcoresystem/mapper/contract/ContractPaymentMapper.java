package com.insur.insurcoresystem.mapper.contract;

import com.insur.insurcoresystem.common.paging.PageQuery;
import com.insur.insurcoresystem.domain.contract.ContractPayment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * 보험료 납입 내역(TB_CTR_PAYMENT) 매퍼.
 */
public interface ContractPaymentMapper {

    /** 계약의 납입 내역 한 페이지 (납입 ID 순) */
    List<ContractPayment> findPageByContractNo(@Param("contractNo") String contractNo, @Param("page") PageQuery page);

    /** 계약의 납입 건수 */
    long countByContractNo(String contractNo);

    /** 같은 거래 UUID 로 이미 접수한 납입. 응답을 못 받은 클라이언트의 재전송을 가려내는 데 쓴다. */
    Optional<ContractPayment> findByGlobalTxId(String globalTxId);

    /** 계약에 지금까지 납입한 금액의 합계. 납입이 없으면 0 */
    BigDecimal sumPaid(String contractNo);

    /** 진행 중인 자동이체 출금(의뢰 대상·결과 대기) 건수. 진행 중이면 결과가 오기 전에 수기 수납을 받으면 과납이 될 수 있다. */
    long countDebitsInFlight(String contractNo);

    /** 납입을 넣고 생성된 납입 ID 를 채운다 */
    void insert(ContractPayment payment);

    /** 한 계약의 납입을 지운다 (시험 정리용) */
    int deleteByContractNo(String contractNo);
}
