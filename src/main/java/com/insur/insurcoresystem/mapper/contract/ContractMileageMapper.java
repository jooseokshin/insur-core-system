package com.insur.insurcoresystem.mapper.contract;

import com.insur.insurcoresystem.domain.contract.ContractMileage;
import java.util.Optional;

/**
 * 마일리지 특약 주행거리(TB_CTR_MILEAGE) 매퍼.
 */
public interface ContractMileageMapper {

    Optional<ContractMileage> findById(String contractNo);

    /** 등록 거래 UUID 로 조회 (등록 재전송 판별) */
    Optional<ContractMileage> findByStartTxId(String startTxId);

    /** 정산 거래 UUID 로 조회 (정산 재전송 판별) */
    Optional<ContractMileage> findByEndTxId(String endTxId);

    boolean existsById(String contractNo);

    /** 시작 주행거리 등록 */
    void insert(ContractMileage mileage);

    /** 정산 결과(종료 주행거리, 환급률·환급액, 상태) 저장 */
    int updateSettlement(ContractMileage mileage);
}
