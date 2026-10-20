package com.insur.insurcoresystem.service.premium.dto;

import com.insur.insurcoresystem.service.premium.dto.FactorListResponse.FactorItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요율 계수 개정/신규 응답 바디
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FactorRevisionResponse {

    /** 수행한 동작: CREATE(새 칸), REVISE(기존 계수 개정) */
    @Schema(description = "수행한 동작: CREATE(새 칸), REVISE(기존 계수 개정)")
    private String action;
    /** 새로 추가된 계수 행 */
    @Schema(description = "새로 추가된 계수 행")
    private FactorItem current;
    /** 개정하면서 적용 종료일을 닫은 이전 행. 새로 만든 경우 또는 닫을 필요가 없었던 경우 null */
    @Schema(description = "개정하면서 적용 종료일을 닫은 이전 행. 새로 만든 경우 또는 닫을 필요가 없었던 경우 null")
    private FactorItem previous;
}
