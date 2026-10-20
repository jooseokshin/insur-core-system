package com.insur.insurcoresystem.service.premium.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 보험료 요율 계수 입력. 모두 선택이며, 입력하지 않은 계수는 적용하지 않는다(계수 1.0).
 * 보험종목에 맞는 입력만 쓸 수 있다. (자동차: 운전자 연령, 할인할증 등급 / 화재: 건물 구조 등급)
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RatingInput {

    /** 운전자 만 나이 (자동차). 연령 구간 계수로 바뀐다 */
    @Schema(description = "운전자 만 나이 (자동차). 연령 구간 계수로 바뀐다")
    private Integer driverAge;
    /** 할인할증 등급 (자동차, 1~5) */
    @Schema(description = "할인할증 등급 (자동차, 1~5)")
    private String discountGrade;
    /** 건물 구조 등급 (화재, 1~3) */
    @Schema(description = "건물 구조 등급 (화재, 1~3)")
    private String structureGrade;

    /** 입력된 값이 하나도 없는지 */
    @Schema(hidden = true)
    public boolean isEmpty() {
        return driverAge == null && discountGrade == null && structureGrade == null;
    }
}
