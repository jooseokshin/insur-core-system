package com.insur.insurcoresystem.service.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.insur.insurcoresystem.service.contract.dto.ContractApplyRequest;
import com.insur.insurcoresystem.service.contract.dto.CoverageRequest;
import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void 지문은_소문자_16진수_64자다() {
        assertThat(RequestFingerprint.of(request())).matches("[0-9a-f]{64}");
    }

    @Test
    void 같은_내용이면_금액_표기나_담보_순서가_달라도_같은_지문이다() {
        ContractApplyRequest scaled = request();
        scaled.setTotalPremium(new BigDecimal("850000.00"));
        scaled.setCoverages(List.of(coverage("CA002", "20000000.0", "350000.00"), coverage("CA001", "100000000", "500000")));

        assertThat(RequestFingerprint.of(scaled)).isEqualTo(RequestFingerprint.of(request()));
    }

    @Test
    void 업무_필드가_하나라도_다르면_다른_지문이다() {
        String base = RequestFingerprint.of(request());

        assertThat(changed(r -> r.setInsrClsCd("02"))).isNotEqualTo(base);
        assertThat(changed(r -> r.setStartDate(LocalDate.of(2026, 10, 4)))).isNotEqualTo(base);
        assertThat(changed(r -> r.setEndDate(LocalDate.of(2027, 10, 4)))).isNotEqualTo(base);
        assertThat(changed(r -> r.setPolicyholderId("C9999"))).isNotEqualTo(base);
        assertThat(changed(r -> r.setInsuredId("C9999"))).isNotEqualTo(base);
        assertThat(changed(r -> r.setTotalPremium(new BigDecimal("850001")))).isNotEqualTo(base);
        assertThat(changed(r -> r.setCoverages(List.of(coverage("CA001", "100000000", "500000"))))).isNotEqualTo(base);
        assertThat(changed(r -> r.setCoverages(List.of(coverage("CA001", "100000001", "500000"),
                coverage("CA002", "20000000", "350000"))))).isNotEqualTo(base);
    }

    @Test
    void 요율_계수_입력은_지문에_반영되지만_비어_있으면_계수가_없는_요청과_같다() {
        String base = RequestFingerprint.of(request());

        assertThat(changed(r -> r.setRating(new RatingInput()))).isEqualTo(base);   // 계수 도입 전 계약의 지문과 호환
        String age30 = changed(r -> r.setRating(RatingInput.builder().driverAge(30).build()));
        assertThat(age30).isNotEqualTo(base);
        assertThat(changed(r -> r.setRating(RatingInput.builder().driverAge(31).build()))).isNotEqualTo(age30);
        assertThat(changed(r -> r.setRating(RatingInput.builder().discountGrade("30").build()))).isNotEqualTo(age30);
        // 어느 계수에 들어갔는지가 구분된다 (나이 3 + 등급 없음 vs 나이 없음 + 등급 3)
        assertThat(changed(r -> r.setRating(RatingInput.builder().driverAge(3).build())))
                .isNotEqualTo(changed(r -> r.setRating(RatingInput.builder().discountGrade("3").build())));
    }

    @Test
    void 값_사이의_경계가_달라지는_조합은_같은_지문이_되지_않는다() {
        // 계약자 "AB" + 피보험자 "C" 와 계약자 "A" + 피보험자 "BC" 처럼 이어 붙이면 같아지는 경우
        String first = changed(r -> { r.setPolicyholderId("AB"); r.setInsuredId("C"); });
        String second = changed(r -> { r.setPolicyholderId("A"); r.setInsuredId("BC"); });

        assertThat(first).isNotEqualTo(second);
    }

    private static String changed(Consumer<ContractApplyRequest> change) {
        ContractApplyRequest request = request();
        change.accept(request);
        return RequestFingerprint.of(request);
    }

    private static ContractApplyRequest request() {
        return ContractApplyRequest.builder()
                .insrClsCd("01")
                .startDate(LocalDate.of(2026, 10, 3))
                .endDate(LocalDate.of(2027, 10, 3))
                .policyholderId("C0001")
                .insuredId("C0002")
                .totalPremium(new BigDecimal("850000"))
                .coverages(List.of(coverage("CA001", "100000000", "500000"), coverage("CA002", "20000000", "350000")))
                .build();
    }

    private static CoverageRequest coverage(String code, String isrdAmt, String prem) {
        return CoverageRequest.builder().cvrgCd(code).isrdAmt(new BigDecimal(isrdAmt)).prem(new BigDecimal(prem)).build();
    }
}
