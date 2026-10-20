package com.insur.insurcoresystem.service.sequence;

import com.insur.insurcoresystem.common.exception.BusinessException;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 채번용 상품구분 (보험종목코드 매핑)
 */
@Getter
@RequiredArgsConstructor
public enum ProductType {

    CAR("01"),
    FIRE("02");

    private final String insrClsCd;

    /**
     * 보험종목코드로 상품구분을 조회한다.
     *
     * @param insrClsCd 보험종목코드 (01: 자동차, 02: 화재)
     * @return 코드에 대응하는 상품구분
     * @throws BusinessException 정의되지 않은 보험종목코드인 경우(E-SEQ-001)
     */
    public static ProductType fromInsrClsCd(String insrClsCd) {
        return Arrays.stream(values())
                .filter(type -> type.insrClsCd.equals(insrClsCd))
                .findFirst()
                .orElseThrow(() -> new BusinessException("E-SEQ-001", "지원하지 않는 보험종목코드입니다: " + insrClsCd));
    }
}
