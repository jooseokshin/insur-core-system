package com.insur.insurcoresystem.service.premium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.premium.PremiumAudit;
import com.insur.insurcoresystem.domain.premium.PremiumAuditRepository;
import com.insur.insurcoresystem.domain.premium.PremiumFactor;
import com.insur.insurcoresystem.domain.premium.PremiumFactorPolicy;
import com.insur.insurcoresystem.domain.premium.PremiumFactorPolicyRepository;
import com.insur.insurcoresystem.domain.premium.PremiumFactorRepository;
import com.insur.insurcoresystem.domain.premium.PremiumRate;
import com.insur.insurcoresystem.domain.premium.PremiumRateRepository;
import com.insur.insurcoresystem.service.premium.dto.FactorListRequest;
import com.insur.insurcoresystem.service.premium.dto.FactorRevisionRequest;
import com.insur.insurcoresystem.service.premium.dto.FactorRevisionResponse;
import com.insur.insurcoresystem.service.premium.dto.RateListRequest;
import com.insur.insurcoresystem.service.premium.dto.RateListResponse;
import com.insur.insurcoresystem.service.premium.dto.RateRevisionRequest;
import com.insur.insurcoresystem.service.premium.dto.RateRevisionResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
class PremiumAdminServiceTest {

    /** 서울 2026-10-10 (UTC 2026-10-09 15:30) */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    @Mock
    PremiumRateRepository rateRepository;
    @Mock
    PremiumFactorRepository factorRepository;
    @Mock
    PremiumFactorPolicyRepository policyRepository;
    @Mock
    PremiumAuditRepository auditRepository;
    PremiumAdminService service;

    @BeforeEach
    void setUp() {
        service = new PremiumAdminService(rateRepository, factorRepository, policyRepository, auditRepository, CLOCK);
    }

    private static PremiumRate rate(String cvrgCd, String value, LocalDate from, LocalDate to) {
        return PremiumRate.builder().rateId(1L).insrClsCd("01").cvrgCd(cvrgCd).cvrgNm("대인배상")
                .annualRate(new BigDecimal(value)).effFrom(from).effTo(to).build();
    }

    private static RateRevisionRequest revision(String value, LocalDate effFrom) {
        return RateRevisionRequest.builder().insrClsCd("01").cvrgCd("CA001")
                .annualRate(new BigDecimal(value)).effFrom(effFrom).build();
    }

    private void givenRateHistory(PremiumRate... rows) {
        given(rateRepository.findByInsrClsCdAndCvrgCdOrderByEffFromAsc("01", "CA001")).willReturn(List.of(rows));
    }

