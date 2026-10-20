package com.insur.insurcoresystem.service.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractEndorsement;
import com.insur.insurcoresystem.domain.contract.ContractEndorsementRepository;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.service.contract.dto.ContractEndorseRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractEndorseResponse;
import com.insur.insurcoresystem.service.contract.dto.EndorsementHistoryPageResponse;
import com.insur.insurcoresystem.service.contract.dto.EndorsementHistoryRequest;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ContractEndorsementServiceTest {

    private static final Clock SEOUL_CLOCK = Clock.fixed(Instant.parse("2026-10-09T15:30:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    ContractRepository contractRepository;
    @Mock
    ContractEndorsementRepository endorsementRepository;
    ContractEndorsementService service;

    @BeforeEach
    void setUp() {
        service = new ContractEndorsementService(contractRepository, endorsementRepository, SEOUL_CLOCK);
    }

    private static Contract contract(String status) {
        return Contract.builder().contractNo("POL-1").applNo("APP-1").ctrStatCd(status)
                .policyholderId("C0001").insuredId("C0002").regEmpId("E1").build();
    }

    private static ContractEndorseRequest request(String policyholderId, String insuredId) {
        return ContractEndorseRequest.builder().contractNo("POL-1").policyholderId(policyholderId).insuredId(insuredId).build();
    }

    @Test
    void 계약자와_피보험자를_바꾸고_바뀐_항목마다_이력을_남긴다() {
        Contract contract = contract("20");
        given(contractRepository.findForUpdateByContractNo("POL-1")).willReturn(Optional.of(contract));

        ContractEndorseResponse response = service.endorse("tx-1", "U300", request("C9001", "C9002"));

        assertThat(contract.getPolicyholderId()).isEqualTo("C9001");
        assertThat(contract.getInsuredId()).isEqualTo("C9002");
        assertThat(response.getChanges()).extracting("fieldNm").containsExactly("POLICYHOLDER_ID", "INSURED_ID");
        assertThat(response.getChanges().get(0).getBeforeVal()).isEqualTo("C0001");
        assertThat(response.getChanges().get(0).getAfterVal()).isEqualTo("C9001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContractEndorsement>> captor = ArgumentCaptor.forClass(List.class);
        verify(endorsementRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        ContractEndorsement first = captor.getValue().get(0);
        assertThat(first.getContractNo()).isEqualTo("POL-1");
        assertThat(first.getEmpId()).isEqualTo("U300");
        assertThat(first.getGlobalTxId()).isEqualTo("tx-1");
        assertThat(first.getChgDt()).isEqualTo(LocalDateTime.of(2026, 10, 10, 0, 30));   // 서울 시각
    }

    @Test
    void 현재_값과_같은_항목은_바뀐_것으로_보지_않고_바뀐_항목만_기록한다() {
        Contract contract = contract("10");
        given(contractRepository.findForUpdateByContractNo("POL-1")).willReturn(Optional.of(contract));

        ContractEndorseResponse response = service.endorse("tx-1", "U300", request("C0001", "C9002"));   // 계약자는 그대로

        assertThat(response.getChanges()).extracting("fieldNm").containsExactly("INSURED_ID");
        assertThat(contract.getPolicyholderId()).isEqualTo("C0001");
    }

    @Test
    void 바뀐_항목이_하나도_없으면_거절하고_이력을_남기지_않는다() {
        given(contractRepository.findForUpdateByContractNo("POL-1")).willReturn(Optional.of(contract("20")));

        assertThatThrownBy(() -> service.endorse("tx-1", "U300", request("C0001", "C0002")))
                .extracting("errorCode").isEqualTo("E-CTR-022");
        assertThatThrownBy(() -> service.endorse("tx-1", "U300", request(null, null)))
                .extracting("errorCode").isEqualTo("E-CTR-022");
        verify(endorsementRepository, never()).saveAll(anyList());
    }

    @Test
    void 같은_거래UUID로_이미_처리한_배서가_다시_오면_다시_바꾸지_않고_처음_결과를_돌려준다() {
        // 이미 계약자 C0001→C9001 이 처리된 계약(현재 값 C9001, 피보험자 C0002)에 같은 거래 UUID 로 같은 요청이 다시 들어온 상황
        Contract contract = contract("20");
        contract.changePolicyholder("C9001");
        given(contractRepository.findForUpdateByContractNo("POL-1")).willReturn(Optional.of(contract));
        given(endorsementRepository.findByContractNoAndGlobalTxIdOrderByEndorseIdAsc("POL-1", "tx-retry")).willReturn(List.of(
                ContractEndorsement.builder().fieldNm("POLICYHOLDER_ID").beforeVal("C0001").afterVal("C9001").build()));

        // 처음 요청과 같은 값 + 바뀌지 않았던 피보험자에 현재 값을 그대로 넣은 요청도 같은 요청이다
        ContractEndorseResponse response = service.endorse("tx-retry", "U300", request("C9001", "C0002"));

        assertThat(response.getChanges()).hasSize(1);
        assertThat(response.getChanges().get(0).getBeforeVal()).isEqualTo("C0001");
        assertThat(response.getPolicyholderId()).isEqualTo("C9001");
        verify(endorsementRepository, never()).saveAll(anyList());
    }

    @Test
    void 같은_거래UUID로_다른_내용의_배서가_오면_409로_거절한다() {
        Contract contract = contract("20");
        contract.changePolicyholder("C9001");
        given(contractRepository.findForUpdateByContractNo("POL-1")).willReturn(Optional.of(contract));
        given(endorsementRepository.findByContractNoAndGlobalTxIdOrderByEndorseIdAsc("POL-1", "tx-retry")).willReturn(List.of(
                ContractEndorsement.builder().fieldNm("POLICYHOLDER_ID").beforeVal("C0001").afterVal("C9001").build()));

        for (ContractEndorseRequest different : List.of(
                request("C7777", null),            // 처음에 바꾼 항목에 다른 값
                request(null, "C8888"),            // 처음에 바꾸지 않은 항목에 현재 값과 다른 값
                request(null, null))) {            // 처음에 바꾼 항목을 빠뜨림
            assertThatThrownBy(() -> service.endorse("tx-retry", "U300", different))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("E-CTR-019");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }
        verify(endorsementRepository, never()).saveAll(anyList());
    }

    @Test
    void 해지나_청약취소된_계약은_변경할_수_없다() {
        for (String status : List.of("30", "90")) {
            given(contractRepository.findForUpdateByContractNo("POL-1")).willReturn(Optional.of(contract(status)));

            assertThatThrownBy(() -> service.endorse("tx-1", "U300", request("C9001", null)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("E-CTR-021");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }
        verify(endorsementRepository, never()).saveAll(anyList());
    }

    @Test
    void 입력_오류와_없는_계약은_거절한다() {
        assertError(ContractEndorseRequest.builder().policyholderId("C1").build(), "tx-1", "E-CTR-012");
        assertError(request("C9001", null), " ", "E-CTR-008");
        assertError(request(" ", null), "tx-1", "E-CTR-005");
        assertError(request(null, "X".repeat(21)), "tx-1", "E-CTR-005");
        verifyNoInteractions(contractRepository);

        given(contractRepository.findForUpdateByContractNo("POL-1")).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.endorse("tx-1", "U300", request("C9001", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("E-CTR-010");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    private void assertError(ContractEndorseRequest request, String txId, String errorCode) {
        assertThatThrownBy(() -> service.endorse(txId, "U300", request))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(errorCode);
    }

    @Test
    void 배서_이력은_발생_순서로_페이지_단위로_조회한다() {
        given(contractRepository.findById("POL-1")).willReturn(Optional.of(contract("20")));
        ContractEndorsement item = ContractEndorsement.builder().fieldNm("INSURED_ID").beforeVal("C0002")
                .afterVal("C9002").empId("U300").chgDt(LocalDateTime.of(2026, 10, 10, 0, 30)).build();
        given(endorsementRepository.findByContractNo(eq("POL-1"), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        EndorsementHistoryPageResponse response = service.history(null,
                EndorsementHistoryRequest.builder().contractNo("POL-1").build());

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getAfterVal()).isEqualTo("C9002");
        assertThat(response.getTotalElements()).isEqualTo(1);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(endorsementRepository).findByContractNo(eq("POL-1"), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);                        // 기본 20건
        assertThat(captor.getValue().getSort().getOrderFor("endorseId").isAscending()).isTrue();
    }

    @Test
    void 배서_이력_조회의_입력_오류와_조회_범위를_검증한다() {
        given(contractRepository.findById("POL-1")).willReturn(Optional.of(contract("20")));

        assertHistoryError(null, EndorsementHistoryRequest.builder().build(), "E-CTR-009");
        assertHistoryError(null, EndorsementHistoryRequest.builder().contractNo("POL-1").size(101).build(), "E-CTR-016");
        assertHistoryError(null, EndorsementHistoryRequest.builder().contractNo("POL-1").page(-1).build(), "E-CTR-016");
        // 접수자(E1)가 아닌 사번은 존재하지 않는 계약과 같은 오류
        assertHistoryError("E2", EndorsementHistoryRequest.builder().contractNo("POL-1").build(), "E-CTR-010");
        verifyNoInteractions(endorsementRepository);
    }

    private void assertHistoryError(String owner, EndorsementHistoryRequest request, String errorCode) {
        assertThatThrownBy(() -> service.history(owner, request))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(errorCode);
    }
}
