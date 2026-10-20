package com.insur.insurcoresystem.service.contract;

import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;

import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistory;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistoryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 계약상태 변경 이력 기록기. 청약 접수(최초 상태)와 상태 변경이 같은 규칙으로 이력을 남기도록 한 곳에 둔다.
 */
@Component
@RequiredArgsConstructor
public class ContractHistoryRecorder {

    private final ContractStatusHistoryRepository historyRepository;
    /** 업무 시간대(Asia/Seoul) 기준 현재 시각 */
    private final Clock clock;

    /**
     * 계약상태 변경 이력을 한 건 남긴다. (청약 접수와 상태 변경이 함께 쓴다) 호출자 트랜잭션에 참여하므로 변경이 롤백되면 이력도 함께 롤백된다.
     * 처리 사번은 컬럼 길이(64)를 넘으면 잘라서 저장한다. (이력 저장 때문에 업무 처리가 실패하지 않도록)
     *
     * @param before 변경 전 상태. 청약 접수(최초 상태)는 null
     */
    public void record(String contractNo, ContractStatus before, ContractStatus after,
                               String globalTxId, String empId) {
        historyRepository.save(ContractStatusHistory.builder()
                .contractNo(contractNo)
                .beforeStatCd(before == null ? null : before.getCode())
                .afterStatCd(after.getCode())
                .empId(normalizeEmpId(empId))
                .globalTxId(globalTxId)
                .chgDt(LocalDateTime.now(clock))
                .build());
    }
}
