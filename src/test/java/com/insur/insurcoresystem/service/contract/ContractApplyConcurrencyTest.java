package com.insur.insurcoresystem.service.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractPayment;
import com.insur.insurcoresystem.domain.contract.ContractPaymentRepository;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistoryRepository;
import com.insur.insurcoresystem.domain.contract.CoverageRepository;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractRenewRequest;
import com.insur.insurcoresystem.service.contract.dto.PaymentRequest;
import com.insur.insurcoresystem.service.contract.dto.PaymentResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractRenewResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractStatusChangeRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractStatusChangeResponse;
import com.insur.insurcoresystem.service.contract.dto.CoverageRequest;
import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;

/**
 * 같은 거래 UUID 의 동시 청약 검증. 서로 다른 트랜잭션으로 실제 커밋되므로 테스트 트랜잭션을 쓰지 않고,
 * 끝나면 만든 계약/담보를 직접 지운다. (채번 테이블의 일련번호는 되돌리지 않으므로 로컬 DB 의 번호가 하나 건너뛴다.)
 */
@SpringBootTest
class ContractApplyConcurrencyTest {

    private static final int THREADS = 6;

    @Autowired
    ContractService contractService;
    @Autowired
    ContractRenewalService renewalService;
    @Autowired
    ContractStatusService statusService;
    @Autowired
    ContractPaymentRepository paymentRepository;
    @Autowired
    ContractPaymentService paymentService;
    @Autowired
    ContractRepository contractRepository;
    @Autowired
    CoverageRepository coverageRepository;
    @Autowired
    ContractStatusHistoryRepository historyRepository;