    /** 저장소가 저장한 엔티티를 그대로 돌려주도록 한다. (저장까지 가는 테스트에서만 쓴다) */
    private void givenRateSaveEchoes() {
        given(rateRepository.saveAndFlush(any(PremiumRate.class))).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 요율을_개정하면_가장_최근_행을_닫고_새_행을_추가하고_감사_이력을_남긴다() {
        PremiumRate latest = rate("CA001", "0.005", LocalDate.of(2000, 1, 1), null);
        givenRateHistory(latest);
        givenRateSaveEchoes();

        RateRevisionResponse response = service.reviseRate("tx-1", "A500", revision("0.006", TODAY.plusDays(30)));

        assertThat(response.getAction()).isEqualTo("REVISE");
        assertThat(response.getPrevious().getEffTo()).isEqualTo(TODAY.plusDays(29));   // 새 시작일 − 1일
        assertThat(latest.getEffTo()).isEqualTo(TODAY.plusDays(29));
        assertThat(response.getCurrent().getAnnualRate()).isEqualByComparingTo("0.006");
        assertThat(response.getCurrent().getCvrgNm()).isEqualTo("대인배상");             // 담보명을 생략하면 기존 이름
        assertThat(response.getCurrent().getEffTo()).isNull();
        assertThat(response.getCurrent().getStatus()).isEqualTo("FUTURE");
        assertThat(response.getPrevious().getStatus()).isEqualTo("ACTIVE");

        ArgumentCaptor<PremiumAudit> audit = ArgumentCaptor.forClass(PremiumAudit.class);
        verify(auditRepository).saveAndFlush(audit.capture());
        assertThat(audit.getValue().getTargetType()).isEqualTo("RATE");
        assertThat(audit.getValue().getTargetKey()).isEqualTo("01/CA001");
        assertThat(audit.getValue().getAction()).isEqualTo("REVISE");
        assertThat(audit.getValue().getBeforeVal()).isEqualTo("0.005");
        assertThat(audit.getValue().getAfterVal()).isEqualTo("0.006");
        assertThat(audit.getValue().getEmpId()).isEqualTo("A500");
        assertThat(audit.getValue().getGlobalTxId()).isEqualTo("tx-1");
    }

    @Test
    void 이미_종료된_최근_행은_다시_닫지_않고_새_행만_추가한다() {
        PremiumRate closed = rate("CA001", "0.005", LocalDate.of(2000, 1, 1), TODAY.plusDays(5));
        givenRateHistory(closed);
        givenRateSaveEchoes();

        RateRevisionResponse response = service.reviseRate("tx-1", "A500", revision("0.006", TODAY.plusDays(30)));

        assertThat(response.getPrevious()).isNull();
        assertThat(closed.getEffTo()).isEqualTo(TODAY.plusDays(5));   // 그대로
    }

    @Test
    void 새_담보의_요율은_담보명이_있어야_만들_수_있다() {
        given(rateRepository.findByInsrClsCdAndCvrgCdOrderByEffFromAsc("01", "ZZ001")).willReturn(List.of());
        given(rateRepository.saveAndFlush(any(PremiumRate.class))).willAnswer(inv -> inv.getArgument(0));
        RateRevisionRequest noName = RateRevisionRequest.builder().insrClsCd("01").cvrgCd("ZZ001")
                .annualRate(new BigDecimal("0.01")).effFrom(TODAY).build();

        assertThatThrownBy(() -> service.reviseRate("tx-1", "A500", noName))
                .extracting("errorCode").isEqualTo("E-ADM-001");

        noName.setCvrgNm("신규 담보");
        RateRevisionResponse response = service.reviseRate("tx-2", "A500", noName);
        assertThat(response.getAction()).isEqualTo("CREATE");
        assertThat(response.getPrevious()).isNull();
        assertThat(response.getCurrent().getStatus()).isEqualTo("ACTIVE");   // 오늘 시작
        ArgumentCaptor<PremiumAudit> audit = ArgumentCaptor.forClass(PremiumAudit.class);
        verify(auditRepository).saveAndFlush(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("CREATE");
        assertThat(audit.getValue().getBeforeVal()).isNull();
    }

    @Test
    void 과거_시작일이나_최근_행보다_늦지_않은_시작일은_거절한다() {
        assertThatThrownBy(() -> service.reviseRate("tx-1", "A500", revision("0.006", TODAY.minusDays(1))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("E-ADM-002");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        givenRateHistory(rate("CA001", "0.005", TODAY.plusDays(10), null));   // 이미 예약된 미래 개정이 있다
        for (LocalDate notAfter : List.of(TODAY.plusDays(10), TODAY.plusDays(3))) {
            assertThatThrownBy(() -> service.reviseRate("tx-1", "A500", revision("0.006", notAfter)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("E-ADM-003");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }
        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    void 입력_오류는_저장소를_조회하기_전에_거절한다() {
        RateRevisionRequest bad = revision("0.006", TODAY);
        assertError(() -> service.reviseRate("tx", "A", RateRevisionRequest.builder().insrClsCd("99").cvrgCd("CA001")
                .annualRate(new BigDecimal("0.006")).effFrom(TODAY).build()), "E-SEQ-001");
        bad.setCvrgCd(" ");
        assertError(() -> service.reviseRate("tx", "A", bad), "E-CTR-006");
        for (String rate : List.of("0", "-0.1", "1.0001", "0.0000001")) {   // 0 이하, 1 초과, 소수 7자리
            assertError(() -> service.reviseRate("tx", "A", revision(rate, TODAY)), "E-ADM-001");
        }
        assertError(() -> service.reviseRate("tx", "A", RateRevisionRequest.builder().insrClsCd("01").cvrgCd("CA001")
                .effFrom(TODAY).build()), "E-ADM-001");                              // 요율 없음
        assertError(() -> service.reviseRate("tx", "A", revision("0.006", null)), "E-ADM-001");   // 시작일 없음
        assertError(() -> service.reviseRate(" ", "A", revision("0.006", TODAY)), "E-CTR-008");
        verifyNoInteractions(rateRepository, auditRepository);
    }

    private void assertError(org.junit.jupiter.api.function.Executable call, String errorCode) {
        assertThatThrownBy(call::execute).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    @Test
    void 같은_거래UUID로_이미_처리한_요율_변경은_다시_처리하지_않고_처음_결과를_돌려준다() {
        LocalDate effFrom = TODAY.plusDays(30);
        PremiumRate old = rate("CA001", "0.005", LocalDate.of(2000, 1, 1), effFrom.minusDays(1));
        PremiumRate created = rate("CA001", "0.006", effFrom, null);
        given(auditRepository.findByTargetTypeAndGlobalTxId("RATE", "tx-1")).willReturn(Optional.of(PremiumAudit.builder()
                .targetType("RATE").targetKey("01/CA001").action("REVISE").afterVal("0.006").effFrom(effFrom).build()));
        given(rateRepository.findByInsrClsCdAndCvrgCdOrderByEffFromAsc("01", "CA001")).willReturn(List.of(old, created));

        RateRevisionResponse response = service.reviseRate("tx-1", "A500", revision("0.0060", effFrom));   // 표기만 다른 같은 값

        assertThat(response.getAction()).isEqualTo("REVISE");
        assertThat(response.getCurrent().getAnnualRate()).isEqualByComparingTo("0.006");
        assertThat(response.getPrevious().getEffTo()).isEqualTo(effFrom.minusDays(1));
        verify(rateRepository, never()).saveAndFlush(any());
        verify(auditRepository, never()).saveAndFlush(any());

        // 같은 거래 UUID 로 값이나 시작일이 다르면 거절한다
        for (RateRevisionRequest different : List.of(revision("0.007", effFrom), revision("0.006", effFrom.plusDays(1)))) {
            assertThatThrownBy(() -> service.reviseRate("tx-1", "A500", different))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("E-CTR-019");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }
    }

    @Test
    void 적용_기간_겹침이나_동시_작업으로_DB가_거절하면_충돌_오류로_바꾼다() {
        givenRateHistory(rate("CA001", "0.005", LocalDate.of(2000, 1, 1), null));
        given(rateRepository.saveAndFlush(any(PremiumRate.class)))
                .willThrow(new DataIntegrityViolationException("요율 적용 기간이 겹칩니다", new RuntimeException("overlap")));

        assertThatThrownBy(() -> service.reviseRate("tx-1", "A500", revision("0.006", TODAY.plusDays(30))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("E-ADM-004");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void 요율_목록은_조건으로_거르고_보험종목_담보_시작일_순으로_상태와_함께_돌려준다() {
        given(rateRepository.findAll()).willReturn(List.of(
                rate("CA002", "0.0175", LocalDate.of(2000, 1, 1), null),
                rate("CA001", "0.006", TODAY.plusDays(30), null),
                rate("CA001", "0.005", LocalDate.of(2000, 1, 1), TODAY.plusDays(29)),
                rate("CA001", "0.004", LocalDate.of(1990, 1, 1), LocalDate.of(1999, 12, 31)),
                PremiumRate.builder().insrClsCd("02").cvrgCd("FI001").cvrgNm("건물").annualRate(new BigDecimal("0.0012"))
                        .effFrom(LocalDate.of(2000, 1, 1)).build()));

        RateListResponse all = service.listRates(new RateListRequest());
        assertThat(all.getItems()).extracting("cvrgCd").containsExactly("CA001", "CA001", "CA001", "CA002", "FI001");
        assertThat(all.getItems()).extracting("status").containsExactly("CLOSED", "ACTIVE", "FUTURE", "ACTIVE", "ACTIVE");
        assertThat(all.getItems().get(0).getEffFrom()).isEqualTo(LocalDate.of(1990, 1, 1));   // 같은 담보는 시작일 순

        assertThat(service.listRates(RateListRequest.builder().insrClsCd("02").build()).getItems()).hasSize(1);
        assertThat(service.listRates(RateListRequest.builder().insrClsCd("01").cvrgCd("CA001").build()).getItems()).hasSize(3);
        assertError(() -> service.listRates(RateListRequest.builder().insrClsCd("99").build()), "E-SEQ-001");
    }

    // ------------------------------------------------------------------------------------------ 감사 조회

    @Test
    void 감사_조회의_잘못된_조건과_페이징은_저장소를_조회하기_전에_거절한다() {
        var type = com.insur.insurcoresystem.service.premium.dto.AuditListRequest.builder().targetType("COLOR").build();
        assertError(() -> service.listAudits(type), "E-ADM-001");
        var range = com.insur.insurcoresystem.service.premium.dto.AuditListRequest.builder()
                .fromDate(TODAY).toDate(TODAY.minusDays(1)).build();
        assertError(() -> service.listAudits(range), "E-ADM-001");
        var size = com.insur.insurcoresystem.service.premium.dto.AuditListRequest.builder().size(101).build();
        assertError(() -> service.listAudits(size), "E-CTR-016");
        var page = com.insur.insurcoresystem.service.premium.dto.AuditListRequest.builder().page(-1).build();
        assertError(() -> service.listAudits(page), "E-CTR-016");
        verifyNoInteractions(auditRepository);
    }

    // ------------------------------------------------------------------------------------ 필수 계수 정책

    private static PremiumFactorPolicy policy(String type, boolean required, LocalDate from, LocalDate to) {
        return PremiumFactorPolicy.builder().policyId(1L).insrClsCd("01").factorType(type).required(required)
                .effFrom(from).effTo(to).build();
    }

    private static com.insur.insurcoresystem.service.premium.dto.PolicyRevisionRequest policyRevision(
            String type, Boolean required, LocalDate effFrom) {
        return com.insur.insurcoresystem.service.premium.dto.PolicyRevisionRequest.builder()
                .insrClsCd("01").factorType(type).required(required).effFrom(effFrom).build();
    }

    @Test
    void 필수_계수_정책을_개정하면_기존_행을_닫고_새_행을_추가하고_감사_이력을_남긴다() {
        PremiumFactorPolicy latest = policy("DISCOUNT_GRADE", true, LocalDate.of(2000, 1, 1), null);
        given(policyRepository.findByInsrClsCdAndFactorTypeOrderByEffFromAsc("01", "DISCOUNT_GRADE")).willReturn(List.of(latest));
        given(policyRepository.saveAndFlush(any(PremiumFactorPolicy.class))).willAnswer(inv -> inv.getArgument(0));

        var response = service.revisePolicy("tx-1", "A500", policyRevision("DISCOUNT_GRADE", false, TODAY.plusDays(30)));

        assertThat(response.getAction()).isEqualTo("REVISE");
        assertThat(latest.getEffTo()).isEqualTo(TODAY.plusDays(29));
        assertThat(response.getCurrent().isRequired()).isFalse();
        assertThat(response.getCurrent().getStatus()).isEqualTo("FUTURE");
        assertThat(response.getPrevious().isRequired()).isTrue();
        ArgumentCaptor<PremiumAudit> audit = ArgumentCaptor.forClass(PremiumAudit.class);
        verify(auditRepository).saveAndFlush(audit.capture());
        assertThat(audit.getValue().getTargetType()).isEqualTo("POLICY");
        assertThat(audit.getValue().getTargetKey()).isEqualTo("01/DISCOUNT_GRADE");
        assertThat(audit.getValue().getBeforeVal()).isEqualTo("true");
        assertThat(audit.getValue().getAfterVal()).isEqualTo("false");
    }

    @Test
    void 정책이_없는_계수_종류는_새로_만들_수_있다() {
        given(policyRepository.findByInsrClsCdAndFactorTypeOrderByEffFromAsc("01", "DRIVER_AGE")).willReturn(List.of());
        given(policyRepository.saveAndFlush(any(PremiumFactorPolicy.class))).willAnswer(inv -> inv.getArgument(0));

        var response = service.revisePolicy("tx-1", "A500", policyRevision("DRIVER_AGE", true, TODAY));

        assertThat(response.getAction()).isEqualTo("CREATE");
        assertThat(response.getPrevious()).isNull();
    }

    @Test
    void 필수_계수_정책의_입력_오류와_시작일_규칙() {
        assertError(() -> service.revisePolicy("tx", "A", policyRevision("COLOR", true, TODAY)), "E-ADM-001");
        assertError(() -> service.revisePolicy("tx", "A", policyRevision("STRUCTURE_GRADE", true, TODAY)), "E-ADM-001");   // 자동차에서 안 쓰는 종류
        assertError(() -> service.revisePolicy("tx", "A", policyRevision("DRIVER_AGE", null, TODAY)), "E-ADM-001");
        assertError(() -> service.revisePolicy("tx", "A", policyRevision("DRIVER_AGE", true, null)), "E-ADM-001");
        assertError(() -> service.revisePolicy("tx", "A", policyRevision("DRIVER_AGE", true, TODAY.minusDays(1))), "E-ADM-002");
        assertError(() -> service.revisePolicy(" ", "A", policyRevision("DRIVER_AGE", true, TODAY)), "E-CTR-008");
        verifyNoInteractions(policyRepository, auditRepository);

        given(policyRepository.findByInsrClsCdAndFactorTypeOrderByEffFromAsc("01", "DRIVER_AGE"))
                .willReturn(List.of(policy("DRIVER_AGE", true, TODAY.plusDays(10), null)));
        assertThatThrownBy(() -> service.revisePolicy("tx", "A", policyRevision("DRIVER_AGE", false, TODAY.plusDays(10))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("E-ADM-003");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void 같은_거래UUID로_이미_처리한_정책_변경은_처음_결과를_돌려주고_다른_내용은_거절한다() {
        LocalDate effFrom = TODAY.plusDays(30);
        PremiumFactorPolicy old = policy("DISCOUNT_GRADE", true, LocalDate.of(2000, 1, 1), effFrom.minusDays(1));
        PremiumFactorPolicy created = policy("DISCOUNT_GRADE", false, effFrom, null);
        given(auditRepository.findByTargetTypeAndGlobalTxId("POLICY", "tx-1")).willReturn(Optional.of(PremiumAudit.builder()
                .targetType("POLICY").targetKey("01/DISCOUNT_GRADE").action("REVISE").afterVal("false").effFrom(effFrom).build()));
        given(policyRepository.findByInsrClsCdAndFactorTypeOrderByEffFromAsc("01", "DISCOUNT_GRADE"))
                .willReturn(List.of(old, created));

        var response = service.revisePolicy("tx-1", "A500", policyRevision("DISCOUNT_GRADE", false, effFrom));

        assertThat(response.getCurrent().isRequired()).isFalse();
        assertThat(response.getPrevious().getEffTo()).isEqualTo(effFrom.minusDays(1));
        verify(policyRepository, never()).saveAndFlush(any());
        assertError(() -> service.revisePolicy("tx-1", "A500", policyRevision("DISCOUNT_GRADE", true, effFrom)), "E-CTR-019");
    }

    @Test
    void 정책_목록은_보험종목으로_거르고_정렬한다() {
        given(policyRepository.findAll()).willReturn(List.of(
                policy("DRIVER_AGE", true, LocalDate.of(2000, 1, 1), null),
                policy("DISCOUNT_GRADE", false, TODAY.plusDays(30), null),
                policy("DISCOUNT_GRADE", true, LocalDate.of(2000, 1, 1), TODAY.plusDays(29)),
                PremiumFactorPolicy.builder().insrClsCd("02").factorType("STRUCTURE_GRADE").required(true)
                        .effFrom(LocalDate.of(2000, 1, 1)).build()));

        var all = service.listPolicies(new com.insur.insurcoresystem.service.premium.dto.PolicyListRequest());
        assertThat(all.getItems()).extracting("factorType")
                .containsExactly("DISCOUNT_GRADE", "DISCOUNT_GRADE", "DRIVER_AGE", "STRUCTURE_GRADE");
        assertThat(all.getItems().subList(0, 2)).extracting("status").containsExactly("ACTIVE", "FUTURE");
        assertThat(service.listPolicies(com.insur.insurcoresystem.service.premium.dto.PolicyListRequest.builder()
                .insrClsCd("02").build()).getItems()).hasSize(1);
        assertError(() -> service.listPolicies(com.insur.insurcoresystem.service.premium.dto.PolicyListRequest.builder()
                .insrClsCd("99").build()), "E-SEQ-001");
    }

    // ------------------------------------------------------------------------------------------- 계수

    private static PremiumFactor ageBand(int from, int to, String value, LocalDate effFrom, LocalDate effTo) {
        return PremiumFactor.builder().factorId(1L).insrClsCd("01").factorType("DRIVER_AGE").rangeFrom(from).rangeTo(to)
                .factorNm("만 " + from + "~" + to + "세").factorValue(new BigDecimal(value)).effFrom(effFrom).effTo(effTo).build();
    }

    private void givenFactorHistory(String type, PremiumFactor... rows) {
        given(factorRepository.findByInsrClsCdAndFactorTypeOrderByEffFromAsc("01", type)).willReturn(List.of(rows));
        given(factorRepository.saveAndFlush(any(PremiumFactor.class))).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 구간형_계수는_같은_구간의_행만_개정하고_다른_구간은_건드리지_않는다() {
        PremiumFactor target = ageBand(18, 25, "1.5", LocalDate.of(2000, 1, 1), null);
        PremiumFactor other = ageBand(26, 29, "1.2", LocalDate.of(2000, 1, 1), null);
        givenFactorHistory("DRIVER_AGE", target, other);

        FactorRevisionResponse response = service.reviseFactor("tx-1", "A500", FactorRevisionRequest.builder()
                .insrClsCd("01").factorType("DRIVER_AGE").rangeFrom(18).rangeTo(25)
                .factorValue(new BigDecimal("1.8")).effFrom(TODAY.plusDays(30)).build());

        assertThat(response.getAction()).isEqualTo("REVISE");
        assertThat(target.getEffTo()).isEqualTo(TODAY.plusDays(29));
        assertThat(other.getEffTo()).isNull();                                       // 다른 구간은 그대로
        assertThat(response.getCurrent().getRangeFrom()).isEqualTo(18);
        assertThat(response.getCurrent().getFactorNm()).isEqualTo("만 18~25세");       // 이름을 생략하면 기존 이름
        assertThat(response.getCurrent().getFactorValue()).isEqualByComparingTo("1.8");
        ArgumentCaptor<PremiumAudit> audit = ArgumentCaptor.forClass(PremiumAudit.class);
        verify(auditRepository).saveAndFlush(audit.capture());
        assertThat(audit.getValue().getTargetKey()).isEqualTo("01/DRIVER_AGE/18-25");
        assertThat(audit.getValue().getBeforeVal()).isEqualTo("1.5");
        assertThat(audit.getValue().getAfterVal()).isEqualTo("1.8");
    }

    @Test
    void 키형_계수를_개정하고_새_키를_만들_수_있다() {
        PremiumFactor grade1 = PremiumFactor.builder().factorId(2L).insrClsCd("01").factorType("DISCOUNT_GRADE").factorKey("1")
                .factorNm("1등급").factorValue(new BigDecimal("0.8")).effFrom(LocalDate.of(2000, 1, 1)).build();
        givenFactorHistory("DISCOUNT_GRADE", grade1);

        FactorRevisionResponse revised = service.reviseFactor("tx-1", "A500", FactorRevisionRequest.builder()
                .insrClsCd("01").factorType("DISCOUNT_GRADE").factorKey("1")
                .factorValue(new BigDecimal("0.75")).effFrom(TODAY.plusDays(1)).build());
        assertThat(revised.getAction()).isEqualTo("REVISE");
        assertThat(grade1.getEffTo()).isEqualTo(TODAY);

        // 아직 없는 키(6등급)는 이름이 있어야 만들 수 있다
        FactorRevisionRequest grade6 = FactorRevisionRequest.builder().insrClsCd("01").factorType("DISCOUNT_GRADE")
                .factorKey("6").factorValue(new BigDecimal("1.5")).effFrom(TODAY).build();
        assertError(() -> service.reviseFactor("tx-2", "A500", grade6), "E-ADM-001");
        grade6.setFactorNm("6등급");
        assertThat(service.reviseFactor("tx-3", "A500", grade6).getAction()).isEqualTo("CREATE");
    }

    @Test
    void 계수_입력_형태와_값_오류는_저장소를_조회하기_전에_거절한다() {
        // 구간형에 키를 넣거나 구간이 잘못됨
        assertError(() -> service.reviseFactor("tx", "A", factor("DRIVER_AGE", "1", null, null)), "E-ADM-001");
        assertError(() -> service.reviseFactor("tx", "A", factor("DRIVER_AGE", null, 30, 20)), "E-ADM-001");
        assertError(() -> service.reviseFactor("tx", "A", factor("DRIVER_AGE", null, 20, 151)), "E-ADM-001");
        assertError(() -> service.reviseFactor("tx", "A", factor("DRIVER_AGE", null, null, null)), "E-ADM-001");
        // 키형에 구간을 넣거나 키가 없음/너무 김
        assertError(() -> service.reviseFactor("tx", "A", factor("DISCOUNT_GRADE", null, 1, 5)), "E-ADM-001");
        assertError(() -> service.reviseFactor("tx", "A", factor("DISCOUNT_GRADE", null, null, null)), "E-ADM-001");
        assertError(() -> service.reviseFactor("tx", "A", factor("DISCOUNT_GRADE", "K".repeat(11), null, null)), "E-ADM-001");
        // 알 수 없는 종류, 이 보험종목에서 쓰지 않는 종류
        assertError(() -> service.reviseFactor("tx", "A", factor("COLOR", "1", null, null)), "E-ADM-001");
        assertError(() -> service.reviseFactor("tx", "A", factor("STRUCTURE_GRADE", "1", null, null)), "E-ADM-001");
        // 값 범위
        for (String value : List.of("0", "-1", "10", "1.00001")) {
            FactorRevisionRequest bad = factor("DISCOUNT_GRADE", "1", null, null);
            bad.setFactorValue(new BigDecimal(value));
            assertError(() -> service.reviseFactor("tx", "A", bad), "E-ADM-001");
        }
        assertError(() -> service.reviseFactor("tx", "A", FactorRevisionRequest.builder().insrClsCd("01")
                .factorType("DISCOUNT_GRADE").factorKey("1").effFrom(TODAY).build()), "E-ADM-001");   // 값 없음
        verifyNoInteractions(factorRepository, auditRepository);
    }

    private static FactorRevisionRequest factor(String type, String key, Integer from, Integer to) {
        return FactorRevisionRequest.builder().insrClsCd("01").factorType(type).factorKey(key).rangeFrom(from)
                .rangeTo(to).factorValue(new BigDecimal("1.1")).effFrom(TODAY).build();
    }

    @Test
    void 같은_거래UUID로_이미_처리한_계수_변경은_처음_결과를_돌려주고_다른_내용은_거절한다() {
        LocalDate effFrom = TODAY.plusDays(30);
        PremiumFactor old = ageBand(18, 25, "1.5", LocalDate.of(2000, 1, 1), effFrom.minusDays(1));
        PremiumFactor created = ageBand(18, 25, "1.8", effFrom, null);
        given(auditRepository.findByTargetTypeAndGlobalTxId("FACTOR", "tx-1")).willReturn(Optional.of(PremiumAudit.builder()
                .targetType("FACTOR").targetKey("01/DRIVER_AGE/18-25").action("REVISE").afterVal("1.8").effFrom(effFrom).build()));
        given(factorRepository.findByInsrClsCdAndFactorTypeOrderByEffFromAsc("01", "DRIVER_AGE")).willReturn(List.of(old, created));
        FactorRevisionRequest same = factor("DRIVER_AGE", null, 18, 25);
        same.setFactorValue(new BigDecimal("1.80"));
        same.setEffFrom(effFrom);

        FactorRevisionResponse response = service.reviseFactor("tx-1", "A500", same);

        assertThat(response.getCurrent().getFactorValue()).isEqualByComparingTo("1.8");
        assertThat(response.getPrevious().getEffTo()).isEqualTo(effFrom.minusDays(1));
        verify(factorRepository, never()).saveAndFlush(any());

        FactorRevisionRequest other = factor("DRIVER_AGE", null, 26, 29);   // 같은 UUID, 다른 구간
        other.setEffFrom(effFrom);
        assertError(() -> service.reviseFactor("tx-1", "A500", other), "E-CTR-019");
    }

    @Test
    void 계수_목록은_종류로_거르고_정렬한다() {
        given(factorRepository.findAll()).willReturn(List.of(
                ageBand(26, 29, "1.2", LocalDate.of(2000, 1, 1), null),
                ageBand(18, 25, "1.8", TODAY.plusDays(30), null),
                ageBand(18, 25, "1.5", LocalDate.of(2000, 1, 1), TODAY.plusDays(29)),
                PremiumFactor.builder().insrClsCd("01").factorType("DISCOUNT_GRADE").factorKey("1").factorNm("1등급")
                        .factorValue(new BigDecimal("0.8")).effFrom(LocalDate.of(2000, 1, 1)).build()));

        var all = service.listFactors(new FactorListRequest());
        assertThat(all.getItems()).extracting("factorType")
                .containsExactly("DISCOUNT_GRADE", "DRIVER_AGE", "DRIVER_AGE", "DRIVER_AGE");
        assertThat(all.getItems().subList(1, 4)).extracting("rangeFrom").containsExactly(18, 18, 26);
        assertThat(all.getItems().subList(1, 4)).extracting("status").containsExactly("ACTIVE", "FUTURE", "ACTIVE");

        assertThat(service.listFactors(FactorListRequest.builder().factorType("DRIVER_AGE").build()).getItems()).hasSize(3);
        assertError(() -> service.listFactors(FactorListRequest.builder().factorType("COLOR").build()), "E-ADM-001");
        assertError(() -> service.listFactors(FactorListRequest.builder().insrClsCd("99").build()), "E-SEQ-001");
    }
}
