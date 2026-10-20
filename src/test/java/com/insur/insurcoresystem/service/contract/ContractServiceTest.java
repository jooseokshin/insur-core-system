package com.insur.insurcoresystem.service.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.contract.Contract;
import com.insur.insurcoresystem.domain.contract.ContractRepository;
import com.insur.insurcoresystem.domain.contract.Coverage;
import com.insur.insurcoresystem.domain.contract.CoverageRepository;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistory;
import com.insur.insurcoresystem.domain.contract.ContractStatusHistoryRepository;
import com.insur.insurcoresystem.domain.premium.PremiumFactor;
import com.insur.insurcoresystem.domain.premium.PremiumFactorPolicyRepository;
import com.insur.insurcoresystem.domain.premium.PremiumFactorRepository;
import com.insur.insurcoresystem.domain.premium.PremiumRate;
import com.insur.insurcoresystem.domain.premium.PremiumRateRepository;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractInquiryRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractListRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractListResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractInquiryResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractStatusChangeRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractStatusChangeResponse;
import com.insur.insurcoresystem.service.contract.dto.CoverageRequest;
import com.insur.insurcoresystem.service.contract.dto.StatusHistoryInquiryRequest;
import com.insur.insurcoresystem.service.contract.dto.StatusHistoryPageResponse;
import com.insur.insurcoresystem.service.premium.PremiumService;
import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import com.insur.insurcoresystem.service.sequence.ContractNumberGenerator;
import com.insur.insurcoresystem.service.sequence.ContractNumbers;
import com.insur.insurcoresystem.service.sequence.ProductType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    private static final String TX_ID = "tx-0001";
    private static final String EMP_ID = "E12345";

    @Mock
    ContractNumberGenerator contractNumberGenerator;
    @Mock
    ContractRepository contractRepository;
    @Mock
    CoverageRepository coverageRepository;
    @Mock
    ContractStatusHistoryRepository historyRepository;
    @Mock
    PremiumRateRepository premiumRateRepository;
    @Mock
    PremiumFactorRepository premiumFactorRepository;
    @Mock
    PremiumFactorPolicyRepository premiumFactorPolicyRepository;
    ContractService contractService;
    ContractInquiryService inquiryService;
    ContractStatusService statusService;

    /** 2026-10-09 15:30 UTC = 서울 2026-10-10 00:30. JVM 시간대와 무관하게 서울 시각이 저장되는지 확인하는 데 쓴다. */
    private static final Clock SEOUL_CLOCK = Clock.fixed(Instant.parse("2026-10-09T15:30:00Z"), ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        // 요율표는 어떤 담보코드든 연 0.5% 로 돌려주도록 두고, 보험료 대조에는 실제 PremiumService 를 쓴다.
        // request() 헬퍼가 가입금액을 보험료 ÷ 0.005 로 만들기 때문에 평소에는 산출 보험료와 항상 일치한다.
        lenient().when(premiumRateRepository.findEffective(any(), any(), any())).thenAnswer(inv ->
                ((java.util.Collection<String>) inv.getArgument(1)).stream()
                        .map(code -> PremiumRate.builder().insrClsCd(inv.getArgument(0)).cvrgCd(code).cvrgNm(code)
                                .annualRate(new BigDecimal("0.005")).build())
                        .toList());
        ContractHistoryRecorder historyRecorder = new ContractHistoryRecorder(historyRepository, SEOUL_CLOCK);
        contractService = new ContractService(contractNumberGenerator, contractRepository, coverageRepository,
                historyRecorder, new PremiumService(premiumRateRepository, premiumFactorRepository, premiumFactorPolicyRepository), SEOUL_CLOCK);
        inquiryService = new ContractInquiryService(contractRepository, coverageRepository, historyRepository);
        statusService = new ContractStatusService(contractRepository, historyRepository, historyRecorder, SEOUL_CLOCK);
    }

    @Test
    void 채번한_번호와_청약상태로_계약을_저장한다() {
        given(contractNumberGenerator.generate(ProductType.FIRE))
                .willReturn(new ContractNumbers("POL-FIRE-2026-000001", "APP-FIRE-2026-000001"));
        given(contractRepository.saveAndFlush(any(Contract.class))).willAnswer(inv -> inv.getArgument(0));

        ContractApplyResponse response = contractService.applyContract(TX_ID, EMP_ID, request("02", "150000", "100000", "50000"));

        assertThat(response.getContractNo()).isEqualTo("POL-FIRE-2026-000001");
        assertThat(response.getApplNo()).isEqualTo("APP-FIRE-2026-000001");
        assertThat(response.getCtrStatCd()).isEqualTo(Contract.CTR_STAT_APPLIED);
        assertThat(response.getCoverageCount()).isEqualTo(2);
        verify(coverageRepository).saveAll(anyList());
    }

    @Test
    void 총보험료와_담보보험료_합계가_다르면_채번_및_저장없이_예외가_발생한다() {
        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, request("01", "150000", "100000", "40000")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("E-CTR-003");

        verifyNoInteractions(contractNumberGenerator, coverageRepository);
        verify(contractRepository, never()).saveAndFlush(any());
    }

    @Test
    void 금액_스케일이_달라도_값이_같으면_일치로_본다() {
        given(contractNumberGenerator.generate(ProductType.CAR))
                .willReturn(new ContractNumbers("POL-CAR-2026-000001", "APP-CAR-2026-000001"));
        given(contractRepository.saveAndFlush(any(Contract.class))).willAnswer(inv -> inv.getArgument(0));

        contractService.applyContract(TX_ID, EMP_ID, request("01", "150000.00", "100000", "50000"));
    }

    @Test
    void 담보가_없으면_예외가_발생한다() {
        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, request("01", "0")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("E-CTR-001");
    }

    @Test
    void 지원하지_않는_보험종목이면_예외가_발생한다() {
        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, request("99", "100", "100")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("E-SEQ-001");
    }

    @Test
    void 보험종기가_보험시기보다_빠르면_예외가_발생한다() {
        ContractApplyRequest request = request("01", "100", "100");
        request.setEndDate(request.getStartDate());

        assertInvalid(request, "E-CTR-004");
    }

    @Test
    void 보험기간이_없으면_예외가_발생한다() {
        ContractApplyRequest request = request("01", "100", "100");
        request.setStartDate(null);

        assertInvalid(request, "E-CTR-004");
    }

    @Test
    void 계약자ID가_없거나_20자를_넘으면_예외가_발생한다() {
        ContractApplyRequest blank = request("01", "100", "100");
        blank.setPolicyholderId(" ");
        ContractApplyRequest tooLong = request("01", "100", "100");
        tooLong.setInsuredId("X".repeat(21));

        assertInvalid(blank, "E-CTR-005");
        assertInvalid(tooLong, "E-CTR-005");
    }

    @Test
    void 담보코드가_없으면_예외가_발생한다() {
        ContractApplyRequest request = request("01", "100", "100");
        request.getCoverages().get(0).setCvrgCd(null);

        assertInvalid(request, "E-CTR-006");
    }

    @Test
    void 금액이_음수이거나_소수이거나_15자리를_넘으면_예외가_발생한다() {
        assertInvalid(request("01", "-100", "-100"), "E-CTR-007");
        assertInvalid(request("01", "100.5", "100.5"), "E-CTR-007");
        assertInvalid(request("01", "1000000000000000", "1000000000000000"), "E-CTR-007");
    }

    @Test
    void 이미_접수된_거래UUID로_재요청하면_저장없이_기존_결과를_반환한다() {
        Contract applied = Contract.builder()
                .contractNo("POL-CAR-2026-000001")
                .applNo("APP-CAR-2026-000001")
                .globalTxId(TX_ID)
                .ctrStatCd(Contract.CTR_STAT_APPLIED)
                .totalPremium(new BigDecimal("150000"))
                .build();
        given(contractRepository.findByGlobalTxId(TX_ID)).willReturn(java.util.Optional.of(applied));
        given(coverageRepository.findByContractContractNo("POL-CAR-2026-000001"))
                .willReturn(List.of(Coverage.builder().build(), Coverage.builder().build()));

        ContractApplyResponse response = contractService.applyContract(TX_ID, EMP_ID, request("01", "150000", "100000", "50000"));

        assertThat(response.getContractNo()).isEqualTo("POL-CAR-2026-000001");
        assertThat(response.getApplNo()).isEqualTo("APP-CAR-2026-000001");
        assertThat(response.getCoverageCount()).isEqualTo(2);
        verifyNoInteractions(contractNumberGenerator);
        verify(contractRepository, never()).saveAndFlush(any());
        verify(coverageRepository, never()).saveAll(anyList());
    }

    @Test
    void 같은_거래UUID가_동시에_접수되어_유니크_제약에_걸리면_409_충돌_오류가_발생한다() {
        // 제약명은 스키마가 붙거나 대문자로 와도 같은 제약으로 본다
        for (String constraintName : List.of("uk_ctr_lgd_global_tx_id", "public.UK_CTR_LGD_GLOBAL_TX_ID")) {
            given(contractNumberGenerator.generate(ProductType.CAR))
                    .willReturn(new ContractNumbers("POL-CAR-2026-000001", "APP-CAR-2026-000001"));
            given(contractRepository.saveAndFlush(any(Contract.class)))
                    .willThrow(integrityViolation("duplicate key value", constraintName));

            assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, request("01", "100", "100")))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("E-CTR-011");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }
        verifyNoInteractions(coverageRepository);
    }

    @Test
    void 제약명이_다르거나_알_수_없으면_메시지에_거래UUID_제약명이_있어도_충돌로_바꾸지_않고_그대로_던진다() {
        given(contractNumberGenerator.generate(ProductType.CAR))
                .willReturn(new ContractNumbers("POL-CAR-2026-000001", "APP-CAR-2026-000001"));

        // 다른 유니크 제약 위반: 메시지 문장에 거래 UUID 제약명이 들어 있어도 제약명이 다르면 충돌이 아니다
        given(contractRepository.saveAndFlush(any(Contract.class))).willThrow(
                integrityViolation("duplicate key (see uk_ctr_lgd_global_tx_id)", "uk_ctr_lgd_appl_no"));
        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, request("01", "100", "100")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 제약명을 알 수 없는 위반, Hibernate 예외 없이 메시지 문자열만 있는 위반
        given(contractRepository.saveAndFlush(any(Contract.class))).willThrow(
                integrityViolation("null value in column \"insr_cls_cd\"", null));
        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, request("01", "100", "100")))
                .isInstanceOf(DataIntegrityViolationException.class);
        given(contractRepository.saveAndFlush(any(Contract.class))).willThrow(new DataIntegrityViolationException(
                "x", new RuntimeException("duplicate key violates unique constraint \"uk_ctr_lgd_global_tx_id\"")));
        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, request("01", "100", "100")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** DB 제약 위반을 Hibernate 가 감싸고 스프링이 다시 감싼 예외를 흉내 낸다. */
    private static DataIntegrityViolationException integrityViolation(String message, String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException(message,
                        new java.sql.SQLException(message, "23505"), "insert into tb_ctr_lgd ...",
                        org.hibernate.exception.ConstraintViolationException.ConstraintKind.UNIQUE, constraintName));
    }

    @Test
    void 청약하면_요청_내용의_지문을_계약에_저장한다() {
        given(contractNumberGenerator.generate(ProductType.CAR))
                .willReturn(new ContractNumbers("POL-CAR-2026-000001", "APP-CAR-2026-000001"));
        given(contractRepository.saveAndFlush(any(Contract.class))).willAnswer(inv -> inv.getArgument(0));
        ContractApplyRequest request = request("01", "100", "100");

        contractService.applyContract(TX_ID, EMP_ID, request);

        ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getReqHash()).isEqualTo(RequestFingerprint.of(request)).hasSize(64);
    }

    @Test
    void 같은_거래UUID로_같은_내용을_재전송하면_기존_결과를_반환하고_다른_내용이면_409로_거절한다() {
        ContractApplyRequest original = request("01", "100", "100");
        Contract applied = Contract.builder().contractNo("POL-1").applNo("APP-1").globalTxId(TX_ID)
                .regEmpId(EMP_ID).reqHash(RequestFingerprint.of(original)).build();
        given(contractRepository.findByGlobalTxId(TX_ID)).willReturn(java.util.Optional.of(applied));

        // 같은 내용: 표기만 다른 금액(100.00)이어도 같은 요청이다
        ContractApplyRequest same = request("01", "100", "100");
        same.setTotalPremium(new BigDecimal("100.00"));
        same.getCoverages().get(0).setPrem(new BigDecimal("100.00"));
        assertThat(contractService.applyContract(TX_ID, EMP_ID, same).getContractNo()).isEqualTo("POL-1");

        // 다른 내용: 보험료, 계약자 어느 쪽이든 거절한다
        ContractApplyRequest otherPremium = request("01", "200", "200");
        ContractApplyRequest otherHolder = request("01", "100", "100");
        otherHolder.setPolicyholderId("C9999");
        for (ContractApplyRequest changed : List.of(otherPremium, otherHolder)) {
            assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, changed))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("E-CTR-019");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }
        verifyNoInteractions(contractNumberGenerator);
        verify(contractRepository, never()).saveAndFlush(any());
    }

    @Test
    void 지문이_없는_이전_계약은_내용을_비교하지_않고_다른_사번이면_내용과_무관하게_사번_오류가_우선한다() {
        Contract legacy = Contract.builder().contractNo("POL-OLD").applNo("APP-OLD").globalTxId(TX_ID)
                .regEmpId(EMP_ID).build();   // reqHash 없음
        given(contractRepository.findByGlobalTxId(TX_ID)).willReturn(java.util.Optional.of(legacy));

        assertThat(contractService.applyContract(TX_ID, EMP_ID, request("01", "999", "999")).getContractNo())
                .isEqualTo("POL-OLD");

        // 다른 사번이 다른 내용으로 보내도 내용 일치 여부를 알려주지 않고 사번 오류(E-CTR-015)로 거절한다
        Contract others = Contract.builder().contractNo("POL-2").applNo("APP-2").globalTxId("tx-other")
                .regEmpId("E1").reqHash(RequestFingerprint.of(request("01", "100", "100"))).build();
        given(contractRepository.findByGlobalTxId("tx-other")).willReturn(java.util.Optional.of(others));
        assertThatThrownBy(() -> contractService.applyContract("tx-other", "E2", request("01", "200", "200")))
                .extracting("errorCode").isEqualTo("E-CTR-015");
    }

    @Test
    void 담보_보험료가_산출_보험료와_다르면_접수하지_않고_400_E_CTR_020으로_거절한다() {
        // 합계는 맞지만(총보험료 = 담보 보험료 합계) 가입금액 기준 산출 보험료(100)와 다른 90
        ContractApplyRequest request = request("01", "90", "100");
        request.getCoverages().get(0).setPrem(new BigDecimal("90"));

        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("E-CTR-020");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("CA001", "90", "100");
                });
        verifyNoInteractions(contractNumberGenerator, coverageRepository, historyRepository);
        verify(contractRepository, never()).saveAndFlush(any());
    }

    @Test
    void 요율_계수를_입력한_청약은_계수가_반영된_보험료여야_하고_입력값을_계약에_저장한다() {
        given(premiumFactorRepository.findEffective(eq("01"), any())).willReturn(List.of(PremiumFactor.builder()
                .insrClsCd("01").factorType("DISCOUNT_GRADE").factorKey("1").factorNm("1등급")
                .factorValue(new BigDecimal("0.8000")).build()));
        given(contractNumberGenerator.generate(ProductType.CAR))
                .willReturn(new ContractNumbers("POL-CAR-2026-000001", "APP-CAR-2026-000001"));
        given(contractRepository.saveAndFlush(any(Contract.class))).willAnswer(inv -> inv.getArgument(0));

        // 가입금액 20,000,000 × 0.5% = 100 이지만 할인 계수 0.8 이 적용되면 80
        ContractApplyRequest withoutFactorPrem = request("01", "100", "100");
        withoutFactorPrem.setRating(RatingInput.builder().discountGrade("1").build());
        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, withoutFactorPrem))
                .extracting("errorCode").isEqualTo("E-CTR-020");

        ContractApplyRequest request = request("01", "80", "100");
        request.setTotalPremium(new BigDecimal("80"));
        request.getCoverages().get(0).setPrem(new BigDecimal("80"));
        request.setRating(RatingInput.builder().discountGrade("1").build());
        contractService.applyContract(TX_ID, EMP_ID, request);

        ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getDiscountGrade()).isEqualTo("1");
        assertThat(captor.getValue().getDriverAge()).isNull();
        assertThat(captor.getValue().getStructureGrade()).isNull();
    }

    @Test
    void 요율표에_없는_담보나_중복된_담보코드로는_청약할_수_없다() {
        doReturn(List.of()).when(premiumRateRepository).findEffective(any(), any(), any());
        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, request("01", "100", "100")))
                .extracting("errorCode").isEqualTo("E-PRM-001");

        ContractApplyRequest duplicated = request("01", "200", "100", "100");
        duplicated.getCoverages().get(1).setCvrgCd("CA001");
        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, duplicated))
                .extracting("errorCode").isEqualTo("E-PRM-002");
        verifyNoInteractions(contractNumberGenerator);
    }

    @Test
    void 재전송은_요율이_바뀌어도_산출_보험료를_다시_대조하지_않고_기존_결과를_반환한다() {
        ContractApplyRequest original = request("01", "100", "100");
        Contract applied = Contract.builder().contractNo("POL-1").applNo("APP-1").globalTxId(TX_ID)
                .regEmpId(EMP_ID).reqHash(RequestFingerprint.of(original)).build();
        given(contractRepository.findByGlobalTxId(TX_ID)).willReturn(java.util.Optional.of(applied));

        assertThat(contractService.applyContract(TX_ID, EMP_ID, original).getContractNo()).isEqualTo("POL-1");
        // 재전송은 요율표를 조회하지도 않으므로, 접수 이후 요율이 바뀌거나 담보가 요율표에서 사라져도 영향받지 않는다
        verifyNoInteractions(premiumRateRepository, contractNumberGenerator);
    }

    @Test
    void 거래UUID가_없거나_64자를_넘으면_예외가_발생한다() {
        ContractApplyRequest request = request("01", "100", "100");

        assertThatThrownBy(() -> contractService.applyContract(" ", EMP_ID, request))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("E-CTR-008");
        assertThatThrownBy(() -> contractService.applyContract("X".repeat(65), EMP_ID, request))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("E-CTR-008");
        verifyNoInteractions(contractNumberGenerator);
    }

    @Test
    void 증권번호로_계약과_담보를_조회한다() {
        Contract contract = contract();
        given(contractRepository.findById("POL-CAR-2026-000001")).willReturn(java.util.Optional.of(contract));
        given(coverageRepository.findByContractContractNo("POL-CAR-2026-000001")).willReturn(List.of(
                Coverage.builder().cvrgCd("CA001").isrdAmt(new BigDecimal("1000")).prem(new BigDecimal("100")).build()));

        ContractInquiryResponse response = inquiryService.inquireContract(null,
                ContractInquiryRequest.builder().contractNo("POL-CAR-2026-000001").build());

        assertThat(response.getApplNo()).isEqualTo("APP-CAR-2026-000001");
        assertThat(response.getInsrClsCd()).isEqualTo("01");
        assertThat(response.getTotalPremium()).isEqualByComparingTo("100");
        assertThat(response.getCoverages()).hasSize(1);
        assertThat(response.getCoverages().get(0).getCvrgCd()).isEqualTo("CA001");
    }

    @Test
    void 청약번호로도_계약을_조회한다() {
        given(contractRepository.findByApplNo("APP-CAR-2026-000001")).willReturn(java.util.Optional.of(contract()));

        ContractInquiryResponse response = inquiryService.inquireContract(null,
                ContractInquiryRequest.builder().applNo("APP-CAR-2026-000001").build());

        assertThat(response.getContractNo()).isEqualTo("POL-CAR-2026-000001");
        assertThat(response.getCoverages()).isEmpty();
    }

    @Test
    void 조회_조건이_없거나_둘_다_있으면_예외가_발생한다() {
        assertInquiryError(ContractInquiryRequest.builder().build(), "E-CTR-009");
        assertInquiryError(ContractInquiryRequest.builder().contractNo(" ").build(), "E-CTR-009");
        assertInquiryError(ContractInquiryRequest.builder().contractNo("POL-1").applNo("APP-1").build(), "E-CTR-009");
    }

    @Test
    void 존재하지_않는_계약을_조회하면_예외가_발생한다() {
        assertInquiryError(ContractInquiryRequest.builder().contractNo("POL-CAR-2026-999999").build(), "E-CTR-010");
        assertInquiryError(ContractInquiryRequest.builder().applNo("APP-CAR-2026-999999").build(), "E-CTR-010");
    }

    @Test
    void 청약_상태의_계약을_유효로_변경하면_변경_전후_상태를_반환한다() {
        Contract contract = contract();
        given(contractRepository.findForUpdateByContractNo("POL-CAR-2026-000001"))
                .willReturn(java.util.Optional.of(contract));

        ContractStatusChangeResponse response = statusService.changeContractStatus(TX_ID, EMP_ID, 
                ContractStatusChangeRequest.builder().contractNo("POL-CAR-2026-000001").ctrStatCd("20").build());

        assertThat(response.getBeforeCtrStatCd()).isEqualTo("10");
        assertThat(response.getAfterCtrStatCd()).isEqualTo("20");
        assertThat(contract.getCtrStatCd()).isEqualTo("20");
    }

    @Test
    void 해지로_바꾸면_해지일과_남은_기간에_비례한_환급보험료를_기록하고_응답한다() {
        // 고정 시계: 서울 2026-10-10 → 보험기간 2026-10-03~2027-10-03(365일) 중 남은 358일
        Contract inForce = Contract.builder().contractNo("POL-1").applNo("APP-1").ctrStatCd("20")
                .startDate(LocalDate.of(2026, 10, 3)).endDate(LocalDate.of(2027, 10, 3))
                .totalPremium(new BigDecimal("850000")).build();
        given(contractRepository.findForUpdateByContractNo("POL-1")).willReturn(java.util.Optional.of(inForce));

        ContractStatusChangeResponse response = statusService.changeContractStatus(TX_ID, EMP_ID,
                ContractStatusChangeRequest.builder().contractNo("POL-1").ctrStatCd("30").build());

        assertThat(response.getTermDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(response.getRefundPremium()).isEqualByComparingTo("833699");
        assertThat(inForce.getTermDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(inForce.getRefundPremium()).isEqualByComparingTo("833699");
        assertThat(inForce.getCtrStatCd()).isEqualTo("30");
    }

    @Test
    void 해지가_아닌_상태_변경에는_해지_정보를_남기지_않는다() {
        Contract contract = contract();
        given(contractRepository.findForUpdateByContractNo("POL-CAR-2026-000001"))
                .willReturn(java.util.Optional.of(contract));

        ContractStatusChangeResponse response = statusService.changeContractStatus(TX_ID, EMP_ID,
                ContractStatusChangeRequest.builder().contractNo("POL-CAR-2026-000001").ctrStatCd("20").build());

        assertThat(response.getTermDate()).isNull();
        assertThat(response.getRefundPremium()).isNull();
        assertThat(contract.getTermDate()).isNull();
        assertThat(contract.getRefundPremium()).isNull();
    }

    @Test
    void 같은_거래UUID로_이미_처리한_상태_변경이_다시_오면_다시_바꾸지_않고_처음_결과를_돌려준다() {
        // 이미 유효(20)로 바뀐 계약에, 처음 요청(10→20)과 같은 거래 UUID 로 같은 요청이 다시 들어온 상황
        Contract inForce = contract();
        inForce.changeStatus(com.insur.insurcoresystem.domain.contract.ContractStatus.IN_FORCE);
        given(contractRepository.findForUpdateByContractNo("POL-CAR-2026-000001")).willReturn(Optional.of(inForce));
        given(historyRepository.findFirstByContractNoAndGlobalTxIdAndBeforeStatCdIsNotNullOrderByHistIdAsc(
                "POL-CAR-2026-000001", "tx-retry")).willReturn(Optional.of(ContractStatusHistory.builder()
                .beforeStatCd("10").afterStatCd("20").build()));

        ContractStatusChangeResponse same = statusService.changeContractStatus("tx-retry", EMP_ID,
                ContractStatusChangeRequest.builder().contractNo("POL-CAR-2026-000001").ctrStatCd("20").build());

        assertThat(same.getBeforeCtrStatCd()).isEqualTo("10");
        assertThat(same.getAfterCtrStatCd()).isEqualTo("20");
        verify(historyRepository, never()).save(any());   // 이력이 늘지 않는다

        // 같은 거래 UUID 로 다른 상태를 요청하면 거절한다
        assertThatThrownBy(() -> statusService.changeContractStatus("tx-retry", EMP_ID,
                ContractStatusChangeRequest.builder().contractNo("POL-CAR-2026-000001").ctrStatCd("90").build()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("E-CTR-019");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void 해지_재전송은_계약에_기록된_해지일과_환급보험료를_돌려준다() {
        Contract terminated = Contract.builder().contractNo("POL-1").applNo("APP-1").ctrStatCd("30")
                .termDate(LocalDate.of(2026, 10, 10)).refundPremium(new BigDecimal("833699")).build();
        given(contractRepository.findForUpdateByContractNo("POL-1")).willReturn(Optional.of(terminated));
        given(historyRepository.findFirstByContractNoAndGlobalTxIdAndBeforeStatCdIsNotNullOrderByHistIdAsc("POL-1", "tx-t"))
                .willReturn(Optional.of(ContractStatusHistory.builder().beforeStatCd("20").afterStatCd("30").build()));

        ContractStatusChangeResponse response = statusService.changeContractStatus("tx-t", EMP_ID,
                ContractStatusChangeRequest.builder().contractNo("POL-1").ctrStatCd("30").build());

        assertThat(response.getTermDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(response.getRefundPremium()).isEqualByComparingTo("833699");
        verify(historyRepository, never()).save(any());
    }

    @Test
    void 허용되지_않는_상태_변경은_409_충돌_오류가_발생하고_상태는_그대로다() {
        Contract contract = contract();
        given(contractRepository.findForUpdateByContractNo("POL-CAR-2026-000001"))
                .willReturn(java.util.Optional.of(contract));

        // 청약 -> 해지 (유효를 거치지 않음), 청약 -> 청약 (같은 상태)
        for (String target : List.of("30", "10")) {
            assertThatThrownBy(() -> statusService.changeContractStatus(TX_ID, EMP_ID, 
                    ContractStatusChangeRequest.builder().contractNo("POL-CAR-2026-000001").ctrStatCd(target).build()))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("E-CTR-014");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }
        assertThat(contract.getCtrStatCd()).isEqualTo("10");
    }

    @Test
    void 상태_변경_요청의_입력_오류와_없는_계약은_예외가_발생한다() {
        assertStatusChangeError(ContractStatusChangeRequest.builder().ctrStatCd("20").build(), "E-CTR-012");
        assertStatusChangeError(ContractStatusChangeRequest.builder().contractNo("POL-1").build(), "E-CTR-012");
        assertStatusChangeError(
                ContractStatusChangeRequest.builder().contractNo("POL-1").ctrStatCd("99").build(), "E-CTR-013");
        assertStatusChangeError(
                ContractStatusChangeRequest.builder().contractNo("POL-NONE").ctrStatCd("20").build(), "E-CTR-010");
    }

    @Test
    void 청약하면_최초_상태_이력을_남기고_재전송에는_이력을_남기지_않는다() {
        given(contractNumberGenerator.generate(ProductType.CAR))
                .willReturn(new ContractNumbers("POL-CAR-2026-000001", "APP-CAR-2026-000001"));
        given(contractRepository.saveAndFlush(any(Contract.class))).willAnswer(inv -> inv.getArgument(0));

        contractService.applyContract(TX_ID, EMP_ID, request("01", "100", "100"));

        ArgumentCaptor<ContractStatusHistory> captor = ArgumentCaptor.forClass(ContractStatusHistory.class);
        verify(historyRepository).save(captor.capture());
        ContractStatusHistory hist = captor.getValue();
        assertThat(hist.getContractNo()).isEqualTo("POL-CAR-2026-000001");
        assertThat(hist.getBeforeStatCd()).isNull();
        assertThat(hist.getAfterStatCd()).isEqualTo("10");
        assertThat(hist.getEmpId()).isEqualTo(EMP_ID);
        assertThat(hist.getGlobalTxId()).isEqualTo(TX_ID);
        assertThat(hist.getChgDt()).isNotNull();

        // 같은 거래의 재전송: 이미 접수된 계약을 반환하고 이력은 추가하지 않는다
        given(contractRepository.findByGlobalTxId(TX_ID)).willReturn(java.util.Optional.of(contract()));
        contractService.applyContract(TX_ID, EMP_ID, request("01", "100", "100"));
        verify(historyRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void 상태를_변경하면_변경_전후_상태와_처리자를_이력으로_남긴다() {
        given(contractRepository.findForUpdateByContractNo("POL-CAR-2026-000001"))
                .willReturn(java.util.Optional.of(contract()));

        statusService.changeContractStatus("tx-chg", "E777",
                ContractStatusChangeRequest.builder().contractNo("POL-CAR-2026-000001").ctrStatCd("20").build());

        ArgumentCaptor<ContractStatusHistory> captor = ArgumentCaptor.forClass(ContractStatusHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getBeforeStatCd()).isEqualTo("10");
        assertThat(captor.getValue().getAfterStatCd()).isEqualTo("20");
        assertThat(captor.getValue().getEmpId()).isEqualTo("E777");
        assertThat(captor.getValue().getGlobalTxId()).isEqualTo("tx-chg");
    }

    @Test
    void 거절된_상태_변경과_거래UUID_오류는_이력을_남기지_않는다() {
        given(contractRepository.findForUpdateByContractNo("POL-CAR-2026-000001"))
                .willReturn(java.util.Optional.of(contract()));

        assertThatThrownBy(() -> statusService.changeContractStatus(TX_ID, EMP_ID,
                ContractStatusChangeRequest.builder().contractNo("POL-CAR-2026-000001").ctrStatCd("30").build()))
                .extracting("errorCode").isEqualTo("E-CTR-014");
        assertThatThrownBy(() -> statusService.changeContractStatus(" ", EMP_ID,
                ContractStatusChangeRequest.builder().contractNo("POL-CAR-2026-000001").ctrStatCd("20").build()))
                .extracting("errorCode").isEqualTo("E-CTR-008");

        verify(historyRepository, never()).save(any());
    }

    @Test
    void 계약_조회는_상태_이력을_조회하지_않는다() {
        given(contractRepository.findById("POL-CAR-2026-000001")).willReturn(java.util.Optional.of(contract()));

        ContractInquiryResponse response = inquiryService.inquireContract(null,
                ContractInquiryRequest.builder().contractNo("POL-CAR-2026-000001").build());

        assertThat(response.getContractNo()).isEqualTo("POL-CAR-2026-000001");
        verifyNoInteractions(historyRepository);
    }

    @Test
    void 접수자_제한이_있으면_본인_접수_계약만_조회하고_그_외는_계약_없음으로_응답한다() {
        Contract mine = Contract.builder().contractNo("POL-MINE").applNo("APP-MINE").regEmpId("E1").build();
        given(contractRepository.findById("POL-MINE")).willReturn(java.util.Optional.of(mine));
        given(contractRepository.findByApplNo("APP-MINE")).willReturn(java.util.Optional.of(mine));

        // 본인 접수 계약: 증권번호/청약번호 어느 쪽으로든 조회된다
        assertThat(inquiryService.inquireContract("E1",
                ContractInquiryRequest.builder().contractNo("POL-MINE").build()).getRegEmpId()).isEqualTo("E1");
        assertThat(inquiryService.inquireContract("E1",
                ContractInquiryRequest.builder().applNo("APP-MINE").build()).getContractNo()).isEqualTo("POL-MINE");

        // 다른 사번의 조회는 존재하지 않는 계약과 구분할 수 없는 같은 오류(404)로 응답한다
        for (ContractInquiryRequest request : List.of(
                ContractInquiryRequest.builder().contractNo("POL-MINE").build(),
                ContractInquiryRequest.builder().applNo("APP-MINE").build())) {
            assertThatThrownBy(() -> inquiryService.inquireContract("E2", request))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("E-CTR-010");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }
    }

    @Test
    void 접수자_사번이_없는_이전_계약은_제한된_조회에서_보이지_않고_제한이_없으면_보인다() {
        Contract legacy = Contract.builder().contractNo("POL-OLD").applNo("APP-OLD").build();
        given(contractRepository.findById("POL-OLD")).willReturn(java.util.Optional.of(legacy));
        ContractInquiryRequest request = ContractInquiryRequest.builder().contractNo("POL-OLD").build();

        assertThatThrownBy(() -> inquiryService.inquireContract("E1", request))
                .extracting("errorCode").isEqualTo("E-CTR-010");
        assertThat(inquiryService.inquireContract(null, request).getContractNo()).isEqualTo("POL-OLD");
    }

    @Test
    void 청약하면_접수_사번을_계약에_저장한다() {
        given(contractNumberGenerator.generate(ProductType.CAR))
                .willReturn(new ContractNumbers("POL-CAR-2026-000001", "APP-CAR-2026-000001"));
        given(contractRepository.saveAndFlush(any(Contract.class))).willAnswer(inv -> inv.getArgument(0));

        ContractApplyResponse ignored = contractService.applyContract(TX_ID, "E777", request("01", "100", "100"));

        ArgumentCaptor<Contract> captor = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRegEmpId()).isEqualTo("E777");
    }

    @Test
    void 다른_사번이_접수한_거래UUID로_재전송하면_409로_거절하고_번호를_알려주지_않는다() {
        Contract applied = Contract.builder().contractNo("POL-1").applNo("APP-1").globalTxId(TX_ID)
                .regEmpId("E1").build();
        given(contractRepository.findByGlobalTxId(TX_ID)).willReturn(java.util.Optional.of(applied));

        assertThatThrownBy(() -> contractService.applyContract(TX_ID, "E2", request("01", "100", "100")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("E-CTR-015");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(contractNumberGenerator);
    }

    @Test
    void 상태_이력을_페이지_단위로_조회하고_페이지를_생략하면_첫_페이지_20건으로_조회한다() {
        Contract contract = Contract.builder().contractNo("POL-1").regEmpId("E1").build();
        given(contractRepository.findById("POL-1")).willReturn(java.util.Optional.of(contract));
        given(historyRepository.findByContractNo(eq("POL-1"), any(Pageable.class))).willReturn(new PageImpl<>(
                List.of(ContractStatusHistory.builder().afterStatCd("10").empId("E1").build(),
                        ContractStatusHistory.builder().beforeStatCd("10").afterStatCd("20").empId("U1").build()),
                PageRequest.of(0, 2), 3));

        StatusHistoryPageResponse response = inquiryService.inquireStatusHistory(null,
                StatusHistoryInquiryRequest.builder().contractNo("POL-1").page(0).size(2).build());

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(1).getAfterCtrStatCd()).isEqualTo("20");
        assertThat(response.getTotalElements()).isEqualTo(3);
        assertThat(response.getTotalPages()).isEqualTo(2);
        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(2);

        // page, size 를 생략하면 0페이지, 20건. 발생 순서(HIST_ID 오름차순)로 정렬해서 조회한다
        inquiryService.inquireStatusHistory(null, StatusHistoryInquiryRequest.builder().contractNo("POL-1").build());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(historyRepository, org.mockito.Mockito.times(2)).findByContractNo(eq("POL-1"), captor.capture());
        Pageable defaults = captor.getAllValues().get(1);
        assertThat(defaults.getPageNumber()).isZero();
        assertThat(defaults.getPageSize()).isEqualTo(20);
        assertThat(defaults.getSort().getOrderFor("histId").isAscending()).isTrue();
    }

    @Test
    void 이력_조회의_증권번호_누락_페이징_오류_조회_범위_밖은_예외가_발생한다() {
        Contract contract = Contract.builder().contractNo("POL-1").regEmpId("E1").build();
        given(contractRepository.findById("POL-1")).willReturn(java.util.Optional.of(contract));
        given(contractRepository.findById("POL-NONE")).willReturn(java.util.Optional.empty());

        assertHistoryError(null, StatusHistoryInquiryRequest.builder().build(), "E-CTR-009");
        assertHistoryError(null, StatusHistoryInquiryRequest.builder().contractNo("POL-1").page(-1).build(), "E-CTR-016");
        assertHistoryError(null, StatusHistoryInquiryRequest.builder().contractNo("POL-1").size(0).build(), "E-CTR-016");
        assertHistoryError(null, StatusHistoryInquiryRequest.builder().contractNo("POL-1").size(101).build(), "E-CTR-016");
        assertHistoryError(null, StatusHistoryInquiryRequest.builder().contractNo("POL-NONE").build(), "E-CTR-010");
        // 본인이 접수하지 않은 계약은 없는 계약과 같은 오류
        assertHistoryError("E2", StatusHistoryInquiryRequest.builder().contractNo("POL-1").build(), "E-CTR-010");
        verifyNoInteractions(historyRepository);
    }

    @Test
    void 목록_조회_조건_오류와_페이징_오류는_조회하기_전에_예외가_발생한다() {
        assertListError(ContractListRequest.builder().insrClsCd("99").build(), "E-SEQ-001");
        assertListError(ContractListRequest.builder().ctrStatCd("99").build(), "E-CTR-013");
        assertListError(ContractListRequest.builder()
                .regDateFrom(java.time.LocalDate.of(2026, 10, 10)).regDateTo(java.time.LocalDate.of(2026, 10, 9))
                .build(), "E-CTR-017");
        assertListError(ContractListRequest.builder().page(-1).build(), "E-CTR-016");
        assertListError(ContractListRequest.builder().size(0).build(), "E-CTR-016");
        assertListError(ContractListRequest.builder().size(101).build(), "E-CTR-016");
        assertListError(ContractListRequest.builder()
                .startDateFrom(java.time.LocalDate.of(2026, 12, 1)).startDateTo(java.time.LocalDate.of(2026, 11, 1))
                .build(), "E-CTR-017");
        assertListError(ContractListRequest.builder()
                .premiumMin(new BigDecimal("200")).premiumMax(new BigDecimal("100")).build(), "E-CTR-017");
        assertListError(ContractListRequest.builder().premiumMin(new BigDecimal("-1")).build(), "E-CTR-017");
        assertListError(ContractListRequest.builder().sortBy("CONTRACT_NO").build(), "E-CTR-018");
        assertListError(ContractListRequest.builder().sortDir("UP").build(), "E-CTR-018");
        verifyNoInteractions(contractRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 목록_정렬은_기준과_방향을_고르고_같은_값은_증권번호로_정렬한다() {
        given(contractRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        // 대소문자를 가리지 않는다
        inquiryService.listContracts(null, ContractListRequest.builder().sortBy("total_premium").sortDir("asc").build());
        inquiryService.listContracts(null, ContractListRequest.builder().sortBy("START_DATE").build());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(contractRepository, org.mockito.Mockito.times(2))
                .findAll(any(org.springframework.data.jpa.domain.Specification.class), captor.capture());
        org.springframework.data.domain.Sort premiumAsc = captor.getAllValues().get(0).getSort();
        assertThat(premiumAsc.getOrderFor("totalPremium").isAscending()).isTrue();
        assertThat(premiumAsc.getOrderFor("contractNo").isAscending()).isTrue();
        org.springframework.data.domain.Sort startDesc = captor.getAllValues().get(1).getSort();
        assertThat(startDesc.getOrderFor("startDate").isDescending()).isTrue();   // 방향을 생략하면 내림차순
    }

    @Test
    @SuppressWarnings("unchecked")
    void 계약_목록은_최근_접수순으로_조회하고_기본_페이지는_0페이지_20건이다() {
        given(contractRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(contract()), PageRequest.of(0, 20), 1));

        ContractListResponse response = inquiryService.listContracts(null, ContractListRequest.builder().build());

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getContractNo()).isEqualTo("POL-CAR-2026-000001");
        assertThat(response.getItems().get(0).getTotalPremium()).isEqualByComparingTo("100");
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getPage()).isZero();
        assertThat(response.getSize()).isEqualTo(20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(contractRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("sysRegDt").isDescending()).isTrue();
        assertThat(captor.getValue().getSort().getOrderFor("contractNo").isDescending()).isTrue();
    }

    private void assertListError(ContractListRequest request, String errorCode) {
        assertThatThrownBy(() -> inquiryService.listContracts(null, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Test
    void 저장하는_일시는_JVM_시간대가_아니라_업무_시간대_서울_기준이다() {
        given(contractNumberGenerator.generate(ProductType.CAR))
                .willReturn(new ContractNumbers("POL-CAR-2026-000001", "APP-CAR-2026-000001"));
        given(contractRepository.saveAndFlush(any(Contract.class))).willAnswer(inv -> inv.getArgument(0));

        contractService.applyContract(TX_ID, EMP_ID, request("01", "100", "100"));

        ArgumentCaptor<Contract> contractCaptor = ArgumentCaptor.forClass(Contract.class);
        verify(contractRepository).saveAndFlush(contractCaptor.capture());
        ArgumentCaptor<ContractStatusHistory> histCaptor = ArgumentCaptor.forClass(ContractStatusHistory.class);
        verify(historyRepository).save(histCaptor.capture());
        LocalDateTime seoulTime = LocalDateTime.of(2026, 10, 10, 0, 30);
        assertThat(contractCaptor.getValue().getSysRegDt()).isEqualTo(seoulTime);
        assertThat(histCaptor.getValue().getChgDt()).isEqualTo(seoulTime);
    }

    @Test
    void 담보_목록에_null_항목이_있으면_시스템_오류가_아니라_검증_오류가_발생한다() {
        ContractApplyRequest onlyNull = request("01", "100", "100");
        onlyNull.setCoverages(java.util.Arrays.asList((CoverageRequest) null));
        ContractApplyRequest mixed = request("01", "100", "100");
        mixed.setCoverages(java.util.Arrays.asList(mixed.getCoverages().get(0), null));

        assertInvalid(onlyNull, "E-CTR-001");
        assertInvalid(mixed, "E-CTR-001");
    }

    @Test
    void 사번이_64자를_넘어도_저장할_때와_같은_규칙으로_잘라_본인_계약을_찾는다() {
        String longEmpId = "E".repeat(70);
        String stored = "E".repeat(64);
        Contract mine = Contract.builder().contractNo("POL-LONG").applNo("APP-LONG").regEmpId(stored).build();
        given(contractRepository.findById("POL-LONG")).willReturn(java.util.Optional.of(mine));
        given(historyRepository.findByContractNo(eq("POL-LONG"), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(inquiryService.inquireContract(longEmpId,
                ContractInquiryRequest.builder().contractNo("POL-LONG").build()).getContractNo()).isEqualTo("POL-LONG");
        assertThat(inquiryService.inquireStatusHistory(longEmpId,
                StatusHistoryInquiryRequest.builder().contractNo("POL-LONG").build()).getContractNo())
                .isEqualTo("POL-LONG");
        // 앞 64자까지 같아도 다른 사번이면 여전히 보이지 않는다
        assertThatThrownBy(() -> inquiryService.inquireContract("F" + "E".repeat(69),
                ContractInquiryRequest.builder().contractNo("POL-LONG").build()))
                .extracting("errorCode").isEqualTo("E-CTR-010");
    }

    private void assertHistoryError(String ownerEmpId, StatusHistoryInquiryRequest request, String errorCode) {
        assertThatThrownBy(() -> inquiryService.inquireStatusHistory(ownerEmpId, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private void assertStatusChangeError(ContractStatusChangeRequest request, String errorCode) {
        assertThatThrownBy(() -> statusService.changeContractStatus(TX_ID, EMP_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private void assertInquiryError(ContractInquiryRequest request, String errorCode) {
        assertThatThrownBy(() -> inquiryService.inquireContract(null, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private static Contract contract() {
        return Contract.builder()
                .contractNo("POL-CAR-2026-000001")
                .applNo("APP-CAR-2026-000001")
                .insrClsCd("01")
                .ctrStatCd(Contract.CTR_STAT_APPLIED)
                .startDate(LocalDate.of(2026, 10, 3))
                .endDate(LocalDate.of(2027, 10, 3))
                .policyholderId("C0001")
                .insuredId("C0002")
                .totalPremium(new BigDecimal("100"))
                .build();
    }

    private void assertInvalid(ContractApplyRequest request, String errorCode) {
        assertThatThrownBy(() -> contractService.applyContract(TX_ID, EMP_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
        verifyNoInteractions(contractNumberGenerator, coverageRepository);
    }

    private static ContractApplyRequest request(String insrClsCd, String totalPremium, String... prems) {
        // 담보코드는 순서대로 CA001, CA002, ... 이고 가입금액은 보험료 ÷ 0.005 (365일 기준 산출 보험료와 같아진다)
        List<CoverageRequest> coverages = java.util.stream.IntStream.range(0, prems.length)
                .mapToObj(i -> CoverageRequest.builder()
                        .cvrgCd("CA00" + (i + 1))
                        .isrdAmt(new BigDecimal(prems[i]).divide(new BigDecimal("0.005")))
                        .prem(new BigDecimal(prems[i]))
                        .build())
                .toList();
        return ContractApplyRequest.builder()
                .insrClsCd(insrClsCd)
                .startDate(LocalDate.of(2026, 10, 3))
                .endDate(LocalDate.of(2027, 10, 3))
                .policyholderId("C0001")
                .insuredId("C0002")
                .totalPremium(new BigDecimal(totalPremium))
                .coverages(coverages)
                .build();
    }
}
