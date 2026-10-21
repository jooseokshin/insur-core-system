package com.insur.insurcoresystem.service.contract;

import static com.insur.insurcoresystem.service.contract.ContractSupport.validateGlobalTxId;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractPaymentRepository;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistory;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistoryRepository;
import com.insur.insurcoresystem.service.contract.dto.ContractStatusChangeRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractStatusChangeResponse;
import com.insur.insurcoresystem.service.premium.RefundCalculator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 계약상태 변경 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractStatusService {

    private final ContractRepository contractRepository;
    private final ContractStatusHistoryRepository historyRepository;
    /** 승인(유효) 전에 납입 완료 여부를 확인하는 데 쓴다 */
    private final ContractPaymentRepository paymentRepository;
    /** 상태 변경 이력 기록 */
    private final ContractHistoryRecorder historyRecorder;
    /** 업무 시간대(Asia/Seoul) 기준 현재 시각 */
    private final Clock clock;

    /**
     * 계약상태를 변경한다. 계약 행을 잠근 채로 현재 상태에서 요청한 상태로의 전이가 허용되는지 확인한 뒤 변경하므로,
     * 같은 계약에 대한 동시 변경 요청은 한 번에 하나씩 처리된다. 허용되는 전이는 {@link ContractStatus} 를 따른다.
     *
     * 승인(유효, 20)은 납입 합계가 총보험료에 이르러야 한다(E-CTR-028, 409). 납입은 {@link ContractPaymentService} 로 접수한다.
     * 변경이 성공하면 같은 트랜잭션에서 변경 이력(변경 전/후 상태, 처리 사번, 거래 UUID)을 남긴다.
     * 같은 거래 UUID 로 이미 처리한 상태 변경이 다시 들어오면(응답을 못 받은 재전송) 다시 바꾸지 않고 처음 결과를 돌려준다.
     * 같은 거래 UUID 로 다른 상태를 요청하면 거절한다(E-CTR-019, 409).
     * 해지(30)로 바꾸는 경우에는 해지일(업무 시간대의 오늘)과 남은 보험기간에 비례한 환급보험료({@link RefundCalculator})도 계약에 기록하고 응답에 담는다.
     *
     * @param globalTxId 거래 UUID (이력에 기록)
     * @param empId      처리 사번 (이력에 기록)
     * @param request    변경 요청 (증권번호, 변경할 계약상태코드)
     * @return 증권번호와 변경 전/후 계약상태코드
     * @throws BusinessException 증권번호/상태코드 누락(E-CTR-012), 지원하지 않는 상태코드(E-CTR-013),
     *                           거래 UUID 오류(E-CTR-008), 계약 없음(E-CTR-010, 404), 허용되지 않는 상태 변경(E-CTR-014, 409),
     *                           납입이 끝나지 않은 승인(E-CTR-028, 409),
     *                           같은 거래 UUID 로 다른 상태 요청(E-CTR-019, 409)
     */
    @Transactional
    public ContractStatusChangeResponse changeContractStatus(String globalTxId, String empId,
                                                             ContractStatusChangeRequest request) {
        if (!StringUtils.hasText(request.getContractNo()) || !StringUtils.hasText(request.getCtrStatCd())) {
            throw new BusinessException("E-CTR-012", "증권번호와 변경할 계약상태코드는 필수입니다.");
        }
        ContractStatus next = ContractStatus.fromCode(request.getCtrStatCd());
        validateGlobalTxId(globalTxId);

        Contract contract = contractRepository.findForUpdateByContractNo(request.getContractNo())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "E-CTR-010", "해당 계약이 존재하지 않습니다."));

        // 응답을 못 받은 클라이언트가 같은 거래 UUID 로 다시 보낸 경우: 이미 처리한 결과를 그대로 돌려준다 (멱등성)
        Optional<ContractStatusHistory> done = historyRepository
                .findFirstByContractNoAndGlobalTxIdAndBeforeStatCdIsNotNullOrderByHistIdAsc(
                        contract.getContractNo(), globalTxId);
        if (done.isPresent()) {
            ContractStatusHistory hist = done.get();
            if (!hist.getAfterStatCd().equals(next.getCode())) {
                throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-019",
                        "같은 거래 UUID(globalTxId)로 이미 다른 상태 변경이 처리되었습니다. 새 거래 UUID 로 다시 요청해 주세요.");
            }
            log.info("이미 처리한 상태 변경의 재전송이므로 기존 결과를 반환한다: 증권번호={}, {} -> {}",
                    contract.getContractNo(), hist.getBeforeStatCd(), hist.getAfterStatCd());
            boolean terminated = next == ContractStatus.TERMINATED;
            return ContractStatusChangeResponse.builder()
                    .contractNo(contract.getContractNo())
                    .beforeCtrStatCd(hist.getBeforeStatCd())
                    .afterCtrStatCd(hist.getAfterStatCd())
                    .termDate(terminated ? contract.getTermDate() : null)
                    .refundPremium(terminated ? contract.getRefundPremium() : null)
                    .build();
        }

        ContractStatus current = ContractStatus.fromCode(contract.getCtrStatCd());
        if (!current.canChangeTo(next)) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-014",
                    "계약상태를 " + current.getCode() + "에서 " + next.getCode() + "(으)로 변경할 수 없습니다.");
        }

        if (next == ContractStatus.IN_FORCE) {
            BigDecimal paid = paymentRepository.sumPaid(contract.getContractNo());
            if (paid.compareTo(contract.getTotalPremium()) < 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-028", "보험료 납입이 끝나야 승인(유효)할 수 있습니다. (납입 "
                        + paid.toPlainString() + "원 / 총보험료 " + contract.getTotalPremium().toPlainString() + "원)");
            }
        }

        contract.changeStatus(next);
        if (next == ContractStatus.TERMINATED) {
            LocalDate termDate = LocalDate.now(clock);
            contract.recordTermination(termDate, RefundCalculator.refund(
                    contract.getTotalPremium(), contract.getStartDate(), contract.getEndDate(), termDate));
        }
        historyRecorder.record(contract.getContractNo(), current, next, globalTxId, empId);
        log.info("계약상태 변경: 증권번호={}, {} -> {}", contract.getContractNo(), current.getCode(), next.getCode());
        return ContractStatusChangeResponse.builder()
                .contractNo(contract.getContractNo())
                .beforeCtrStatCd(current.getCode())
                .afterCtrStatCd(next.getCode())
                .termDate(contract.getTermDate())
                .refundPremium(contract.getRefundPremium())
                .build();
    }
}
