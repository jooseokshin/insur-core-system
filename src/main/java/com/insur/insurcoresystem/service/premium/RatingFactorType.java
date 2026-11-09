package com.insur.insurcoresystem.service.premium;

import com.insur.insurcoresystem.service.premium.dto.RatingInput;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 요율 계수 종류. 계수표(TB_PRM_FACTOR.FACTOR_TYPE)와 필수 계수 정책, 보험료 산출 입력, 요율 관리 화면이 이 정의 하나를 함께 쓴다.
 * <ul>
 *     <li>범위형(range): 숫자 입력이 구간(RANGE_FROM~RANGE_TO)에 들어가는 행을 찾는다. 예: 운전자 나이</li>
 *     <li>키형: 입력 코드가 FACTOR_KEY 와 같은 행을 찾는다. 예: 할인할증 등급</li>
 *     <li>입력 필드가 없는 계수(판매채널)는 고객·직원이 입력하지 않고 서버가 요청 주체에서 정한다. 필수 정책 대상이 아니고,
 *         계수표에 그 채널의 행이 없으면 적용하지 않는다(1.0).</li>
 * </ul>
 */
public enum RatingFactorType {

    DRIVER_AGE(true, "driverAge", Set.of("01"), r -> r.getDriverAge() == null ? null : String.valueOf(r.getDriverAge())),
    DISCOUNT_GRADE(false, "discountGrade", Set.of("01"), RatingInput::getDiscountGrade),
    STRUCTURE_GRADE(false, "structureGrade", Set.of("02"), RatingInput::getStructureGrade),
    /** 판매채널(10 대면, 20 다이렉트, 30 TM). 서버가 정한다 */
    SALES_CHANNEL(false, null, Set.of("01", "02"), r -> null);

    private final boolean range;
    private final String inputField;
    private final Set<String> insrClsCds;
    private final Function<RatingInput, String> reader;

    RatingFactorType(boolean range, String inputField, Set<String> insrClsCds, Function<RatingInput, String> reader) {
        this.range = range;
        this.inputField = inputField;
        this.insrClsCds = insrClsCds;
        this.reader = reader;
    }

    /** 숫자 구간으로 찾는 계수인지 */
    public boolean isRange() {
        return range;
    }

    /** 요청 rating 안의 입력 필드 이름. 서버가 정하는 계수는 null */
    public String inputField() {
        return inputField;
    }

    /** 고객·직원이 입력하는 계수인지 (필수 정책을 걸 수 있는지) */
    public boolean isUserInput() {
        return inputField != null;
    }

    /** 이 보험종목에서 쓰는 계수인지 */
    public boolean appliesTo(String insrClsCd) {
        return insrClsCds.contains(insrClsCd);
    }

    /** 입력값 (없으면 null). 범위형은 숫자를 문자열로 */
    public String read(RatingInput rating) {
        return rating == null ? null : reader.apply(rating);
    }

    public static Optional<RatingFactorType> find(String code) {
        return Arrays.stream(values()).filter(t -> t.name().equals(code)).findFirst();
    }
}
