package com.insur.insurcoresystem.service.product;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.domain.premium.PremiumRate;
import com.insur.insurcoresystem.domain.premium.PremiumRateRepository;
import com.insur.insurcoresystem.domain.product.Product;
import com.insur.insurcoresystem.domain.product.ProductCoverage;
import com.insur.insurcoresystem.domain.product.ProductRepository;
import com.insur.insurcoresystem.service.product.dto.ProductListRequest;
import com.insur.insurcoresystem.service.product.dto.ProductListResponse;
import com.insur.insurcoresystem.service.product.dto.ProductResponse;
import com.insur.insurcoresystem.service.sequence.ProductType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 상품 조회와 상품 구성 검증.
 * <ul>
 *     <li>구성 검증: 담보가 상품에 있는지, 필수 담보(주계약)를 모두 넣었는지, 가입금액이 담보별 한도 안인지. 산출·청약·갱신 모두 같은 규칙이다.</li>
 *     <li>판매 기간: 새 청약(과 그 사전 산출)만 오늘 판매 중인 상품이어야 한다. 갱신은 원 계약의 상품을 그대로 쓴다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final PremiumRateRepository premiumRateRepository;
    private final Clock clock;

    /** 담보코드와 가입금액 (구성 검증 입력) */
    public record CoverageAmount(String cvrgCd, BigDecimal isrdAmt) {
    }

    /**
     * 상품 목록을 조회한다.
     *
     * @throws BusinessException 지원하지 않는 보험종목(E-SEQ-001)
     */
    @Transactional(readOnly = true)
    public ProductListResponse list(ProductListRequest request) {
        LocalDate today = LocalDate.now(clock);
        List<Product> products;
        if (StringUtils.hasText(request.getPrdCd())) {
            products = productRepository.findById(request.getPrdCd()).stream().toList();
        } else if (StringUtils.hasText(request.getInsrClsCd())) {
            ProductType.fromInsrClsCd(request.getInsrClsCd());
            products = productRepository.findByInsrClsCdOrderByPrdCd(request.getInsrClsCd());
        } else {
            products = productRepository.findAllByOrderByInsrClsCdAscPrdCdAsc();
        }
        boolean onSaleOnly = Boolean.TRUE.equals(request.getOnSaleOnly());
        return ProductListResponse.builder()
                .items(products.stream()
                        .filter(p -> !onSaleOnly || p.isOnSale(today))
                        .map(p -> toResponse(p, today))
                        .toList())
                .build();
    }

    /**
     * 상품을 찾는다.
     *
     * @throws BusinessException 없는 상품(E-PRD-001), 보험종목이 다른 상품(E-PRD-002)
     */
    @Transactional(readOnly = true)
    public Product require(String prdCd, String insrClsCd) {
        Product product = productRepository.findById(prdCd)
                .orElseThrow(() -> new BusinessException("E-PRD-001", "없는 상품코드입니다: " + prdCd));
        if (!product.getInsrClsCd().equals(insrClsCd)) {
            throw new BusinessException("E-PRD-002",
                    "상품 " + prdCd + "은(는) 보험종목 " + product.getInsrClsCd() + "의 상품입니다. (요청 보험종목: " + insrClsCd + ")");
        }
        return product;
    }

    /**
     * 새 청약에 쓸 수 있도록 오늘 판매 중인 상품인지 확인한다.
     *
     * @throws BusinessException 없는 상품(E-PRD-001), 보험종목 불일치(E-PRD-002), 판매 기간이 아닌 상품(E-PRD-003)
     */
    @Transactional(readOnly = true)
    public Product requireOnSale(String prdCd, String insrClsCd) {
        Product product = require(prdCd, insrClsCd);
        LocalDate today = LocalDate.now(clock);
        if (!product.isOnSale(today)) {
            throw new BusinessException("E-PRD-003", "판매 기간이 아닌 상품입니다: " + prdCd + " (판매 " + product.getSaleFrom()
                    + " ~ " + (product.getSaleTo() == null ? "" : product.getSaleTo()) + ")");
        }
        return product;
    }

    /**
     * 담보 구성이 상품 규칙에 맞는지 확인한다.
     *
     * @throws BusinessException 상품 없음·종목 불일치(E-PRD-001, 002), 상품에 없는 담보(E-PRD-004), 필수 담보 누락(E-PRD-005),
     *                           가입금액 한도 위반(E-PRD-006)
     */
    @Transactional(readOnly = true)
    public Product validateComposition(String prdCd, String insrClsCd, List<CoverageAmount> coverages) {
        Product product = require(prdCd, insrClsCd);
        for (CoverageAmount requested : coverages) {
            ProductCoverage rule = product.coverage(requested.cvrgCd()).orElseThrow(() -> new BusinessException("E-PRD-004",
                    "상품 " + prdCd + "에 없는 담보입니다: " + requested.cvrgCd()));
            if (requested.isrdAmt().compareTo(rule.getMinIsrdAmt()) < 0 || requested.isrdAmt().compareTo(rule.getMaxIsrdAmt()) > 0) {
                throw new BusinessException("E-PRD-006", "담보 " + requested.cvrgCd() + "의 가입금액은 "
                        + rule.getMinIsrdAmt().toPlainString() + "~" + rule.getMaxIsrdAmt().toPlainString() + "원이어야 합니다.");
            }
        }
        Set<String> chosen = coverages.stream().map(CoverageAmount::cvrgCd).collect(Collectors.toSet());
        List<String> missing = product.getCoverages().stream()
                .filter(ProductCoverage::isRequired)
                .map(ProductCoverage::getCvrgCd)
                .filter(code -> !chosen.contains(code))
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException("E-PRD-005", "상품 " + prdCd + "의 필수 담보가 빠졌습니다: " + String.join(", ", missing));
        }
        return product;
    }

    /**
     * 보험기간 일수가 상품이 허용하는 범위 안인지 확인한다. (예: 해외여행 1~90일, 운전자 1년)
     *
     * @throws BusinessException 상품 없음·종목 불일치(E-PRD-001, 002), 허용 범위 밖의 보험기간(E-PRD-007)
     */
    @Transactional(readOnly = true)
    public void validateTerm(String prdCd, String insrClsCd, long days) {
        Product product = require(prdCd, insrClsCd);
        if (!product.allowsTerm(days)) {
            throw new BusinessException("E-PRD-007", "상품 " + prdCd + "의 보험기간은 "
                    + (product.getMinTermDays() == null ? "" : product.getMinTermDays() + "일 이상 ")
                    + (product.getMaxTermDays() == null ? "" : product.getMaxTermDays() + "일 이하")
                    + "여야 합니다. (요청 " + days + "일)");
        }
    }

    private ProductResponse toResponse(Product product, LocalDate today) {
        Map<String, String> names = premiumRateRepository.findEffective(product.getInsrClsCd(),
                        product.getCoverages().stream().map(ProductCoverage::getCvrgCd).toList(), today)
                .stream().collect(Collectors.toMap(PremiumRate::getCvrgCd, PremiumRate::getCvrgNm, (a, b) -> a));
        return ProductResponse.builder()
                .prdCd(product.getPrdCd())
                .prdNm(product.getPrdNm())
                .insrClsCd(product.getInsrClsCd())
                .prdDesc(product.getPrdDesc())
                .saleFrom(product.getSaleFrom())
                .saleTo(product.getSaleTo())
                .minTermDays(product.getMinTermDays())
                .maxTermDays(product.getMaxTermDays())
                .onSale(product.isOnSale(today))
                .coverages(product.getCoverages().stream().map(c -> ProductResponse.Coverage.builder()
                        .cvrgCd(c.getCvrgCd())
                        .cvrgNm(names.get(c.getCvrgCd()))
                        .cvrgType(c.getCvrgType())
                        .required(c.isRequired())
                        .minIsrdAmt(c.getMinIsrdAmt())
                        .maxIsrdAmt(c.getMaxIsrdAmt())
                        .deductibleAmt(c.getDeductibleAmt())
                        .build()).toList())
                .build();
    }

    /** 상품명 조회용 (없는 코드면 null) */
    @Transactional(readOnly = true)
    public Map<String, String> names(Set<String> prdCds) {
        return productRepository.findAllById(prdCds).stream()
                .collect(Collectors.toMap(Product::getPrdCd, Product::getPrdNm, (a, b) -> a));
    }
}
