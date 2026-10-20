package com.insur.insurcoresystem.domain.contract;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

/**
 * 계약 원장 Repository. 목록 조회의 선택 조건은 Specification 으로 조합한다(JpaSpecificationExecutor).
 */
public interface ContractRepository extends JpaRepository<Contract, String>, JpaSpecificationExecutor<Contract> {

    /**
     * 청약 접수 거래 UUID 로 계약을 조회한다. 동일 거래의 재전송 여부를 판별하는 데 사용한다.
     *
     * @param globalTxId 청약 요청 전문의 거래 UUID
     * @return 해당 거래로 이미 접수된 계약. 없으면 빈 Optional
     */
    Optional<Contract> findByGlobalTxId(String globalTxId);

    /**
     * 증권번호로 계약 원장을 조회하면서 행을 잠근다(SELECT ... FOR UPDATE). 같은 계약의 상태 변경이 동시에 들어와도
     * 한 번에 하나씩 처리되도록 하여, 두 요청이 같은 현재 상태를 보고 서로 다른 상태로 덮어쓰는 일을 막는다.
     * 잠금은 호출한 트랜잭션이 끝날 때까지 유지되므로 반드시 트랜잭션 안에서 호출해야 한다.
     *
     * @param contractNo 증권번호
     * @return 잠금이 걸린 계약. 없으면 빈 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Contract> findForUpdateByContractNo(String contractNo);

    /**
     * 청약번호로 계약 원장을 조회한다.
     *
     * @param applNo 청약번호
     * @return 해당 청약번호의 계약. 없으면 빈 Optional
     */
    Optional<Contract> findByApplNo(String applNo);

    /**
     * 원 계약을 갱신해 만든 계약 중 청약취소가 아닌 것을 찾는다. 이미 갱신했는지 확인하는 데 쓴다.
     *
     * @param renewedFrom 원 계약의 증권번호
     * @return 유효한 갱신 계약. 없으면 빈 Optional
     */
    Optional<Contract> findFirstByRenewedFromAndCtrStatCdNot(String renewedFrom, String ctrStatCd);
}
