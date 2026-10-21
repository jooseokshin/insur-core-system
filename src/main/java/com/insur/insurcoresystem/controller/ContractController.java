package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonHeader;
import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.contract.ContractAccessPolicy;
import com.insur.insurcoresystem.service.contract.ContractEndorsementService;
import com.insur.insurcoresystem.service.contract.ContractInquiryService;
import com.insur.insurcoresystem.service.contract.ContractPaymentService;
import com.insur.insurcoresystem.service.contract.ContractRenewalService;
import com.insur.insurcoresystem.service.contract.ContractService;
import com.insur.insurcoresystem.service.contract.ContractStatusService;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractApplyResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractEndorseRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractEndorseResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractInquiryRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractRenewPreviewResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractRenewRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractRenewResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractInquiryResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractListRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractListResponse;
import com.insur.insurcoresystem.service.contract.dto.ContractStatusChangeRequest;
import com.insur.insurcoresystem.service.contract.dto.ContractStatusChangeResponse;
import com.insur.insurcoresystem.service.contract.dto.EndorsementHistoryPageResponse;
import com.insur.insurcoresystem.service.contract.dto.EndorsementHistoryRequest;
import com.insur.insurcoresystem.service.contract.dto.PaymentHistoryRequest;
import com.insur.insurcoresystem.service.contract.dto.PaymentHistoryResponse;
import com.insur.insurcoresystem.service.contract.dto.PaymentRequest;
import com.insur.insurcoresystem.service.contract.dto.PaymentResponse;
import com.insur.insurcoresystem.service.contract.dto.StatusHistoryInquiryRequest;
import com.insur.insurcoresystem.service.contract.dto.StatusHistoryPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 청약/조회/목록/상태 변경/상태 이력/변경(배서) 컨트롤러
 */