    @Test
    void 같은_거래UUID가_동시에_들어오면_계약은_한_건만_저장되고_나머지는_기존_결과이거나_409다() throws Exception {
        String txId = "tx-conc-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return contractService.applyContract(txId, "E12345", request());
                    } catch (BusinessException e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();

            String contractNo = null;
            for (Future<Object> future : futures) {
                Object result = future.get();
                if (result instanceof ContractApplyResponse response) {
                    contractNo = contractNo == null ? response.getContractNo() : contractNo;
                    assertThat(response.getContractNo()).isEqualTo(contractNo);
                } else {
                    assertThat(((BusinessException) result).getErrorCode()).isEqualTo("E-CTR-011");
                }
            }

            Contract saved = contractRepository.findByGlobalTxId(txId).orElseThrow();
            assertThat(saved.getContractNo()).isEqualTo(contractNo);
            assertThat(coverageRepository.findByContractContractNo(contractNo)).hasSize(1);
        } finally {
            pool.shutdownNow();
            cleanup(txId);
        }
    }

    @Test
    void 같은_계약에_서로_다른_상태_변경이_동시에_들어오면_하나만_성공하고_나머지는_409다() throws Exception {
        String txId = "tx-conc-st-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            String contractNo = contractService.applyContract(txId, "E12345", request()).getContractNo();
            paymentRepository.save(ContractPayment.builder().contractNo(contractNo).payAmt(new BigDecimal("500000"))
                    .payMethod("CASH").empId("E12345").globalTxId(txId + "-pay").paidDt(java.time.LocalDateTime.now()).build());

            List<Future<Object>> futures = new ArrayList<>();
            for (String target : List.of("20", "90")) {   // 청약 -> 유효, 청약 -> 청약취소 (서로 배타적)
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return statusService.changeContractStatus(txId + "-" + target, "E12345", ContractStatusChangeRequest.builder()
                                .contractNo(contractNo).ctrStatCd(target).build());
                    } catch (BusinessException e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();

            List<ContractStatusChangeResponse> succeeded = new ArrayList<>();
            for (Future<Object> future : futures) {
                Object result = future.get();
                if (result instanceof ContractStatusChangeResponse response) {
                    succeeded.add(response);
                } else {
                    assertThat(((BusinessException) result).getErrorCode()).isEqualTo("E-CTR-014");
                }
            }

            assertThat(succeeded).hasSize(1);
            assertThat(succeeded.get(0).getBeforeCtrStatCd()).isEqualTo("10");
            assertThat(contractRepository.findById(contractNo).orElseThrow().getCtrStatCd())
                    .isEqualTo(succeeded.get(0).getAfterCtrStatCd());
        } finally {
            pool.shutdownNow();
            cleanup(txId);
        }
    }

    @Test
    void 같은_계약에_전액_납입이_서로_다른_거래로_동시에_들어오면_하나만_성공하고_나머지는_초과_납입으로_거절한다() throws Exception {
        String txId = "tx-conc-pay-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        String contractNo = contractService.applyContract(txId, "E12345", request()).getContractNo();
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String payTx = txId + "-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return paymentService.pay(payTx, "O400", PaymentRequest.builder().contractNo(contractNo)
                                .payAmt(new BigDecimal("500000")).payMethod("CASH").build());
                    } catch (BusinessException e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();

            int succeeded = 0;
            for (Future<Object> future : futures) {
                Object result = future.get();
                if (result instanceof PaymentResponse) {
                    succeeded++;
                } else {
                    assertThat(((BusinessException) result).getErrorCode()).isEqualTo("E-CTR-027");
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(paymentRepository.sumPaid(contractNo)).isEqualByComparingTo("500000");   // 초과 납입이 없다
        } finally {
            pool.shutdownNow();
            cleanup(txId);
        }
    }

    @Test
    void 같은_계약의_갱신이_서로_다른_거래로_동시에_들어오면_하나만_성공하고_나머지는_409다() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        LocalDate end = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(10);
        Contract source = contractRepository.save(Contract.builder()
                .contractNo("POL-C-" + tag).applNo("APP-C-" + tag).insrClsCd("01").ctrStatCd("20")
                .startDate(end.minusYears(1)).endDate(end).policyholderId("C0001").insuredId("C0002")
                .totalPremium(new BigDecimal("500000")).sysRegDt(java.time.LocalDateTime.now()).regEmpId("E12345")
                .driverAge(35).discountGrade("3").build());
        coverageRepository.save(com.insur.insurcoresystem.domain.contract.Coverage.builder().contract(source).cvrgCd("CA001")
                .isrdAmt(new BigDecimal("100000000")).prem(new BigDecimal("500000")).build());
        String txPrefix = "tx-conc-rn-" + tag;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String txId = txPrefix + "-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return renewalService.renew(null, txId, "E12345",
                                ContractRenewRequest.builder().contractNo(source.getContractNo()).build());
                    } catch (BusinessException e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();

            int succeeded = 0;
            for (Future<Object> future : futures) {
                Object result = future.get();
                if (result instanceof ContractRenewResponse) {
                    succeeded++;
                } else {
                    assertThat(((BusinessException) result).getErrorCode()).isEqualTo("E-CTR-025");
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(contractRepository.findFirstByRenewedFromAndCtrStatCdNot(source.getContractNo(), "90")).isPresent();
        } finally {
            pool.shutdownNow();
            for (int i = 0; i < 2; i++) {
                cleanup(txPrefix + "-" + i);
            }
            coverageRepository.deleteAll(coverageRepository.findByContractContractNo(source.getContractNo()));
            contractRepository.delete(source);
        }
    }

    /** 테스트가 커밋한 계약과 담보를 지운다. */
    private void cleanup(String txId) {
        contractRepository.findByGlobalTxId(txId).ifPresent(contract -> {
            paymentRepository.deleteAll(
                    paymentRepository.findByContractNo(contract.getContractNo(), Pageable.unpaged()).getContent());
            historyRepository.deleteAll(
                    historyRepository.findByContractNo(contract.getContractNo(), Pageable.unpaged()).getContent());
            coverageRepository.deleteAll(coverageRepository.findByContractContractNo(contract.getContractNo()));
            contractRepository.delete(contract);
        });
    }

    private static ContractApplyRequest request() {
        return ContractApplyRequest.builder()
                .insrClsCd("01")
                .startDate(LocalDate.of(2026, 10, 3))
                .endDate(LocalDate.of(2027, 10, 3))
                .policyholderId("C0001")
                .insuredId("C0002")
                .totalPremium(new BigDecimal("500000"))
                .rating(RatingInput.builder().driverAge(35).discountGrade("3").build())   // 필수 계수 (계수 1.0)
                .coverages(List.of(CoverageRequest.builder()
                        .cvrgCd("CA001")
                        .isrdAmt(new BigDecimal("100000000"))
                        .prem(new BigDecimal("500000"))
                        .build()))
                .build();
    }
}
