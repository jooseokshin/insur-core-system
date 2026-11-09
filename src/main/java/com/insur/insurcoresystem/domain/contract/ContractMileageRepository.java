package com.insur.insurcoresystem.domain.contract;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractMileageRepository extends JpaRepository<ContractMileage, String> {

    /** 같은 거래 UUID 로 시작 등록한 마일리지 (재전송 판별) */
    Optional<ContractMileage> findByStartTxId(String startTxId);

    /** 같은 거래 UUID 로 정산한 마일리지 (재전송 판별) */
    Optional<ContractMileage> findByEndTxId(String endTxId);
}
