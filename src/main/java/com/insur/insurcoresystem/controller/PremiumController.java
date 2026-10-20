package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.premium.PremiumService;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteRequest;
import com.insur.insurcoresystem.service.premium.dto.PremiumQuoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보험료 산출 컨트롤러
 */
@Tag(name = "보험료 산출", description = "요율표와 계수로 보험료를 산출한다")
@RestController
@RequestMapping("/api/premium")
@RequiredArgsConstructor
public class PremiumController {

    private final PremiumService premiumService;

    /**
     * 보험료를 산출한다. (POST /api/premium/quote)
     * 가입금액과 보험기간으로 담보별 보험료와 총보험료를 계산해 돌려줄 뿐 아무것도 저장하지 않는다.
     * 응답의 담보별 보험료와 총보험료는 청약 접수(/api/contract/apply) 요청에 그대로 사용할 수 있다.
     *
     * @param request 보험료 산출 요청 전문 (헤더 + 보험종목, 보험기간, 담보별 가입금액)
     * @return 담보별 요율/보험료와 총보험료를 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 입력/요율 오류
     */
    @Operation(summary = "보험료를 산출한다", description = "가입금액과 보험기간으로 담보별 보험료와 총보험료를 계산해 돌려줄 뿐 아무것도 저장하지 않는다. 응답의 담보별 보험료와 총보험료는 청약 접수요청에 그대로 사용할 수 있다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 입력/요율 오류")
    @PostMapping("/quote")
    public CommonMessage<PremiumQuoteResponse> quote(@RequestBody CommonMessage<PremiumQuoteRequest> request) {
        return success(request.getHeader(), premiumService.quote(requireBody(request)));
    }
}
