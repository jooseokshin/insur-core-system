package com.insur.insurcoresystem.service.premium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.premium.PremiumFactor;
import com.insur.insurcoresystem.domain.premium.PremiumFactorPolicy;
import com.insur.insurcoresystem.domain.premium.PremiumFactorPolicyRepository;
import com.insur.insurcoresystem.domain.premium.PremiumFactorRepository;
import com.insur.insurcoresystem.domain.premium.PremiumRate;
import com.insur.insurcoresystem.domain.premium.PremiumRateRepository;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteRequest;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteRequest.QuoteCoverageRequest;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteResponse;
import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PremiumServiceTest {

    @Mock
    PremiumRateRepository premiumRateRepository;
    @Mock
    PremiumFactorRepository premiumFactorRepository;
    @Mock
    PremiumFactorPolicyRepository premiumFactorPolicyRepository;
    @InjectMocks
    PremiumService premiumService;

    private static PremiumRate rate(String code, String name, String annualRate) {
        return PremiumRate.builder().insrClsCd("01").cvrgCd(code).cvrgNm(name).annualRate(new BigDecimal(annualRate)).build();
    }

    private static QuoteCoverageRequest coverage(String code, String amount) {
        return QuoteCoverageRequest.builder().cvrgCd(code).isrdAmt(new BigDecimal(amount)).build();
    }

    private static PremiumQuoteRequest request(QuoteCoverageRequest... coverages) {
        return PremiumQuoteRequest.builder().insrClsCd("01")
                .startDate(LocalDate.of(2026, 10, 3)).endDate(LocalDate.of(2027, 10, 3))
                .coverages(Arrays.asList(coverages)).build();
    }

    @Test
    void 담보별_보험료와_합계를_요청한_순서대로_산출한다() {
        given(premiumRateRepository.findEffective(eq("01"), any(), any())).willReturn(List.of(
                rate("CA002", "대물배상", "0.0175"), rate("CA001", "대인배상", "0.005")));

        PremiumQuoteResponse response = premiumService.quote(
                request(coverage("CA001", "100000000"), coverage("CA002", "20000000")));

        assertThat(response.getDays()).isEqualTo(365);
        assertThat(response.getCoverages()).extracting("cvrgCd").containsExactly("CA001", "CA002");
        assertThat(response.getCoverages().get(0).getCvrgNm()).isEqualTo("대인배상");
        assertThat(response.getCoverages().get(0).getAnnualRate()).isEqualByComparingTo("0.005");
        assertThat(response.getCoverages().get(0).getPrem()).isEqualByComparingTo("500000");
        assertThat(response.getCoverages().get(1).getPrem()).isEqualByComparingTo("350000");
        assertThat(response.getTotalPremium()).isEqualByComparingTo("850000");
    }

    @Test
    void 총보험료는_원_단위로_반올림된_담보별_보험료의_합이다() {
        // 두 담보 모두 정확한 값이 x.5 가 아닌 소수가 되도록: 각각 반올림한 값의 합과 같아야 청약 접수 검증을 통과한다
        given(premiumRateRepository.findEffective(eq("01"), any(), any())).willReturn(List.of(
                rate("CA001", "대인배상", "0.005"), rate("CA002", "대물배상", "0.0175")));
        PremiumQuoteRequest request = request(coverage("CA001", "100000001"), coverage("CA002", "20000001"));
        request.setEndDate(LocalDate.of(2027, 4, 3));   // 182일

        PremiumQuoteResponse response = premiumService.quote(request);

        BigDecimal sum = response.getCoverages().stream().map(c -> c.getPrem()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(response.getTotalPremium()).isEqualByComparingTo(sum);
        assertThat(response.getCoverages()).allSatisfy(c -> assertThat(c.getPrem().scale()).isZero());
    }

    private static PremiumFactor range(String type, int from, int to, String name, String value) {
        return PremiumFactor.builder().insrClsCd("01").factorType(type).rangeFrom(from).rangeTo(to)
                .factorNm(name).factorValue(new BigDecimal(value)).build();
    }

    private static PremiumFactor key(String type, String key, String name, String value) {
        return PremiumFactor.builder().insrClsCd("01").factorType(type).factorKey(key)
                .factorNm(name).factorValue(new BigDecimal(value)).build();
    }

    private void givenCarFactors() {
        given(premiumFactorRepository.findEffective(eq("01"), any())).willReturn(List.of(
                range("DRIVER_AGE", 18, 25, "만 18~25세", "1.5000"),
                range("DRIVER_AGE", 30, 49, "만 30~49세", "1.0000"),
                range("DRIVER_AGE", 50, 64, "만 50~64세", "0.9500"),
                key("DISCOUNT_GRADE", "1", "1등급", "0.8000"),
                key("DISCOUNT_GRADE", "5", "5등급", "1.3000")));
    }

    @Test
    void 입력한_요율_계수를_곱해_담보_보험료에_반영하고_적용한_계수를_돌려준다() {
        given(premiumRateRepository.findEffective(eq("01"), any(), any()))
                .willReturn(List.of(rate("CA001", "대인배상", "0.005")));
        givenCarFactors();
        PremiumQuoteRequest request = request(coverage("CA001", "100000000"));
        request.setRating(RatingInput.builder().driverAge(22).discountGrade("1").build());

        PremiumQuoteResponse response = premiumService.quote(request);

        // 1억 × 0.5% × (1.5 × 0.8 = 1.2) = 600,000
        assertThat(response.getCombinedFactor()).isEqualByComparingTo("1.2");
        assertThat(response.getFactors()).extracting("factorType").containsExactly("DRIVER_AGE", "DISCOUNT_GRADE");
        assertThat(response.getFactors()).extracting("input").containsExactly("22", "1");
        assertThat(response.getFactors().get(0).getFactorNm()).isEqualTo("만 18~25세");
        assertThat(response.getCoverages().get(0).getAnnualRate()).isEqualByComparingTo("0.005");   // 기본 요율은 그대로
        assertThat(response.getCoverages().get(0).getPrem()).isEqualByComparingTo("600000");
        assertThat(response.getTotalPremium()).isEqualByComparingTo("600000");
    }

    private static PremiumFactorPolicy required(String type) {
        return PremiumFactorPolicy.builder().insrClsCd("01").factorType(type).required(true).build();
    }

    @Test
    void 필수_계수가_입력되지_않으면_빠진_계수와_입력_필드를_알려주며_거절한다() {
        given(premiumFactorPolicyRepository.findRequired("01", LocalDate.of(2026, 10, 3))).willReturn(List.of(
                required("DISCOUNT_GRADE"), required("DRIVER_AGE")));

        // 계수를 아예 입력하지 않음: 필수 두 가지가 모두 빠졌다 (종류 이름순으로 안내)
        assertThatThrownBy(() -> premiumService.quote(request(coverage("CA001", "100000000"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DISCOUNT_GRADE(discountGrade)").hasMessageContaining("DRIVER_AGE(driverAge)")
                .extracting("errorCode").isEqualTo("E-PRM-005");

        // 일부만 입력: 빠진 것만 안내한다
        PremiumQuoteRequest partial = request(coverage("CA001", "100000000"));
        partial.setRating(RatingInput.builder().driverAge(30).build());
        assertThatThrownBy(() -> premiumService.quote(partial))
                .hasMessageContaining("DISCOUNT_GRADE(discountGrade)").hasMessageNotContaining("DRIVER_AGE")
                .extracting("errorCode").isEqualTo("E-PRM-005");

        // 빈 rating 객체도 입력하지 않은 것과 같다
        PremiumQuoteRequest empty = request(coverage("CA001", "100000000"));
        empty.setRating(new RatingInput());
        assertThatThrownBy(() -> premiumService.quote(empty)).extracting("errorCode").isEqualTo("E-PRM-005");
    }

    @Test
    void 필수_계수를_모두_입력하면_산출하고_필수_계수_종류를_응답에_담는다() {
        given(premiumFactorPolicyRepository.findRequired("01", LocalDate.of(2026, 10, 3))).willReturn(List.of(
                required("DISCOUNT_GRADE"), required("DRIVER_AGE")));
        given(premiumRateRepository.findEffective(eq("01"), any(), any()))
                .willReturn(List.of(rate("CA001", "대인배상", "0.005")));
        givenCarFactors();
        PremiumQuoteRequest request = request(coverage("CA001", "100000000"));
        request.setRating(RatingInput.builder().driverAge(30).discountGrade("1").build());

        PremiumQuoteResponse response = premiumService.quote(request);

        assertThat(response.getRequiredFactors()).containsExactly("DISCOUNT_GRADE", "DRIVER_AGE");
        assertThat(response.getTotalPremium()).isEqualByComparingTo("400000");   // 1억 × 0.5% × (1.0 × 0.8)
    }

    @Test
    void 선택_계수는_입력하지_않아도_산출하고_필수_계수_목록은_비어_있다() {
        // 정책 저장소가 필수 행을 돌려주지 않음(= 이 보험시기에 필수인 계수가 없음)
        given(premiumRateRepository.findEffective(eq("01"), any(), any()))
                .willReturn(List.of(rate("CA001", "대인배상", "0.005")));

        PremiumQuoteResponse response = premiumService.quote(request(coverage("CA001", "100000000")));

        assertThat(response.getRequiredFactors()).isEmpty();
        assertThat(response.getTotalPremium()).isEqualByComparingTo("500000");
    }

    @Test
    void 요율_계수를_입력하지_않으면_계수표를_조회하지_않고_기본_요율로_산출한다() {
        given(premiumRateRepository.findEffective(eq("01"), any(), any()))
                .willReturn(List.of(rate("CA001", "대인배상", "0.005")));

        PremiumQuoteResponse noRating = premiumService.quote(request(coverage("CA001", "100000000")));
        PremiumQuoteRequest emptyRating = request(coverage("CA001", "100000000"));
        emptyRating.setRating(new RatingInput());
        PremiumQuoteResponse empty = premiumService.quote(emptyRating);

        assertThat(noRating.getFactors()).isEmpty();
        assertThat(noRating.getCombinedFactor()).isEqualByComparingTo("1");
        assertThat(noRating.getCoverages().get(0).getPrem()).isEqualByComparingTo("500000");
        assertThat(empty.getCoverages().get(0).getPrem()).isEqualByComparingTo("500000");
        org.mockito.Mockito.verifyNoInteractions(premiumFactorRepository);
    }

    @Test
    void 구간의_양_끝값도_해당_구간의_계수를_쓴다() {
        given(premiumRateRepository.findEffective(eq("01"), any(), any()))
                .willReturn(List.of(rate("CA001", "대인배상", "0.005")));
        givenCarFactors();
        for (int[] ageAndExpected : new int[][] {{18, 750000}, {25, 750000}, {30, 500000}, {49, 500000}, {50, 475000}}) {
            PremiumQuoteRequest request = request(coverage("CA001", "100000000"));
            request.setRating(RatingInput.builder().driverAge(ageAndExpected[0]).build());

            assertThat(premiumService.quote(request).getTotalPremium())
                    .as("만 %d세", ageAndExpected[0]).isEqualByComparingTo(String.valueOf(ageAndExpected[1]));
        }
    }

    @Test
    void 계수표에_없는_입력값과_보험종목에서_쓰지_않는_계수는_거절한다() {
        given(premiumFactorRepository.findEffective(eq("01"), any())).willReturn(List.of(
                range("DRIVER_AGE", 18, 25, "만 18~25세", "1.5000"), key("DISCOUNT_GRADE", "1", "1등급", "0.8000")));

        PremiumQuoteRequest tooYoung = request(coverage("CA001", "1000"));
        tooYoung.setRating(RatingInput.builder().driverAge(17).build());           // 구간 밖
        PremiumQuoteRequest badGrade = request(coverage("CA001", "1000"));
        badGrade.setRating(RatingInput.builder().discountGrade("9").build());      // 없는 등급
        PremiumQuoteRequest wrongProduct = request(coverage("CA001", "1000"));
        wrongProduct.setRating(RatingInput.builder().structureGrade("1").build()); // 자동차에서 쓰지 않는 계수

        assertError(tooYoung, "E-PRM-004");
        assertError(badGrade, "E-PRM-004");
        assertError(wrongProduct, "E-PRM-003");
    }

    @Test
    void 요율과_계수는_보험시기를_기준일로_조회하고_개정_전후_행이_겹치면_늦은_행을_쓴다() {
        PremiumRate older = PremiumRate.builder().insrClsCd("01").cvrgCd("CA001").cvrgNm("대인배상")
                .annualRate(new BigDecimal("0.005")).build();
        PremiumRate latest = PremiumRate.builder().insrClsCd("01").cvrgCd("CA001").cvrgNm("대인배상")
                .annualRate(new BigDecimal("0.006")).build();
        // 조회 쿼리는 적용 시작일이 늦은 행을 먼저 돌려준다
        given(premiumRateRepository.findEffective(eq("01"), any(), eq(LocalDate.of(2027, 1, 15))))
                .willReturn(List.of(latest, older));
        PremiumQuoteRequest request = request(coverage("CA001", "100000000"));
        request.setStartDate(LocalDate.of(2027, 1, 15));
        request.setEndDate(LocalDate.of(2028, 1, 15));

        PremiumQuoteResponse response = premiumService.quote(request);

        assertThat(response.getCoverages().get(0).getAnnualRate()).isEqualByComparingTo("0.006");
        assertThat(response.getTotalPremium()).isEqualByComparingTo("600000");
    }

    @Test
    void 보험시기에_적용되는_요율이_없으면_보험시기를_알려주며_산출할_수_없다() {
        given(premiumRateRepository.findEffective(eq("01"), any(), eq(LocalDate.of(2026, 10, 3)))).willReturn(List.of());

        assertThatThrownBy(() -> premiumService.quote(request(coverage("CA001", "100000000"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2026-10-03").hasMessageContaining("CA001")
                .extracting("errorCode").isEqualTo("E-PRM-001");
    }

    @Test
    void 요율표에_없는_담보는_산출할_수_없다() {
        given(premiumRateRepository.findEffective(eq("01"), any(), any()))
                .willReturn(List.of(rate("CA001", "대인배상", "0.005")));

        assertThatThrownBy(() -> premiumService.quote(request(coverage("CA001", "100000000"), coverage("FI001", "5000"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FI001")
                .extracting("errorCode").isEqualTo("E-PRM-001");
    }

    @Test
    void 입력_오류는_요율을_조회하기_전에_예외가_발생한다() {
        assertError(PremiumQuoteRequest.builder().insrClsCd("99").build(), "E-SEQ-001");

        PremiumQuoteRequest noDates = request(coverage("CA001", "1000"));
        noDates.setStartDate(null);
        assertError(noDates, "E-CTR-004");
        PremiumQuoteRequest reversed = request(coverage("CA001", "1000"));
        reversed.setEndDate(reversed.getStartDate());
        assertError(reversed, "E-CTR-004");

        assertError(request(), "E-CTR-001");
        assertError(request((QuoteCoverageRequest) null), "E-CTR-001");
        assertError(request(coverage(" ", "1000")), "E-CTR-006");
        assertError(request(coverage("X".repeat(11), "1000")), "E-CTR-006");
        assertError(request(coverage("CA001", "0")), "E-CTR-007");
        assertError(request(coverage("CA001", "-5")), "E-CTR-007");
        assertError(request(coverage("CA001", "1000.5")), "E-CTR-007");
        assertError(request(coverage("CA001", "1000000000000000")), "E-CTR-007");
        assertError(request(coverage("CA001", "1000"), coverage("CA001", "2000")), "E-PRM-002");

        verifyNoInteractions(premiumRateRepository);
    }

    private void assertError(PremiumQuoteRequest request, String errorCode) {
        assertThatThrownBy(() -> premiumService.quote(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
