package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.stats.LossRatioService;
import com.insur.insurcoresystem.service.stats.dto.LossRatioRequest;
import com.insur.insurcoresystem.service.stats.dto.LossRatioResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 경영·심사 통계 컨트롤러
 */
@Tag(name = "통계", description = "손해율 등 상품·요율 관리용 집계")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final LossRatioService lossRatioService;

    /**
     * 손해율을 집계한다. (POST /api/stats/loss-ratio, 심사·보상·ADMIN)
     *
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 기간 오류(E-STAT-001), 묶는 단위 오류(E-STAT-002)
     */
    @Operation(summary = "손해율을 집계한다", description = "기간과 묶는 단위(보험종목·상품·담보)별로 경과보험료, 지급 보험금, 미지급 추산액, 발생손해액, 손해율을 계산한다. 사고년도 기준을 단순화한 정의이며 IBNR 은 포함하지 않는다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 기간 오류(E-STAT-001), 묶는 단위 오류(E-STAT-002)")
    @PostMapping("/loss-ratio")
    public CommonMessage<LossRatioResponse> lossRatio(@RequestBody CommonMessage<LossRatioRequest> request) {
        return success(request.getHeader(), lossRatioService.lossRatio(requireBody(request)));
    }
}
