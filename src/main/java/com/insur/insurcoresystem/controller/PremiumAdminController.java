package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonHeader;
import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.premium.PremiumAdminService;
import com.insur.insurcoresystem.service.premium.dto.AuditListRequest;
import com.insur.insurcoresystem.service.premium.dto.AuditListResponse;
import com.insur.insurcoresystem.service.premium.dto.FactorListRequest;
import com.insur.insurcoresystem.service.premium.dto.FactorListResponse;
import com.insur.insurcoresystem.service.premium.dto.FactorRevisionRequest;
import com.insur.insurcoresystem.service.premium.dto.FactorRevisionResponse;
import com.insur.insurcoresystem.service.premium.dto.PolicyListRequest;
import com.insur.insurcoresystem.service.premium.dto.PolicyListResponse;
import com.insur.insurcoresystem.service.premium.dto.PolicyRevisionRequest;
import com.insur.insurcoresystem.service.premium.dto.PolicyRevisionResponse;
import com.insur.insurcoresystem.service.premium.dto.RateListRequest;
import com.insur.insurcoresystem.service.premium.dto.RateListResponse;
import com.insur.insurcoresystem.service.premium.dto.RateRevisionRequest;
import com.insur.insurcoresystem.service.premium.dto.RateRevisionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 요율/요율 계수 관리 컨트롤러. 조회는 심사·계약 관리·ADMIN, 개정과 신규 등록은 ADMIN 만 할 수 있다. (SecurityConfig 의 URL 규칙)
 */
@Tag(name = "요율 관리", description = "요율, 계수, 필수 계수 정책의 조회·개정과 감사 이력 (ADMIN 개정)")
@RestController
@RequestMapping("/api/admin/premium")
@RequiredArgsConstructor
public class PremiumAdminController {

    private final PremiumAdminService premiumAdminService;

    /**
     * 요율 개정 이력을 조회한다. (POST /api/admin/premium/rate-list)
     *
     * @param request 조회 요청 전문 (헤더 + 보험종목, 담보코드 조건)
     * @return 요율 행 목록을 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 지원하지 않는 보험종목(E-SEQ-001)
     */
    @Operation(summary = "요율 개정 이력을 조회한다", description = "오류: 요청 바디가 없는 경우(E-CMN-001), 지원하지 않는 보험종목(E-SEQ-001)")
    @PostMapping("/rate-list")
    public CommonMessage<RateListResponse> rateList(@RequestBody CommonMessage<RateListRequest> request) {
        return success(request.getHeader(), premiumAdminService.listRates(requireBody(request)));
    }

    /**
     * 요율을 개정하거나 새 담보 요율을 만든다. (POST /api/admin/premium/rate-revise)
     * 기존 요율이 있으면 가장 최근 행의 적용 종료일을 닫고 새 행을 추가하며, 모든 변경은 감사 이력에 남는다.
     *
     * @param request 개정 요청 전문 (헤더 + 보험종목, 담보코드, 새 요율, 적용 시작일)
     * @return 수행한 동작과 새 행, 닫은 이전 행을 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 입력/충돌 오류(E-ADM-001~004 등)
     */
    @Operation(summary = "요율을 개정하거나 새 담보 요율을 만든다", description = "기존 요율이 있으면 가장 최근 행의 적용 종료일을 닫고 새 행을 추가하며, 모든 변경은 감사 이력에 남는다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 입력/충돌 오류(E-ADM-001~004 등)")
    @PostMapping("/rate-revise")
    public CommonMessage<RateRevisionResponse> rateRevise(@RequestBody CommonMessage<RateRevisionRequest> request) {
        RateRevisionRequest body = requireBody(request);
        CommonHeader header = request.getHeader();

        return success(header, premiumAdminService.reviseRate(header.getGlobalTxId(), header.getEmpId(), body));
    }

    /**
     * 필수 요율 계수 정책의 개정 이력을 조회한다. (POST /api/admin/premium/policy-list)
     *
     * @param request 조회 요청 전문 (헤더 + 보험종목 조건)
     * @return 정책 행 목록을 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 지원하지 않는 보험종목(E-SEQ-001)
     */
    @Operation(summary = "필수 요율 계수 정책의 개정 이력을 조회한다", description = "오류: 요청 바디가 없는 경우(E-CMN-001), 지원하지 않는 보험종목(E-SEQ-001)")
    @PostMapping("/policy-list")
    public CommonMessage<PolicyListResponse> policyList(@RequestBody CommonMessage<PolicyListRequest> request) {
        return success(request.getHeader(), premiumAdminService.listPolicies(requireBody(request)));
    }

