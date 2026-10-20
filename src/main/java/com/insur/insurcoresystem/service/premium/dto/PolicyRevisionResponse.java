package com.insur.insurcoresystem.service.premium.dto;

import com.insur.insurcoresystem.service.premium.dto.PolicyListResponse.PolicyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 필수 요율 계수 정책 개정/신규 응답 바디
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyRevisionResponse {

    /** 수행한 동작: CREATE(새 정책), REVISE(기존 정책 개정) */
    @Schema(description = "수행한 동작: CREATE(새 정책), REVISE(기존 정책 개정)")
    private String action;
    /** 새로 추가된 정책 행 */
    @Schema(description = "새로 추가된 정책 행")
    private PolicyItem current;
    /** 개정하면서 적용 종료일을 닫은 이전 행. 새로 만든 경우 또는 닫을 필요가 없었던 경우 null */
    @Schema(description = "개정하면서 적용 종료일을 닫은 이전 행. 새로 만든 경우 또는 닫을 필요가 없었던 경우 null")
    private PolicyItem previous;
}