@Tag(name = "계약", description = "청약, 조회, 목록, 상태 변경, 이력, 배서, 갱신")
@RestController
@RequestMapping("/api/contract")
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;
    private final ContractInquiryService inquiryService;
    private final ContractStatusService statusService;
    private final ContractEndorsementService endorsementService;
    private final ContractRenewalService renewalService;
    private final ContractPaymentService paymentService;

    /**
     * 계약 청약을 접수한다. (POST /api/contract/apply)
     * 요청 전문의 바디를 서비스에 위임하고, 요청 헤더의 globalTxId, empId 를 이어받아 정상 응답 전문을 구성한다.
     *
     * @param request 청약 요청 전문 (헤더 + 청약 바디)
     * @return 청약 결과 응답 전문 (증권번호, 청약번호 등)
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 업무 오류
     */
    @Operation(summary = "계약 청약을 접수한다", description = "요청 전문의 바디를 서비스에 위임하고, 요청 헤더의 globalTxId, empId 를 이어받아 정상 응답 전문을 구성한다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 업무 오류")
    @PostMapping("/apply")
    public CommonMessage<ContractApplyResponse> apply(@RequestBody CommonMessage<ContractApplyRequest> request) {
        ContractApplyRequest body = requireBody(request);
        // 헤더는 CommonHeaderRequestBodyAdvice 에서 globalTxId 까지 채워진 상태로 전달된다.
        CommonHeader reqHeader = request.getHeader();

        return success(reqHeader, contractService.applyContract(reqHeader.getGlobalTxId(), reqHeader.getEmpId(), body));
    }

    /**
     * 청약 상태 계약의 보험료 납입(수납)을 접수한다. (POST /api/contract/payment)
     * 나누어 낼 수 있고, 낸 합계가 총보험료에 이르면 승인(유효)할 수 있다. 계약 관리 담당과 ADMIN 만 할 수 있다.
     *
     * @param request 납입 요청 전문 (헤더 + 증권번호, 납입 금액, 납부 방법)
     * @return 이번 납입과 납입 현황(낸 합계, 남은 보험료, 완납 여부)을 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 납입 오류(E-CTR-007~011, 015, 019, 026, 027, 029)
     */
    @Operation(summary = "청약 상태 계약의 보험료 납입(수납)을 접수한다", description = "나누어 낼 수 있고, 낸 합계가 총보험료에 이르면 승인(유효)할 수 있다. 계약 관리 담당과 ADMIN 만 할 수 있다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 납입 오류(E-CTR-007~011, 015, 019, 026, 027, 029)")
    @PostMapping("/payment")
    public CommonMessage<PaymentResponse> payment(@RequestBody CommonMessage<PaymentRequest> request) {
        PaymentRequest body = requireBody(request);
        CommonHeader reqHeader = request.getHeader();

        return success(reqHeader, paymentService.pay(reqHeader.getGlobalTxId(), reqHeader.getEmpId(), body));
    }

    /**
     * 계약의 납입 내역을 페이지 단위로 조회한다. (POST /api/contract/payment-history)
     * 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약만 조회할 수 있다.
     *
     * @param request        조회 요청 전문 (헤더 + 증권번호, 페이지 번호, 페이지 크기)
     * @param authentication 인증된 사용자 (JWT)
     * @return 납입 항목 한 페이지와 납입 현황을 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 증권번호 누락(E-CTR-009), 페이징 값 오류(E-CTR-016), 계약 없음 또는 조회 권한 밖(E-CTR-010)
     */
    @Operation(summary = "계약의 납입 내역을 페이지 단위로 조회한다", description = "청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약만 조회할 수 있다. 낸 합계와 남은 보험료는 페이지와 무관하게 전체 기준이다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 증권번호 누락(E-CTR-009), 페이징 값 오류(E-CTR-016), 계약 없음 또는 조회 권한 밖(E-CTR-010)")
    @PostMapping("/payment-history")
    public CommonMessage<PaymentHistoryResponse> paymentHistory(
            @RequestBody CommonMessage<PaymentHistoryRequest> request, Authentication authentication) {
        PaymentHistoryRequest body = requireBody(request);
        String ownerEmpId = ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;

        return success(request.getHeader(), paymentService.history(ownerEmpId, body));
    }

    /**
     * 유효 계약을 갱신해 새 청약을 접수한다. (POST /api/contract/renew)
     * 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약만 갱신할 수 있다.
     *
     * @param request        갱신 요청 전문 (헤더 + 원 계약 증권번호, 요율 계수)
     * @param authentication 인증된 사용자 (JWT)
     * @return 갱신 계약의 번호와 기간, 원 계약과 갱신 계약의 총보험료를 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 갱신 오류(E-CTR-008~010, 015, 019, 023~025, E-PRM-*)
     */
    @Operation(summary = "유효 계약을 갱신해 새 청약을 접수한다", description = "청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약만 갱신할 수 있다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001) 및 서비스 단계의 갱신 오류(E-CTR-008~010, 015, 019, 023~025, E-PRM-*)")
    @PostMapping("/renew")
    public CommonMessage<ContractRenewResponse> renew(
            @RequestBody CommonMessage<ContractRenewRequest> request, Authentication authentication) {
        ContractRenewRequest body = requireBody(request);
        CommonHeader reqHeader = request.getHeader();
        String ownerEmpId = ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;

        return success(reqHeader, renewalService.renew(ownerEmpId, reqHeader.getGlobalTxId(), reqHeader.getEmpId(), body));
    }

    /**
     * 갱신 보험료를 미리 본다. (POST /api/contract/renew-quote) 아무것도 저장하지 않는다.
     * 갱신(/renew)과 같은 검증과 산출을 거치고 접수자 제한도 같다.
     *
     * @param request        요청 전문 (헤더 + 원 계약 증권번호, 요율 계수)
     * @param authentication 인증된 사용자 (JWT)
     * @return 갱신 보험기간과 원 계약 대비 담보별/총 보험료를 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001) 및 갱신과 같은 오류(E-CTR-009~010, 023~025, E-PRM-*)
     */
    @Operation(summary = "갱신 보험료를 미리 본다", description = "아무것도 저장하지 않는다. 갱신(/renew)과 같은 검증과 산출을 거치고 접수자 제한도 같다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001) 및 갱신과 같은 오류(E-CTR-009~010, 023~025, E-PRM-*)")
    @PostMapping("/renew-quote")
    public CommonMessage<ContractRenewPreviewResponse> renewQuote(
            @RequestBody CommonMessage<ContractRenewRequest> request, Authentication authentication) {
        ContractRenewRequest body = requireBody(request);
        String ownerEmpId = ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;

        return success(request.getHeader(), renewalService.preview(ownerEmpId, body));
    }

    /**
     * 계약을 조회한다. (POST /api/contract/inquiry)
     * 다른 거래와 같은 공통 전문 규약(헤더 + 바디)을 따르도록 조회도 POST 로 받는다.
     *
     * 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약만 조회할 수 있다.
     *
     * @param request        조회 요청 전문 (헤더 + 증권번호 또는 청약번호)
     * @param authentication 인증된 사용자 (JWT)
     * @return 계약 원장과 담보 목록을 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 조회 조건 오류(E-CTR-009),
     *                           계약 없음 또는 조회 권한 밖(E-CTR-010)
     */
    @Operation(summary = "계약을 조회한다", description = "다른 거래와 같은 공통 전문 규약(헤더 + 바디)을 따르도록 조회도 POST 로 받는다. 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약만 조회할 수 있다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 조회 조건 오류(E-CTR-009), 계약 없음 또는 조회 권한 밖(E-CTR-010)")
    @PostMapping("/inquiry")
    public CommonMessage<ContractInquiryResponse> inquiry(
            @RequestBody CommonMessage<ContractInquiryRequest> request, Authentication authentication) {
        ContractInquiryRequest body = requireBody(request);
        String ownerEmpId = ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;

        return success(request.getHeader(), inquiryService.inquireContract(ownerEmpId, body));
    }

    /**
     * 조건에 맞는 계약 목록을 페이지 단위로 조회한다. (POST /api/contract/list)
     * 조건은 모두 선택이고, 기본 정렬은 최근 접수순이다. 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약만 목록에 나온다.
     *
     * @param request        목록 조회 요청 전문 (헤더 + 검색 조건, 정렬, 페이지 번호, 페이지 크기)
     * @param authentication 인증된 사용자 (JWT)
     * @return 해당 페이지의 계약 요약과 전체 건수/페이지 수를 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 조건 오류(E-SEQ-001, E-CTR-013, E-CTR-017),
     *                           페이징 값 오류(E-CTR-016), 정렬 값 오류(E-CTR-018)
     */
    @Operation(summary = "조건에 맞는 계약 목록을 페이지 단위로 조회한다", description = "조건은 모두 선택이고, 기본 정렬은 최근 접수순이다. 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약만 목록에 나온다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 조건 오류(E-SEQ-001, E-CTR-013, E-CTR-017), 페이징 값 오류(E-CTR-016), 정렬 값 오류(E-CTR-018)")
    @PostMapping("/list")
    public CommonMessage<ContractListResponse> list(
            @RequestBody CommonMessage<ContractListRequest> request, Authentication authentication) {
        ContractListRequest body = requireBody(request);
        String ownerEmpId = ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;

        return success(request.getHeader(), inquiryService.listContracts(ownerEmpId, body));
    }

    /**
     * 계약의 상태 변경 이력을 페이지 단위로 조회한다. (POST /api/contract/status-history)
     * 계약 조회와 같은 조회 범위 규칙을 따라, 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약의 이력만 볼 수 있다.
     *
     * @param request        조회 요청 전문 (헤더 + 증권번호, 페이지 번호, 페이지 크기)
     * @param authentication 인증된 사용자 (JWT)
     * @return 해당 페이지의 이력과 전체 건수/페이지 수를 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 증권번호 누락(E-CTR-009), 페이징 값 오류(E-CTR-016),
     *                           계약 없음 또는 조회 권한 밖(E-CTR-010)
     */
    @Operation(summary = "계약의 상태 변경 이력을 페이지 단위로 조회한다", description = "계약 조회와 같은 조회 범위 규칙을 따라, 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약의 이력만 볼 수 있다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 증권번호 누락(E-CTR-009), 페이징 값 오류(E-CTR-016), 계약 없음 또는 조회 권한 밖(E-CTR-010)")
    @PostMapping("/status-history")
    public CommonMessage<StatusHistoryPageResponse> statusHistory(
            @RequestBody CommonMessage<StatusHistoryInquiryRequest> request, Authentication authentication) {
        StatusHistoryInquiryRequest body = requireBody(request);
        String ownerEmpId = ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;

        return success(request.getHeader(), inquiryService.inquireStatusHistory(ownerEmpId, body));
    }

    /**
     * 계약 변경(배서): 계약자/피보험자를 바꾼다. (POST /api/contract/endorse)
     * 청약 또는 유효 상태의 계약만 바꿀 수 있고, 바뀐 항목마다 변경 전/후 값이 배서 이력으로 남는다.
     *
     * @param request 변경 요청 전문 (헤더 + 증권번호, 새 계약자/피보험자)
     * @return 변경 후 계약자/피보험자와 바뀐 항목을 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 입력 오류(E-CTR-005, E-CTR-012), 계약 없음(E-CTR-010),
     *                           배서할 수 없는 상태(E-CTR-021), 바뀐 항목 없음(E-CTR-022)
     */
    @Operation(summary = "계약 변경(배서): 계약자/피보험자를 바꾼다", description = "청약 또는 유효 상태의 계약만 바꿀 수 있고, 바뀐 항목마다 변경 전/후 값이 배서 이력으로 남는다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 입력 오류(E-CTR-005, E-CTR-012), 계약 없음(E-CTR-010), 배서할 수 없는 상태(E-CTR-021), 바뀐 항목 없음(E-CTR-022)")
    @PostMapping("/endorse")
    public CommonMessage<ContractEndorseResponse> endorse(@RequestBody CommonMessage<ContractEndorseRequest> request) {
        ContractEndorseRequest body = requireBody(request);
        CommonHeader reqHeader = request.getHeader();

        return success(reqHeader, endorsementService.endorse(reqHeader.getGlobalTxId(), reqHeader.getEmpId(), body));
    }

    /**
     * 계약의 배서 이력을 페이지 단위로 조회한다. (POST /api/contract/endorse-history)
     * 계약 조회와 같은 조회 범위 규칙을 따라, 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약의 이력만 볼 수 있다.
     *
     * @param request        조회 요청 전문 (헤더 + 증권번호, 페이지 번호, 페이지 크기)
     * @param authentication 인증된 사용자 (JWT)
     * @return 해당 페이지의 배서 항목과 전체 건수/페이지 수를 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 증권번호 누락(E-CTR-009), 페이징 값 오류(E-CTR-016),
     *                           계약 없음 또는 조회 권한 밖(E-CTR-010)
     */
    @Operation(summary = "계약의 배서 이력을 페이지 단위로 조회한다", description = "계약 조회와 같은 조회 범위 규칙을 따라, 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약의 이력만 볼 수 있다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 증권번호 누락(E-CTR-009), 페이징 값 오류(E-CTR-016), 계약 없음 또는 조회 권한 밖(E-CTR-010)")
    @PostMapping("/endorse-history")
    public CommonMessage<EndorsementHistoryPageResponse> endorseHistory(
            @RequestBody CommonMessage<EndorsementHistoryRequest> request, Authentication authentication) {
        EndorsementHistoryRequest body = requireBody(request);
        String ownerEmpId = ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;

        return success(request.getHeader(), endorsementService.history(ownerEmpId, body));
    }

    /**
     * 계약상태를 변경한다. (POST /api/contract/status-change)
     * 청약 승인(10→20), 청약취소(10→90), 해지(20→30)처럼 허용된 상태 전이만 처리한다.
     *
     * 변경할 상태에 따라 필요한 역할이 다르므로(심사 담당: 승인/취소, 계약 관리 담당: 해지) 서비스 호출 전에 권한을 확인한다.
     *
     * @param request        상태 변경 요청 전문 (헤더 + 증권번호, 변경할 상태코드)
     * @param authentication 인증된 사용자 (JWT)
     * @return 변경 전/후 상태를 담은 응답 전문
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 해당 상태로 변경할 권한이 없는 경우(E-AUTH-002, 403),
     *                           입력 오류(E-CTR-012, E-CTR-013), 계약 없음(E-CTR-010), 허용되지 않는 상태 변경(E-CTR-014)
     */
    @Operation(summary = "계약상태를 변경한다", description = "청약 승인(10→20), 청약취소(10→90), 해지(20→30)처럼 허용된 상태 전이만 처리한다. 변경할 상태에 따라 필요한 역할이 다르므로(심사 담당: 승인/취소, 계약 관리 담당: 해지) 서비스 호출 전에 권한을 확인한다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 해당 상태로 변경할 권한이 없는 경우(E-AUTH-002, 403), 입력 오류(E-CTR-012, E-CTR-013), 계약 없음(E-CTR-010), 허용되지 않는 상태 변경(E-CTR-014)")
    @PostMapping("/status-change")
    public CommonMessage<ContractStatusChangeResponse> statusChange(
            @RequestBody CommonMessage<ContractStatusChangeRequest> request, Authentication authentication) {
        ContractStatusChangeRequest body = requireBody(request);
        ContractAccessPolicy.checkStatusChange(authentication, body.getCtrStatCd());
        CommonHeader reqHeader = request.getHeader();

        return success(reqHeader,
                statusService.changeContractStatus(reqHeader.getGlobalTxId(), reqHeader.getEmpId(), body));
    }
}
