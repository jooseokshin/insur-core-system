package com.insur.insurcoresystem.domain.contract;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractStatusHistoryRepository extends JpaRepository<ContractStatusHistory, Long> {

    /**
     * 계약의 상태 변경 이력을 페이지 단위로 조회한다. 정렬은 호출자가 Pageable 로 지정한다.
     *
     * @param contractNo 증권번호
     * @param pageable   페이지 번호, 크기, 정렬
     * @return 요청한 페이지의 이력과 전체 건수
     */
    Page<ContractStatusHistory> findByContractNo(String contractNo, Pageable pageable);

    /**
     * 같은 계약에서 같은 거래 UUID 로 이미 처리한 상태 변경을 찾는다. 응답을 못 받은 클라이언트의 재전송을 가려내는 데 쓴다.
     * 청약 접수 기록(변경 전 상태 없음)은 상태 변경이 아니므로 제외한다.
     *
     * @param contractNo 증권번호
     * @param globalTxId 거래 UUID
     * @return 처음 처리한 상태 변경 이력. 없으면 빈 Optional
     */
    Optional<ContractStatusHistory> findFirstByContractNoAndGlobalTxIdAndBeforeStatCdIsNotNullOrderByHistIdAsc(
            String contractNo, String globalTxId);
}
