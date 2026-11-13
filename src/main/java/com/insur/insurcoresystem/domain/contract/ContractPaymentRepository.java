package com.insur.insurcoresystem.domain.contract;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractPaymentRepository extends JpaRepository<ContractPayment, Long> {

    /**
     * 계약의 납입 내역을 페이지 단위로 조회한다. 정렬은 호출자가 Pageable 로 지정한다.
     *
     * @param contractNo 증권번호
     * @param pageable   페이지 번호, 크기, 정렬
     * @return 요청한 페이지의 납입 내역과 전체 건수
     */
    Page<ContractPayment> findByContractNo(String contractNo, Pageable pageable);

    /**
     * 같은 거래 UUID 로 이미 접수한 납입을 찾는다. 응답을 못 받은 클라이언트의 재전송을 가려내는 데 쓴다.
     *
     * @param globalTxId 거래 UUID
     * @return 처음 접수한 납입. 없으면 빈 Optional
     */
    Optional<ContractPayment> findByGlobalTxId(String globalTxId);

    /**
     * 계약에 지금까지 납입한 금액의 합계를 구한다.
     *
     * @param contractNo 증권번호
     * @return 납입 합계. 납입이 없으면 0
     */
    @Query("select coalesce(sum(p.payAmt), 0) from ContractPayment p where p.contractNo = :contractNo")
    BigDecimal sumPaid(@Param("contractNo") String contractNo);

    /**
     * 진행 중인 자동이체 출금(의뢰 대상·결과 대기) 건수. 진행 중이면 결과가 오기 전에 수기 수납을 받으면 과납이 될 수 있다.
     *
     * @param contractNo 증권번호
     * @return 진행 중인 출금 건수
     */
    @Query(value = "SELECT COUNT(*) FROM TB_CMS_DEBIT WHERE CONTRACT_NO = :contractNo AND STAT_CD IN ('10', '20')", nativeQuery = true)
    long countDebitsInFlight(@Param("contractNo") String contractNo);
}
