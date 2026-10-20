package com.insur.insurcoresystem.common.validation;

import java.math.BigDecimal;

/**
 * 여러 업무가 함께 쓰는 입력값 규칙. DB 컬럼에 담을 수 없는 값을 업무 오류로 먼저 걸러내는 데 쓴다.
 */
public final class InputRules {

    /** 금액 컬럼 NUMERIC(15,0) 의 정수부 최대 자릿수 */
    private static final int AMOUNT_MAX_DIGITS = 15;

    private InputRules() {
    }

    /** 값이 비어 있거나 컬럼 최대 길이를 넘으면 true. */
    public static boolean isInvalidCode(String value, int maxLength) {
        return value == null || value.isBlank() || value.length() > maxLength;
    }

    /** 금액이 없거나, 음수이거나, NUMERIC(15,0)에 담을 수 없으면(소수점 있음 / 15자리 초과) true. 0 은 허용한다. */
    public static boolean isInvalidAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            return true;
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() > 0 || stripped.precision() - stripped.scale() > AMOUNT_MAX_DIGITS;
    }
}
