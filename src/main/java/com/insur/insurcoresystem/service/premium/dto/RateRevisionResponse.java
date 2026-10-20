package com.insur.insurcoresystem.service.premium.dto;

import com.insur.insurcoresystem.service.premium.dto.RateListResponse.RateItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요율 개정/신규 응답 바디
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateRevisionResponse {

    /** 수행한 동작: CREATE(새 담보 요율), REVISE(기존 요율 개정) */
    @Schema(description = "수행한 동작: CREATE(새 담보 요율), REVISE(기존 요율 개정)")
    private String action;
    /** 새로 추가된 요율 행 */
    @Schema(description = "새로 추가된 요율 행")
    private RateItem current;
    /** 개정하면서 적용 종료일을 닫은 이전 행. 새로 만든 경우 또는 닫을 필요가 없었던 경우 null */
    @Schema(description = "개정하면서 적용 종료일을 닫은 이전 행. 새로 만든 경우 또는 닫을 필요가 없었던 경우 null")
    private RateItem previous;
}
