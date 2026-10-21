package com.insur.insurcoresystem.service.contract;

import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidAmount;
import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidCode;
import static com.insur.insurcoresystem.service.contract.ContractSupport.isConstraintViolation;
import static com.insur.insurcoresystem.service.contract.ContractSupport.isReadable;
import static com.insur.insurcoresystem.service.contract.ContractSupport.normalizeEmpId;
import static com.insur.insurcoresystem.service.contract.ContractSupport.pageRequest;
import static com.insur.insurcoresystem.service.contract.ContractSupport.validateGlobalTxId;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.web.LogSanitizer;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractPayment;
import com.insur.insurcoresystem.domain.contract.ContractPaymentRepository;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import com.insur.insurcoresystem.service.contract.dto.PaymentHistoryRequest;
import com.insur.insurcoresystem.service.contract.dto.PaymentHistoryResponse;
import com.insur.insurcoresystem.service.contract.dto.PaymentRequest;
import com.insur.insurcoresystem.service.contract.dto.PaymentResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계약 납입(수납) 서비스. 청약 상태의 계약에 보험료를 나누어 낼 수 있고, 낸 합계가 총보험료에 이르러야 승인(유효)할 수 있다
 * (승인 규칙은 {@link ContractStatusService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractPaymentService {

    /** 거래 UUID 유니크 제약명 (V18 마이그레이션) */
    private static final String GLOBAL_TX_ID_CONSTRAINT = "uk_ctr_payment_global_tx_id";

    private static final Set<String> METHODS = Set.of(
            ContractPayment.METHOD_CASH, ContractPayment.METHOD_TRANSFER, ContractPayment.METHOD_CARD);

    private final ContractRepository contractRepository;
    private final ContractPaymentRepository paymentRepository;
    /** 업무 시간대(Asia/Seoul) 기준 현재 시각 */
    private final Clock clock;

    /**
     * 납입을 한 건 접수한다. 계약 행을 잠근 채로 남은 보험료를 계산하므로 같은 계약에 대한 동시 납입이 초과 납입되지 않는다.
     * 같은 거래 UUID 로 이미 접수한 납입이 다시 들어오면(응답을 못 받은 재전송) 새로 저장하지 않고 처음 결과를 돌려주며,
     * 내용(증권번호, 금액, 납부 방법)이 다르면 거절한다.
     *
     * @param globalTxId 거래 UUID (멱등성 키)
     * @param empId      수납 처리 사번
     * @param request    증권번호, 납입 금액, 납부 방법
     * @return 이번 납입과 납입 현황(낸 합계, 남은 보험료, 완납 여부)
     * @throws BusinessException 거래 UUID 오류(E-CTR-008), 증권번호 누락(E-CTR-009), 금액 형식 오류(E-CTR-007),
     *                           계약 없음(E-CTR-010), 같은 거래 UUID 의 동시 처리 충돌(E-CTR-011), 다른 사용자의 거래 UUID(E-CTR-015),
     *                           같은 거래 UUID 로 다른 내용(E-CTR-019), 청약 상태가 아님(E-CTR-026),
     *                           남은 보험료 초과(E-CTR-027), 지원하지 않는 납부 방법(E-CTR-029)
     */
    @Transactional
    public PaymentResponse pay(String globalTxId, String empId, PaymentRequest request) {
        validateGlobalTxId(globalTxId);
        if (isInvalidCode(request.getContractNo(), 30)) {
            throw new BusinessException("E-CTR-009", "납입할 계약의 증권번호를 입력해야 합니다.");
        }
        if (isInvalidAmount(request.getPayAmt()) || request.getPayAmt().signum() <= 0) {
            throw new BusinessException("E-CTR-007", "납입 금액은 0보다 큰 15자리 이하 정수여야 합니다.");
        }
        if (request.getPayMethod() == null || !METHODS.contains(request.getPayMethod())) {
            throw new BusinessException("E-CTR-029", "납부 방법은 CASH, TRANSFER, CARD 중 하나여야 합니다.");
        }

        var done = paymentRepository.findByGlobalTxId(globalTxId);
        if (done.isPresent()) {
            return replay(done.get(), empId, request);
        }

        Contract contract = contractRepository.findForUpdateByContractNo(request.getContractNo())
                .orElseThrow(ContractSupport::contractNotFound);
        if (!ContractStatus.APPLIED.getCode().equals(contract.getCtrStatCd())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-026", "청약(10) 상태의 계약만 납입할 수 있습니다.");
        }
        BigDecimal remaining = contract.getTotalPremium().subtract(paymentRepository.sumPaid(contract.getContractNo()));
        if (request.getPayAmt().compareTo(remaining) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-027", "납입 금액이 남은 보험료를 넘습니다. (남은 보험료 " + remaining.toPlainString() + "원)");
        }

        ContractPayment saved;
        try {
            saved = paymentRepository.saveAndFlush(ContractPayment.builder()
                    .contractNo(contract.getContractNo())
                    .payAmt(request.getPayAmt())
                    .payMethod(request.getPayMethod())
                    .empId(normalizeEmpId(empId))
                    .globalTxId(globalTxId)
                    .paidDt(LocalDateTime.now(clock))
                    .build());
        } catch (DataIntegrityViolationException e) {
            if (!isConstraintViolation(e, GLOBAL_TX_ID_CONSTRAINT)) {
                throw e;
            }
            log.warn("같은 거래의 동시 납입이 충돌했다: globalTxId={}", LogSanitizer.clean(globalTxId));
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-011",
                    "같은 거래(globalTxId)가 동시에 처리 중입니다. 잠시 후 같은 globalTxId 로 다시 요청해 주세요.");
        }
        log.info("납입 접수: 증권번호={}, 금액={}, 방법={}", contract.getContractNo(), saved.getPayAmt(), saved.getPayMethod());
        return toResponse(saved, contract);
    }

    /**
     * 계약의 납입 내역을 페이지 단위로 조회한다. 오래된 납입부터 돌려주며, 납입 현황(낸 합계, 남은 보험료)은 전체 기준이다.
     * 계약 조회와 같은 조회 범위 규칙을 따른다.
     *
     * @param ownerEmpId 조회를 접수자 본인의 계약으로 제한할 사번. null 이면 제한 없이 조회한다
     * @param request    증권번호, 페이지 번호, 페이지 크기
     * @return 해당 페이지의 납입 항목과 납입 현황
     * @throws BusinessException 증권번호 누락(E-CTR-009), 페이징 값 오류(E-CTR-016), 계약 없음 또는 조회 권한 밖(E-CTR-010)
     */
    @Transactional(readOnly = true)
    public PaymentHistoryResponse history(String ownerEmpId, PaymentHistoryRequest request) {
        if (isInvalidCode(request.getContractNo(), 30)) {
            throw new BusinessException("E-CTR-009", "증권번호를 입력해야 합니다.");
        }
        Pageable pageable = pageRequest(request.getPage(), request.getSize(), Sort.by("paymentId").ascending());
        Contract contract = contractRepository.findById(request.getContractNo())
                .filter(found -> isReadable(found, ownerEmpId))
                .orElseThrow(ContractSupport::contractNotFound);

        Page<ContractPayment> result = paymentRepository.findByContractNo(contract.getContractNo(), pageable);
        BigDecimal paid = paymentRepository.sumPaid(contract.getContractNo());
        return PaymentHistoryResponse.builder()
                .contractNo(contract.getContractNo())
                .totalPremium(contract.getTotalPremium())
                .paidPremium(paid)
                .remainingPremium(contract.getTotalPremium().subtract(paid))
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .items(result.getContent().stream().map(p -> PaymentHistoryResponse.Item.builder()
                        .paymentId(p.getPaymentId()).payAmt(p.getPayAmt()).payMethod(p.getPayMethod())
                        .empId(p.getEmpId()).paidDt(p.getPaidDt()).build()).toList())
                .build();
    }

    /** 이미 접수한 거래 UUID 의 재전송: 사번과 내용이 처음과 같으면 처음 결과를, 아니면 오류를 돌려준다. */
    private PaymentResponse replay(ContractPayment first, String empId, PaymentRequest request) {
        if (first.getEmpId() != null && !first.getEmpId().equals(normalizeEmpId(empId))) {
            log.warn("다른 사용자가 접수한 거래 UUID 로 납입 재전송이 들어왔다: 접수 사번={}, 요청 사번={}",
                    LogSanitizer.clean(first.getEmpId()), LogSanitizer.clean(empId));
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-015", "이미 다른 사용자가 사용한 거래 UUID(globalTxId)입니다.");
        }
        if (!first.getContractNo().equals(request.getContractNo())
                || first.getPayAmt().compareTo(request.getPayAmt()) != 0
                || !first.getPayMethod().equals(request.getPayMethod())) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-CTR-019",
                    "같은 거래 UUID(globalTxId)로 이전과 다른 내용의 납입이 들어왔습니다. 새 거래 UUID 로 다시 요청해 주세요.");
        }
        Contract contract = contractRepository.findById(first.getContractNo()).orElseThrow(ContractSupport::contractNotFound);
        log.info("이미 접수한 납입의 재전송이므로 기존 결과를 반환한다: 증권번호={}", contract.getContractNo());
        return toResponse(first, contract);
    }

    /** 납입 한 건과 계약의 현재 납입 현황으로 응답을 만든다. */
    private PaymentResponse toResponse(ContractPayment payment, Contract contract) {
        BigDecimal paid = paymentRepository.sumPaid(contract.getContractNo());
        BigDecimal remaining = contract.getTotalPremium().subtract(paid);
        return PaymentResponse.builder()
                .contractNo(contract.getContractNo())
                .paymentId(payment.getPaymentId())
                .payAmt(payment.getPayAmt())
                .payMethod(payment.getPayMethod())
                .paidDt(payment.getPaidDt())
                .totalPremium(contract.getTotalPremium())
                .paidPremium(paid)
                .remainingPremium(remaining)
                .fullyPaid(remaining.signum() == 0)
                .build();
    }
}
