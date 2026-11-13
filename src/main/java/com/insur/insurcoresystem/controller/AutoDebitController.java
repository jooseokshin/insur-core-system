package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonHeader;
import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.cms.CmsMandateService;
import com.insur.insurcoresystem.service.cms.dto.AutoDebitRequests;
import com.insur.insurcoresystem.service.cms.dto.AutoDebitResponse;
import com.insur.insurcoresystem.service.contract.ContractAccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보험료 자동이체(CMS) 등록·해지·조회
 */
@Tag(name = "자동이체", description = "보험료 자동이체 출금 동의 등록·해지·조회 (출금은 배치)")
@RestController
@RequestMapping("/api/contract/autodebit")
@RequiredArgsConstructor
public class AutoDebitController {

    private final CmsMandateService cmsMandateService;

    private static String owner(Authentication authentication) {
        return ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;
    }

    /**
     * 자동이체를 등록한다. (POST /api/contract/autodebit/register, AGENT 본인 계약/OPERATOR/ADMIN/CHANNEL 자기 채널)
     *
     * @throws BusinessException 요청 바디 없음(E-CMN-001), 입력 오류(E-CMS-001), 계약 없음(E-CTR-010), 청약·유효 아님(E-CMS-002),
     *                           계좌 오류·예금주 불일치(E-CMS-003), 이미 등록(E-CMS-004), 은행 장애(E-CMS-006), 같은 거래 UUID 로 다른 요청(E-CMS-007)
     */
    @Operation(summary = "보험료 자동이체를 등록한다", description = "예금주의 출금 동의(방식과 증빙 관리번호)와 계좌를 등록한다. 은행 예금주 조회로 계좌와 예금주를 바로 확인하고, 결제원 등록은 자동이체 배치가 한다.\n\n오류: 요청 바디 없음(E-CMN-001), 입력 오류(E-CMS-001), 계약 없음(E-CTR-010, 404), 청약·유효 아님(E-CMS-002, 409), 계좌 오류·예금주 불일치(E-CMS-003), 이미 등록(E-CMS-004, 409), 은행 장애(E-CMS-006, 503), 같은 거래 UUID 로 다른 요청(E-CMS-007, 409)")
    @PostMapping("/register")
    public CommonMessage<AutoDebitResponse> register(@RequestBody CommonMessage<AutoDebitRequests.Register> request, Authentication authentication) {
        CommonHeader header = request.getHeader();
        return success(header, cmsMandateService.register(owner(authentication), header.getGlobalTxId(), header.getEmpId(), requireBody(request)));
    }

    /**
     * 자동이체를 해지한다. (POST /api/contract/autodebit/cancel, AGENT 본인 계약/OPERATOR/ADMIN/CHANNEL 자기 채널)
     *
     * @throws BusinessException 요청 바디 없음(E-CMN-001), 계약 없음(E-CTR-010), 등록 없음(E-CMS-008), 출금 진행 중(E-CMS-005)
     */
    @Operation(summary = "보험료 자동이체를 해지한다", description = "출금이 진행 중(의뢰 대상·결과 대기)이면 해지할 수 없다.\n\n오류: 요청 바디 없음(E-CMN-001), 계약 없음(E-CTR-010, 404), 등록 없음(E-CMS-008, 404), 출금 진행 중(E-CMS-005, 409)")
    @PostMapping("/cancel")
    public CommonMessage<AutoDebitResponse> cancel(@RequestBody CommonMessage<AutoDebitRequests.Contract> request, Authentication authentication) {
        return success(request.getHeader(), cmsMandateService.cancel(owner(authentication), request.getHeader().getEmpId(),
                requireBody(request).getContractNo()));
    }

    /**
     * 자동이체 현황을 조회한다. (POST /api/contract/autodebit/inquiry)
     *
     * @throws BusinessException 요청 바디 없음(E-CMN-001), 계약 없음(E-CTR-010)
     */
    @Operation(summary = "보험료 자동이체 현황을 조회한다", description = "가장 최근 등록과 출금 내역(최근 20건).\n\n오류: 요청 바디 없음(E-CMN-001), 계약 없음(E-CTR-010, 404)")
    @PostMapping("/inquiry")
    public CommonMessage<AutoDebitResponse> inquiry(@RequestBody CommonMessage<AutoDebitRequests.Contract> request, Authentication authentication) {
        return success(request.getHeader(), cmsMandateService.inquiry(owner(authentication), requireBody(request).getContractNo()));
    }
}
