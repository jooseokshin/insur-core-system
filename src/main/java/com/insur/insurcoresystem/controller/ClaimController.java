package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonHeader;
import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.claim.ClaimService;
import com.insur.insurcoresystem.service.claim.dto.ClaimRequests;
import com.insur.insurcoresystem.service.claim.dto.ClaimResponses;
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
 * 보상 컨트롤러: 사고 접수, 손해사정, 종결(지급·면책·접수 취소), 조회.
 * 설계사(AGENT 만 가진 사용자)는 본인이 접수한 계약의 사고만 다룬다.
 */
@Tag(name = "보상", description = "사고 접수, 손해사정, 보험금 지급·면책, 조회")
@RestController
@RequestMapping("/api/claim")
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService claimService;

    /**
     * 사고를 접수한다. (POST /api/claim/report)
     *
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 접수 오류
     */
    @Operation(summary = "사고를 접수한다", description = "유효·해지 계약의 보장 기간 안에 난 사고를 담보별 청구 손해액과 함께 접수한다. 설계사는 본인 접수 계약만. 같은 거래 UUID 재전송은 처음 결과를 돌려준다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 입력 오류(E-CLM-001, 004, 005), 계약 없음(E-CTR-010), 대상 아닌 계약 상태(E-CLM-002), 보장 기간 밖(E-CLM-003), 다른 내용의 재전송(E-CLM-009)")
    @PostMapping("/report")
    public CommonMessage<ClaimResponses.Detail> report(@RequestBody CommonMessage<ClaimRequests.Report> request,
                                                       Authentication authentication) {
        ClaimRequests.Report body = requireBody(request);
        CommonHeader header = request.getHeader();
        return success(header, claimService.report(owner(authentication), header.getGlobalTxId(), header.getEmpId(), body));
    }

    /**
     * 손해사정 결과를 기록한다. (POST /api/claim/assess, 보상 담당·ADMIN)
     *
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 사정 오류
     */
    @Operation(summary = "손해사정 결과를 기록한다", description = "담보별 사정 손해액을 받아 자기부담금과 남은 가입금액을 반영한 지급액을 계산한다. 지급 대기 중에는 다시 사정할 수 있다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 사고 없음(E-CLM-010), 할 수 없는 상태(E-CLM-006), 사정 항목 불일치(E-CLM-013), 금액 오류(E-CLM-005)")
    @PostMapping("/assess")
    public CommonMessage<ClaimResponses.Detail> assess(@RequestBody CommonMessage<ClaimRequests.Assess> request) {
        ClaimRequests.Assess body = requireBody(request);
        CommonHeader header = request.getHeader();
        return success(header, claimService.assess(header.getGlobalTxId(), header.getEmpId(), body));
    }

    /**
     * 사고를 종결한다. (POST /api/claim/decide)
     *
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 종결 오류
     */
    @Operation(summary = "사고를 종결한다 (지급·면책·접수 취소)", description = "PAY(지급)·DENY(면책, 사유 필수)는 보상 담당과 ADMIN, WITHDRAW(접수 취소)는 접수 상태에서 설계사도 할 수 있다. 지급액이 고액 기준을 넘으면 ADMIN 만 지급할 수 있다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 권한 없음(E-AUTH-002), 할 수 없는 상태(E-CLM-006), 면책 사유 누락(E-CLM-007), 고액 승인 필요(E-CLM-008), 지급액 0원(E-CLM-011), 재사정 필요(E-CLM-012)")
    @PostMapping("/decide")
    public CommonMessage<ClaimResponses.Detail> decide(@RequestBody CommonMessage<ClaimRequests.Decide> request,
                                                       Authentication authentication) {
        ClaimRequests.Decide body = requireBody(request);
        CommonHeader header = request.getHeader();
        return success(header, claimService.decide(owner(authentication), ContractAccessPolicy.roles(authentication),
                header.getGlobalTxId(), header.getEmpId(), body));
    }

    /**
     * 사고 상세를 조회한다. (POST /api/claim/inquiry)
     *
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 사고번호 누락(E-CLM-001), 사고 없음(E-CLM-010)
     */
    @Operation(summary = "사고 상세를 조회한다", description = "담보별 청구·사정·지급 금액, 계약 요약, 상태 이력을 돌려준다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 사고번호 누락(E-CLM-001), 사고 없음 또는 조회 권한 밖(E-CLM-010)")
    @PostMapping("/inquiry")
    public CommonMessage<ClaimResponses.Detail> inquiry(@RequestBody CommonMessage<ClaimRequests.Inquiry> request,
                                                        Authentication authentication) {
        return success(request.getHeader(), claimService.inquiry(owner(authentication), requireBody(request).getClmNo()));
    }

    /**
     * 사고 목록을 조회한다. (POST /api/claim/list)
     *
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 조건 오류(E-CLM-001), 페이징 값 오류(E-CTR-016)
     */
    @Operation(summary = "사고 목록을 조회한다", description = "상태, 증권번호, 접수일 범위, 고액 승인 대기만으로 좁힐 수 있다. 최근 접수순이다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 조건 오류(E-CLM-001), 페이징 값 오류(E-CTR-016)")
    @PostMapping("/list")
    public CommonMessage<ClaimResponses.Page> list(@RequestBody CommonMessage<ClaimRequests.ListCond> request,
                                                   Authentication authentication) {
        return success(request.getHeader(), claimService.list(owner(authentication), requireBody(request)));
    }

    private static String owner(Authentication authentication) {
        return ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;
    }
}
