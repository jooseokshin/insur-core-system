package com.insur.insurcoresystem.service.premium;

import com.insur.insurcoresystem.service.product.ProductService;
import org.springframework.util.StringUtils;
import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidAmount;
import static com.insur.insurcoresystem.common.validation.InputRules.isInvalidCode;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.premium.PremiumFactor;
import com.insur.insurcoresystem.domain.premium.PremiumFactorPolicy;
import com.insur.insurcoresystem.domain.premium.PremiumFactorPolicyRepository;
import com.insur.insurcoresystem.domain.premium.PremiumFactorRepository;
import com.insur.insurcoresystem.domain.premium.PremiumRate;
import com.insur.insurcoresystem.domain.premium.PremiumRateRepository;
import com.insur.insurcoresystem.service.premium.dto.AppliedFactor;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteRequest;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteRequest.QuoteCoverageRequest;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteResponse;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteResponse.QuoteCoverageResponse;
import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import com.insur.insurcoresystem.service.sequence.ProductType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보험료 산출 서비스. 아무것도 저장하지 않으며, 산출 결과는 청약 요청의 담보 보험료/총보험료로 그대로 쓸 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PremiumService {

    static final String FACTOR_DRIVER_AGE = RatingFactorType.DRIVER_AGE.name();
    static final String FACTOR_DISCOUNT_GRADE = RatingFactorType.DISCOUNT_GRADE.name();
    static final String FACTOR_STRUCTURE_GRADE = RatingFactorType.STRUCTURE_GRADE.name();

    private final PremiumRateRepository premiumRateRepository;
    private final PremiumFactorRepository premiumFactorRepository;
    private final PremiumFactorPolicyRepository premiumFactorPolicyRepository;
    private final ProductService productService;

    /**
     * 새 청약을 위한 보험료 산출 (보험료 산출 API). 상품을 지정했으면 오늘 판매 중인 상품이어야 한다. 나머지는 {@link #quote} 와 같다.
     *
     * @throws BusinessException {@link #quote} 의 오류와 판매 기간이 아닌 상품(E-PRD-003)
     */
    @Transactional(readOnly = true)
    public PremiumQuoteResponse quoteNewBusiness(PremiumQuoteRequest request) {
        if (StringUtils.hasText(request.getPrdCd())) {
            productService.requireOnSale(request.getPrdCd(), request.getInsrClsCd());
        }
        return quote(request);
    }

    /**
     * 가입금액과 보험기간, 요율 계수로 담보별 보험료와 총보험료를 산출한다. 요율과 계수는 보험시기에 적용되는 것을 쓴다. 담보별 보험료는 원 단위로 반올림하고,
     * 총보험료는 반올림된 담보별 보험료의 합이라서 청약 접수의 "총보험료 = 담보보험료 합계" 검증을 그대로 통과한다.
     *
     * @param request 보험종목, 보험기간, 요율 계수 입력(선택), 담보별 가입금액
     * @return 담보별 요율/보험료, 적용한 요율 계수, 보험기간 일수, 총보험료
     * @throws BusinessException 지원하지 않는 보험종목(E-SEQ-001), 보험기간 오류(E-CTR-004), 담보 목록/코드/금액 오류
     *                           (E-CTR-001, E-CTR-006, E-CTR-007), 담보코드 중복(E-PRM-002), 요율표에 없는 담보(E-PRM-001),
     *                           보험종목에 쓰지 않는 요율 계수 입력(E-PRM-003), 계수표에 없는 입력값(E-PRM-004),
     *                           보험시기에 필수인 요율 계수 미입력(E-PRM-005), 상품을 지정한 경우 상품 구성 위반(E-PRD-001~006, 판매 기간 제외)
     */
    @Transactional(readOnly = true)
    public PremiumQuoteResponse quote(PremiumQuoteRequest request) {
        ProductType.fromInsrClsCd(request.getInsrClsCd());
        if (request.getStartDate() == null || request.getEndDate() == null
                || !request.getEndDate().isAfter(request.getStartDate())) {
            throw new BusinessException("E-CTR-004", "보험기간이 올바르지 않습니다. 보험종기는 보험시기 이후여야 합니다.");
        }
        List<QuoteCoverageRequest> coverages = request.getCoverages();
        validateCoverages(coverages);
        if (StringUtils.hasText(request.getPrdCd())) {
            productService.validateComposition(request.getPrdCd(), request.getInsrClsCd(), coverages.stream()
                    .map(c -> new ProductService.CoverageAmount(c.getCvrgCd(), c.getIsrdAmt())).toList());
        }

        Map<String, PremiumRate> rates = premiumRateRepository
                .findEffective(request.getInsrClsCd(),
                        coverages.stream().map(QuoteCoverageRequest::getCvrgCd).toList(), request.getStartDate())
                .stream().collect(Collectors.toMap(PremiumRate::getCvrgCd, Function.identity(), (latest, older) -> latest));

        List<String> requiredTypes = requireMandatoryFactors(request);
        List<AppliedFactor> factors = resolveFactors(request.getInsrClsCd(), request.getStartDate(), request.getRating(),
                request.getSalesChnlCd());
        BigDecimal combinedFactor = factors.stream().map(AppliedFactor::getFactorValue)
                .reduce(BigDecimal.ONE, BigDecimal::multiply);

        long days = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate());
        List<QuoteCoverageResponse> results = coverages.stream().map(coverage -> {
            PremiumRate rate = rates.get(coverage.getCvrgCd());
            if (rate == null) {
                throw new BusinessException("E-PRM-001", "해당 보험종목의 보험시기(" + request.getStartDate()
                        + ")에 적용되는 요율이 없는 담보코드입니다: " + coverage.getCvrgCd());
            }
            return QuoteCoverageResponse.builder()
                    .cvrgCd(rate.getCvrgCd())
                    .cvrgNm(rate.getCvrgNm())
                    .isrdAmt(coverage.getIsrdAmt())
                    .annualRate(rate.getAnnualRate())
                    .prem(PremiumCalculator.premium(coverage.getIsrdAmt(), rate.getAnnualRate(), combinedFactor, days))
                    .build();
        }).toList();

        BigDecimal total = results.stream().map(QuoteCoverageResponse::getPrem).reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("보험료 산출: 보험종목={}, {}일, 담보 {}건, 계수 {}건(곱 {}), 총보험료={}",
                request.getInsrClsCd(), days, results.size(), factors.size(), combinedFactor, total);
        return PremiumQuoteResponse.builder()
                .insrClsCd(request.getInsrClsCd())
                .days(days)
                .requiredFactors(requiredTypes)
                .factors(factors)
                .combinedFactor(combinedFactor.stripTrailingZeros())   // 응답에는 군더더기 0 없이 (1.20000000 → 1.2). 계산에는 정확한 곱을 썼다
                .coverages(results)
                .totalPremium(total)
                .build();
    }

    /**
     * 보험시기에 입력이 필수인 요율 계수가 모두 입력됐는지 확인한다. 필수 여부는 필수 계수 정책(적용 기간별)으로 정한다.
     *
     * @return 필수인 계수 종류 (정렬된 목록, 필수가 없으면 빈 목록)
     * @throws BusinessException 필수 계수가 입력되지 않은 경우(E-PRM-005)
     */
    private List<String> requireMandatoryFactors(PremiumQuoteRequest request) {
        List<String> required = premiumFactorPolicyRepository.findRequired(request.getInsrClsCd(), request.getStartDate())
                .stream().map(PremiumFactorPolicy::getFactorType).distinct().sorted().toList();
        RatingInput rating = request.getRating() == null ? new RatingInput() : request.getRating();
        List<String> missing = required.stream().filter(type -> !isProvided(rating, type)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException("E-PRM-005", "필수 요율 계수가 입력되지 않았습니다: "
                    + missing.stream().map(type -> type + "(" + inputField(type) + ")").collect(Collectors.joining(", ")));
        }
        return required;
    }

    /** 필수 정책의 계수가 입력됐는지. 서버가 정하는 계수와 알 수 없는 종류는 입력할 방법이 없으므로 필수로 취급하지 않는다 */
    private static boolean isProvided(RatingInput rating, String factorType) {
        return RatingFactorType.find(factorType)
                .map(type -> !type.isUserInput() || type.read(rating) != null)
                .orElse(true);
    }

    /** 계수 종류에 대응하는 요청 필드 이름 (rating 안의 필드) */
    static String inputField(String factorType) {
        return RatingFactorType.find(factorType).map(RatingFactorType::inputField).orElse(factorType);
    }

    /**
     * 요율 계수 입력을 보험시기에 적용되는 계수표에서 찾아 적용할 계수 목록으로 바꾼다. 입력하지 않은 계수는 적용하지 않는다.
     * 계수 종류 순서는 {@link RatingFactorType} 선언 순서로 고정된다. 판매채널은 입력이 아니라 서버가 정한 값으로 찾고,
     * 계수표에 그 채널의 행이 없으면 적용하지 않는다(대면 채널이 보통 그렇다).
     *
     * @throws BusinessException 보험종목에서 쓰지 않는 계수를 입력한 경우(E-PRM-003), 계수표에 없는 입력값인 경우(E-PRM-004)
     */
    private List<AppliedFactor> resolveFactors(String insrClsCd, LocalDate on, RatingInput rating, String salesChnlCd) {
        boolean hasInput = rating != null && !rating.isEmpty();
        if (!hasInput && salesChnlCd == null) {
            return List.of();
        }
        List<PremiumFactor> table = premiumFactorRepository.findEffective(insrClsCd, on);
        List<AppliedFactor> applied = new ArrayList<>();
        for (RatingFactorType type : RatingFactorType.values()) {
            if (!type.isUserInput()) {
                continue;
            }
            String input = type.read(rating);
            if (input == null) {
                continue;
            }
            applied.add(findFactor(table, insrClsCd, type.name(), input, type.isRange()
                    ? factor -> factor.contains(Integer.valueOf(input))
                    : factor -> input.equals(factor.getFactorKey())));
        }
        if (salesChnlCd != null) {
            table.stream()
                    .filter(f -> RatingFactorType.SALES_CHANNEL.name().equals(f.getFactorType()) && salesChnlCd.equals(f.getFactorKey()))
                    .findFirst()
                    .ifPresent(found -> applied.add(AppliedFactor.builder()
                            .factorType(RatingFactorType.SALES_CHANNEL.name())
                            .factorNm(found.getFactorNm())
                            .input(salesChnlCd)
                            .factorValue(found.getFactorValue())
                            .build()));
        }
        return applied;
    }

    /** 계수표에서 계수 종류가 같고 입력값에 맞는 계수 하나를 찾는다. */
    private static AppliedFactor findFactor(List<PremiumFactor> table, String insrClsCd, String factorType,
                                            String input, Predicate<PremiumFactor> matches) {
        List<PremiumFactor> ofType = table.stream().filter(f -> f.getFactorType().equals(factorType)).toList();
        if (ofType.isEmpty()) {
            throw new BusinessException("E-PRM-003",
                    "보험종목 " + insrClsCd + "에서는 사용하지 않는 요율 계수입니다: " + factorType);
        }
        PremiumFactor found = ofType.stream().filter(matches).findFirst().orElseThrow(() ->
                new BusinessException("E-PRM-004", "요율 계수표에 없는 입력값입니다: " + factorType + "=" + input));
        return AppliedFactor.builder()
                .factorType(factorType)
                .factorNm(found.getFactorNm())
                .input(input)
                .factorValue(found.getFactorValue())
                .build();
    }

    /** 담보 목록이 비어 있지 않고, 항목마다 담보코드와 가입금액이 올바르며, 담보코드가 중복되지 않는지 확인한다. */
    private static void validateCoverages(List<QuoteCoverageRequest> coverages) {
        if (coverages == null || coverages.isEmpty()) {
            throw new BusinessException("E-CTR-001", "담보가 존재하지 않습니다.");
        }
        Set<String> seen = new HashSet<>();
        for (QuoteCoverageRequest coverage : coverages) {
            if (coverage == null) {
                throw new BusinessException("E-CTR-001", "담보 목록에 빈 항목이 있습니다.");
            }
            if (isInvalidCode(coverage.getCvrgCd(), 10)) {
                throw new BusinessException("E-CTR-006", "담보코드는 필수이며 10자 이하여야 합니다.");
            }
            if (isInvalidAmount(coverage.getIsrdAmt()) || coverage.getIsrdAmt().signum() == 0) {
                throw new BusinessException("E-CTR-007",
                        "가입금액은 0보다 크고 15자리 이하인 정수여야 합니다. (담보코드: " + coverage.getCvrgCd() + ")");
            }
            if (!seen.add(coverage.getCvrgCd())) {
                throw new BusinessException("E-PRM-002", "담보코드가 중복되었습니다: " + coverage.getCvrgCd());
            }
        }
    }
}
