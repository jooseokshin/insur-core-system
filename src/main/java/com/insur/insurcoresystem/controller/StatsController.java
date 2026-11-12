package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.stats.LossRatioService;
import com.insur.insurcoresystem.service.stats.RateImpactService;
import com.insur.insurcoresystem.service.stats.ReserveService;
import com.insur.insurcoresystem.service.stats.dto.IbnrRequest;
import com.insur.insurcoresystem.service.stats.dto.IbnrResponse;
import com.insur.insurcoresystem.service.stats.dto.LossRatioRequest;
import com.insur.insurcoresystem.service.stats.dto.LossRatioResponse;
import com.insur.insurcoresystem.service.stats.dto.RateImpactRequest;
import com.insur.insurcoresystem.service.stats.dto.RateImpactResponse;
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
@Tag(name = "통계", description = "손해율, 요율 영향 시뮬레이션 등 상품·요율 관리용 집계")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final LossRatioService lossRatioService;
    private final RateImpactService rateImpactService;
    private final ReserveService reserveService;

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

    /**
     * 요율 영향을 시뮬레이션한다. (POST /api/stats/rate-impact, 심사·ADMIN, 저장하지 않음)
     *
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 기간 오류(E-STAT-001), 보험종목·요율표 없음(E-STAT-003),
     *                           변경 내용 오류(E-STAT-004), 목표 손해율 범위 오류(E-STAT-005), 갱신 영향 기간 범위 오류(E-STAT-006)
     */
    @Operation(summary = "요율 영향을 시뮬레이션한다", description = "담보별 기본 요율 변경률을 넣으면 경험 기간 손해율이 새 요율에서 얼마였을지, 목표 손해율 대비 필요 변경률(손해율법)과 신뢰도(1,082건 기준) 반영 변경률, 앞으로 만기가 오는 유효 계약의 갱신 보험료 증감을 계산한다. 아무것도 저장하지 않는다. 추세·IBNR·가격 탄력성·온레벨은 반영하지 않으며 해당하면 warnings 로 알린다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 기간 오류(E-STAT-001), 보험종목·요율표 없음(E-STAT-003), 변경 내용 오류(E-STAT-004), 목표 손해율 범위 오류(E-STAT-005), 갱신 영향 기간 범위 오류(E-STAT-006)")
    @PostMapping("/rate-impact")
    public CommonMessage<RateImpactResponse> rateImpact(@RequestBody CommonMessage<RateImpactRequest> request) {
        return success(request.getHeader(), rateImpactService.simulate(requireBody(request)));
    }

    /**
     * IBNR 을 산출한다. (POST /api/stats/ibnr, 심사·보상·ADMIN, 저장하지 않음)
     *
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 조건 오류(E-RSV-001)
     */
    @Operation(summary = "IBNR 을 산출한다", description = "보험종목의 지급 기준 손해 진전 삼각형으로 체인래더·본휴터-퍼거슨 IBNR 을 계산한다. 진전율 50% 이상인 사고 기간은 체인래더, 미만은 BF 를 고르고, 기대손해율을 주지 않으면 케이프코드로 실적에서 구한다. 지급준비금 = 개별추산액 + IBNR. 저장하지 않는다(확정은 분기 결산 배치).\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 조건 오류(E-RSV-001)")
    @PostMapping("/ibnr")
    public CommonMessage<IbnrResponse> ibnr(@RequestBody CommonMessage<IbnrRequest> request) {
        return success(request.getHeader(), reserveService.ibnr(requireBody(request)));
    }
}
