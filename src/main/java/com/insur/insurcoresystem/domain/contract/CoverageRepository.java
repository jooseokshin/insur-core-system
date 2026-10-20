package com.insur.insurcoresystem.domain.contract;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoverageRepository extends JpaRepository<Coverage, Long> {

    /**
     * 증권번호에 속한 계약 담보 목록을 조회한다.
     *
     * @param contractNo 증권번호
     * @return 해당 계약의 담보 목록. 없으면 빈 목록
     */
    List<Coverage> findByContractContractNo(String contractNo);
}