    /**
     * 필수 요율 계수 정책을 개정하거나 새로 만든다. (POST /api/admin/premium/policy-revise)
     * 새 정책은 보험시기가 적용 시작일 이후인 보험료 산출과 청약부터 적용되며, 모든 변경은 감사 이력에 남는다.
     *
     * @param request 개정 요청 전문 (헤더 + 보험종목, 계수 종류, 필수 여부, 적용 시작일)
     * @return 수행한 동작과 새 행, 닫은 이전 행을 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 입력/충돌 오류(E-ADM-001~004 등)
     */
    @Operation(summary = "필수 요율 계수 정책을 개정하거나 새로 만든다", description = "새 정책은 보험시기가 적용 시작일 이후인 보험료 산출과 청약부터 적용되며, 모든 변경은 감사 이력에 남는다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 입력/충돌 오류(E-ADM-001~004 등)")
    @PostMapping("/policy-revise")
    public CommonMessage<PolicyRevisionResponse> policyRevise(@RequestBody CommonMessage<PolicyRevisionRequest> request) {
        PolicyRevisionRequest body = requireBody(request);
        CommonHeader header = request.getHeader();

        return success(header, premiumAdminService.revisePolicy(header.getGlobalTxId(), header.getEmpId(), body));
    }

    /**
     * 요율/계수/정책 관리 감사 이력을 조회한다. (POST /api/admin/premium/audit-list)
     *
     * @param request 조회 요청 전문 (헤더 + 대상 종류, 대상 식별, 처리 사번, 처리일 범위, 페이징)
     * @return 최근 처리 순 감사 항목 한 페이지를 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 조건 오류(E-ADM-001), 페이징 값 오류(E-CTR-016)
     */
    @Operation(summary = "요율/계수/정책 관리 감사 이력을 조회한다", description = "오류: 요청 바디가 없는 경우(E-CMN-001), 조건 오류(E-ADM-001), 페이징 값 오류(E-CTR-016)")
    @PostMapping("/audit-list")
    public CommonMessage<AuditListResponse> auditList(@RequestBody CommonMessage<AuditListRequest> request) {
        return success(request.getHeader(), premiumAdminService.listAudits(requireBody(request)));
    }

    /**
     * 요율 계수 개정 이력을 조회한다. (POST /api/admin/premium/factor-list)
     *
     * @param request 조회 요청 전문 (헤더 + 보험종목, 계수 종류 조건)
     * @return 계수 행 목록을 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 지원하지 않는 보험종목(E-SEQ-001), 알 수 없는 계수 종류(E-ADM-001)
     */
    @Operation(summary = "요율 계수 개정 이력을 조회한다", description = "오류: 요청 바디가 없는 경우(E-CMN-001), 지원하지 않는 보험종목(E-SEQ-001), 알 수 없는 계수 종류(E-ADM-001)")
    @PostMapping("/factor-list")
    public CommonMessage<FactorListResponse> factorList(@RequestBody CommonMessage<FactorListRequest> request) {
        return success(request.getHeader(), premiumAdminService.listFactors(requireBody(request)));
    }

    /**
     * 요율 계수를 개정하거나 새 칸을 만든다. (POST /api/admin/premium/factor-revise)
     * 기존 계수가 있으면 가장 최근 행의 적용 종료일을 닫고 새 행을 추가하며, 모든 변경은 감사 이력에 남는다.
     *
     * @param request 개정 요청 전문 (헤더 + 보험종목, 계수 종류, 키 또는 구간, 새 계수 값, 적용 시작일)
     * @return 수행한 동작과 새 행, 닫은 이전 행을 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 입력/충돌 오류(E-ADM-001~004 등)
     */
    @Operation(summary = "요율 계수를 개정하거나 새 칸을 만든다", description = "기존 계수가 있으면 가장 최근 행의 적용 종료일을 닫고 새 행을 추가하며, 모든 변경은 감사 이력에 남는다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 입력/충돌 오류(E-ADM-001~004 등)")
    @PostMapping("/factor-revise")
    public CommonMessage<FactorRevisionResponse> factorRevise(@RequestBody CommonMessage<FactorRevisionRequest> request) {
        FactorRevisionRequest body = requireBody(request);
        CommonHeader header = request.getHeader();

        return success(header, premiumAdminService.reviseFactor(header.getGlobalTxId(), header.getEmpId(), body));
    }
}
