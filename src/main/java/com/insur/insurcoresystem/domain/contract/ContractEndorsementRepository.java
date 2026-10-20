package com.insur.insurcoresystem.domain.contract;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractEndorsementRepository extends JpaRepository<ContractEndorsement, Long> {

    /**
     * 계약의 배서 이력을 페이지 단위로 조회한다. 정렬은 호출자가 Pageable 로 지정한다.
     *
     * @param contractNo 증권번호
     * @param pageable   페이지 번호, 크기, 정렬
     * @return 요청한 페이지의 배서 항목과 전체 건수
     */
    Page<ContractEndorsement> findByContractNo(String contractNo, Pageable pageable);

    /**
     * 같은 계약에서 같은 거래 UUID 로 이미 처리한 배서 항목을 발생 순서대로 찾는다. 응답을 못 받은 클라이언트의 재전송을 가려내는 데 쓴다.
     *
     * @param contractNo 증권번호
     * @param globalTxId 거래 UUID
     * @return 그 거래로 바뀐 항목들. 없으면 빈 목록
     */
    List<ContractEndorsement> findByContractNoAndGlobalTxIdOrderByEndorseIdAsc(String contractNo, String globalTxId);
}
