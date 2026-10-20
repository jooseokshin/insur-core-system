package com.insur.insurcoresystem.service.contract;

import com.insur.insurcoresystem.service.contract.dto.ContractApplyRequest;
import com.insur.insurcoresystem.service.contract.dto.CoverageRequest;
import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 청약 요청 내용의 지문. 같은 거래 UUID 로 재전송이 들어왔을 때 처음 접수한 요청과 내용이 같은지 비교하는 데 쓴다.
 * <ul>
 *     <li>업무 필드(보험종목, 보험기간, 계약자/피보험자, 총보험료, 요율 계수 입력, 담보)만 포함한다. 거래 UUID 와 사번은 포함하지 않는다.</li>
 *     <li>금액은 자릿수 표기(150000 / 150000.00)가 달라도 같은 값으로 본다.</li>
 *     <li>담보는 순서가 달라도 같은 요청으로 본다.</li>
 * </ul>
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    /**
     * 요청 내용의 SHA-256 지문을 16진수 64자로 만든다. 값 사이 경계가 모호해지지 않도록 각 값을 "길이:값" 형태로 이어 붙인다.
     *
     * @param request 청약 요청 바디
     * @return 소문자 16진수 64자
     */
    public static String of(ContractApplyRequest request) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.getInsrClsCd());
        append(canonical, request.getStartDate());
        append(canonical, request.getEndDate());
        append(canonical, request.getPolicyholderId());
        append(canonical, request.getInsuredId());
        append(canonical, amount(request.getTotalPremium()));
        // 요율 계수 입력이 있을 때만 지문에 넣는다. 계수 도입 전에 접수한 계약의 지문과 계수 없는 요청의 지문이 계속 같도록 하기 위해서다.
        RatingInput rating = request.getRating();
        if (rating != null && !rating.isEmpty()) {
            append(canonical, "rating");
            append(canonical, rating.getDriverAge());
            append(canonical, rating.getDiscountGrade());
            append(canonical, rating.getStructureGrade());
        }

        List<CoverageRequest> coverages = request.getCoverages() == null ? List.of() : request.getCoverages();
        coverages.stream()
                .sorted(Comparator.comparing((CoverageRequest c) -> String.valueOf(c.getCvrgCd()))
                        .thenComparing(c -> amount(c.getIsrdAmt()))
                        .thenComparing(c -> amount(c.getPrem())))
                .forEach(coverage -> {
                    append(canonical, coverage.getCvrgCd());
                    append(canonical, amount(coverage.getIsrdAmt()));
                    append(canonical, amount(coverage.getPrem()));
                });
        return sha256Hex(canonical.toString());
    }

    /** 금액을 자릿수 표기와 무관한 문자열로 바꾼다. (150000.00 → 150000) */
    private static String amount(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static void append(StringBuilder canonical, Object value) {
        String text = value == null ? "\u0000null" : value.toString();
        canonical.append(text.length()).append(':').append(text).append(';');
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 지원하지 않는 JVM 입니다.", e);   // 모든 JVM 이 지원하는 알고리즘
        }
    }
}
