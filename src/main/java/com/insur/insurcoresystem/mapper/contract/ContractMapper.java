package com.insur.insurcoresystem.mapper.contract;

import com.insur.insurcoresystem.common.paging.PageQuery;
import com.insur.insurcoresystem.domain.contract.Contract;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * 계약 원장(TB_CTR_LGD) 매퍼. SQL 은 {@code mapper/contract/ContractMapper.xml}.
 */
public interface ContractMapper {

    /** 증권번호로 계약을 조회한다. */
    Optional<Contract> findById(String contractNo);

    /**
     * 증권번호로 계약 원장을 조회하면서 행을 잠근다(SELECT ... FOR UPDATE). 같은 계약의 상태 변경이 동시에 들어와도
     * 한 번에 하나씩 처리되도록 하여, 두 요청이 같은 현재 상태를 보고 서로 다른 상태로 덮어쓰는 일을 막는다.
     * 잠금은 호출한 트랜잭션이 끝날 때까지 유지되므로 반드시 트랜잭션 안에서 호출해야 한다.
     */
    Optional<Contract> findForUpdateByContractNo(String contractNo);

    /** 청약 접수 거래 UUID 로 계약을 조회한다. 동일 거래의 재전송 여부를 판별하는 데 사용한다. */
    Optional<Contract> findByGlobalTxId(String globalTxId);

    /** 청약번호로 계약 원장을 조회한다. */
    Optional<Contract> findByApplNo(String applNo);

    /** 원 계약을 갱신해 만든 계약 중 주어진 상태가 아닌 것(청약취소 제외)을 하나 찾는다. 이미 갱신했는지 확인하는 데 쓴다. */
    Optional<Contract> findFirstByRenewedFromAndCtrStatCdNot(@Param("renewedFrom") String renewedFrom,
                                                             @Param("ctrStatCd") String ctrStatCd);

    /** 조건에 맞는 계약 한 페이지. 정렬 컬럼과 방향은 서비스가 허용 목록으로 검증한 값만 넘긴다. */
    List<Contract> search(@Param("c") ContractSearchCondition condition, @Param("page") PageQuery page);

    /** {@link #search} 와 같은 조건의 전체 건수 */
    long countSearch(@Param("c") ContractSearchCondition condition);

    /** 전체 계약 건수 */
    long count();

    /** 새 계약을 넣는다. 판매채널이 비어 있으면 대면(10)으로 채운다. */
    default void insert(Contract contract) {
        contract.defaultSalesChannel();
        insertRow(contract);
    }

    /** {@link #insert} 가 쓰는 INSERT 문. 직접 부르지 않는다. */
    void insertRow(Contract contract);

    /** 바뀔 수 있는 컬럼(상태, 계약자, 피보험자, 해지일, 환급보험료)을 저장한다. 행을 잠근 뒤 부른다. */
    int update(Contract contract);

    /** 증권번호로 계약을 지운다 (시험 정리용) */
    int deleteById(String contractNo);
}
