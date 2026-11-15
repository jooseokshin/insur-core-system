package com.insur.insurcoresystem.service.contract;

import com.insur.insurcoresystem.common.paging.PageQuery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractPayment;
import com.insur.insurcoresystem.mapper.contract.ContractPaymentMapper;
import com.insur.insurcoresystem.mapper.contract.ContractMapper;
import com.insur.insurcoresystem.service.contract.dto.PaymentHistoryRequest;
import com.insur.insurcoresystem.service.contract.dto.PaymentRequest;
import com.insur.insurcoresystem.service.contract.dto.PaymentResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ContractPaymentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String NO = "POL-CAR-2026-000001";

    @Mock
    ContractMapper contractMapper;
    @Mock
    ContractPaymentMapper paymentMapper;
    ContractPaymentService service;

    @BeforeEach
    void setUp() {
        service = new ContractPaymentService(contractMapper, paymentMapper, CLOCK);
    }

    private static Contract contract(String statCd, String total) {
        return Contract.builder().contractNo(NO).applNo("APP-1").insrClsCd("01").ctrStatCd(statCd)
                .totalPremium(new BigDecimal(total)).regEmpId("E100").build();
    }

    private static PaymentRequest request(String amt) {
        return PaymentRequest.builder().contractNo(NO).payAmt(new BigDecimal(amt)).payMethod("CASH").build();
    }

    private void assertError(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }

    @Test
    void 입력_오류는_계약을_조회하기_전에_거절한다() {
        assertError(() -> service.pay(" ", "O400", request("1000")), "E-CTR-008");
        assertError(() -> service.pay("tx", "O400", PaymentRequest.builder().payAmt(BigDecimal.TEN).payMethod("CASH").build()), "E-CTR-009");
        assertError(() -> service.pay("tx", "O400", request("0")), "E-CTR-007");
        assertError(() -> service.pay("tx", "O400", request("-1000")), "E-CTR-007");
        assertError(() -> service.pay("tx", "O400", request("1000.5")), "E-CTR-007");
        assertError(() -> service.pay("tx", "O400", PaymentRequest.builder().contractNo(NO).payMethod("CASH").build()), "E-CTR-007");
        assertError(() -> service.pay("tx", "O400", PaymentRequest.builder().contractNo(NO).payAmt(BigDecimal.TEN).payMethod("BITCOIN").build()), "E-CTR-029");
        assertError(() -> service.pay("tx", "O400", PaymentRequest.builder().contractNo(NO).payAmt(BigDecimal.TEN).build()), "E-CTR-029");
        verifyNoInteractions(contractMapper, paymentMapper);
    }

    @Test
    void 청약_상태의_계약에_납입하면_저장하고_납입_현황을_돌려준다() {
        given(paymentMapper.findByGlobalTxId("tx-1")).willReturn(Optional.empty());
        given(contractMapper.findForUpdateByContractNo(NO)).willReturn(Optional.of(contract("10", "500000")));
        given(paymentMapper.sumPaid(NO)).willReturn(new BigDecimal("100000"), new BigDecimal("300000"));
        org.mockito.BDDMockito.willAnswer(inv -> {   // 생성 키(납입 ID)를 채우는 insert 를 흉내 낸다
            org.springframework.test.util.ReflectionTestUtils.setField((Object) inv.getArgument(0), "paymentId", 7L);
            return null;
        }).given(paymentMapper).insert(any(ContractPayment.class));

        PaymentResponse response = service.pay("tx-1", "O400", request("200000"));

        ArgumentCaptor<ContractPayment> saved = ArgumentCaptor.forClass(ContractPayment.class);
        verify(paymentMapper).insert(saved.capture());
        assertThat(saved.getValue().getEmpId()).isEqualTo("O400");
        assertThat(saved.getValue().getPaidDt()).isEqualTo(LocalDateTime.of(2026, 10, 10, 0, 30));   // 서울 시각
        assertThat(response.getPaymentId()).isEqualTo(7L);
        assertThat(response.getPaidPremium()).isEqualByComparingTo("300000");
        assertThat(response.getRemainingPremium()).isEqualByComparingTo("200000");
        assertThat(response.isFullyPaid()).isFalse();
    }

    @Test
    void 남은_보험료를_정확히_채우면_완납이고_더_내면_거절한다() {
        given(paymentMapper.findByGlobalTxId(any())).willReturn(Optional.empty());
        given(contractMapper.findForUpdateByContractNo(NO)).willReturn(Optional.of(contract("10", "500000")));
        given(paymentMapper.sumPaid(NO)).willReturn(new BigDecimal("300000"), new BigDecimal("500000"));

        assertThat(service.pay("tx-full", "O400", request("200000")).isFullyPaid()).isTrue();

        given(paymentMapper.sumPaid(NO)).willReturn(new BigDecimal("300000"));
        assertThatThrownBy(() -> service.pay("tx-over", "O400", request("200001")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("E-CTR-027");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("200000");
                });
    }

    @Test
    void 청약_상태가_아니거나_없는_계약에는_납입할_수_없다() {
        given(paymentMapper.findByGlobalTxId(any())).willReturn(Optional.empty());
        given(contractMapper.findForUpdateByContractNo(NO)).willReturn(Optional.of(contract("20", "500000")));
        assertError(() -> service.pay("tx-2", "O400", request("1000")), "E-CTR-026");
        given(contractMapper.findForUpdateByContractNo("POL-NONE")).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.pay("tx-3", "O400",
                PaymentRequest.builder().contractNo("POL-NONE").payAmt(BigDecimal.TEN).payMethod("CARD").build()))
                .extracting("errorCode").isEqualTo("E-CTR-010");
        verify(paymentMapper, never()).insert(any());
    }

    @Test
    void 같은_거래UUID_재전송은_저장하지_않고_처음_결과를_돌려주고_다른_내용이나_사번은_거절한다() {
        ContractPayment first = ContractPayment.builder().paymentId(7L).contractNo(NO).payAmt(new BigDecimal("200000"))
                .payMethod("CASH").empId("O400").globalTxId("tx-1").paidDt(LocalDateTime.of(2026, 10, 10, 0, 30)).build();
        given(paymentMapper.findByGlobalTxId("tx-1")).willReturn(Optional.of(first));
        given(contractMapper.findById(NO)).willReturn(Optional.of(contract("10", "500000")));
        given(paymentMapper.sumPaid(NO)).willReturn(new BigDecimal("200000"));

        assertThat(service.pay("tx-1", "O400", request("200000")).getPaymentId()).isEqualTo(7L);   // 금액 스케일이 달라도 같은 값
        verify(paymentMapper, never()).insert(any());
        assertError(() -> service.pay("tx-1", "O400", request("100000")), "E-CTR-019");
        assertError(() -> service.pay("tx-1", "O400", PaymentRequest.builder().contractNo(NO)
                .payAmt(new BigDecimal("200000")).payMethod("CARD").build()), "E-CTR-019");
        assertError(() -> service.pay("tx-1", "X999", request("200000")), "E-CTR-015");
    }

    @Test
    void 같은_거래UUID가_동시에_저장되어_유니크_제약에_걸리면_409_충돌_오류다() {
        given(paymentMapper.findByGlobalTxId(any())).willReturn(Optional.empty());
        given(contractMapper.findForUpdateByContractNo(NO)).willReturn(Optional.of(contract("10", "500000")));
        given(paymentMapper.sumPaid(NO)).willReturn(BigDecimal.ZERO);
        willThrow(violation("public.uk_ctr_payment_global_tx_id")).given(paymentMapper).insert(any(ContractPayment.class));
        assertThatThrownBy(() -> service.pay("tx-c", "O400", request("1000")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("E-CTR-011");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        willThrow(violation("ck_ctr_payment_amt")).given(paymentMapper).insert(any(ContractPayment.class));
        assertThatThrownBy(() -> service.pay("tx-d", "O400", request("1000"))).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                com.insur.insurcoresystem.support.PgErrors.uniqueViolation("x", constraint));
    }

    @Test
    void 납입_내역_조회의_입력_오류와_조회_범위() {
        assertError(() -> service.history(null, PaymentHistoryRequest.builder().build()), "E-CTR-009");
        assertError(() -> service.history(null, PaymentHistoryRequest.builder().contractNo(NO).size(101).build()), "E-CTR-016");
        given(contractMapper.findById(NO)).willReturn(Optional.of(contract("10", "500000")));
        assertError(() -> service.history("E200", PaymentHistoryRequest.builder().contractNo(NO).build()), "E-CTR-010");   // 남의 계약

        given(paymentMapper.findPageByContractNo(any(), any(PageQuery.class))).willReturn(List.of());
        given(paymentMapper.sumPaid(NO)).willReturn(BigDecimal.ZERO);
        var response = service.history("E100", PaymentHistoryRequest.builder().contractNo(NO).build());   // 본인 접수
        assertThat(response.getItems()).isEmpty();
        assertThat(response.getRemainingPremium()).isEqualByComparingTo("500000");
        assertThat(response.getSize()).isEqualTo(20);
    }
}
