package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.medical.MedicalCheckService;
import com.insur.insurcoresystem.service.medical.dto.MedicalCheckRequest;
import com.insur.insurcoresystem.service.medical.dto.MedicalCheckResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실손의료보험 계약의 신용정보원 연계 API
 */
@Tag(name = "실손", description = "실손의료보험 중복가입 조회 (한국신용정보원)")
@RestController
@RequestMapping("/api/contract/medical")
@RequiredArgsConstructor
public class MedicalController {

    private final MedicalCheckService medicalCheckService;

    /**
     * 중복가입을 다시 조회한다. (POST /api/contract/medical/dup-check, UNDERWRITER/OPERATOR/ADMIN)
     *
     * @throws BusinessException 요청 바디 없음(E-CMN-001), 계약 없음(E-CTR-010), 실손 아님(E-MED-007), 청약 상태 아님(E-MED-008),
     *                           신용정보원 장애(E-MED-004, 503)
     */
    @Operation(summary = "실손 중복가입을 다시 조회한다", description = "청약 때 신용정보원 장애로 조회하지 못한(80) 계약은 이 조회가 성공해야 승인할 수 있다. "
            + "다른 보험사 실손 계약(증권번호 마스킹)을 함께 돌려준다.\n\n"
            + "오류: 요청 바디 없음(E-CMN-001), 계약 없음(E-CTR-010), 실손 아님(E-MED-007), 청약 상태 아님(E-MED-008), 신용정보원 장애(E-MED-004, 503)")
    @PostMapping("/dup-check")
    public CommonMessage<MedicalCheckResponse> dupCheck(@RequestBody CommonMessage<MedicalCheckRequest> request) {
        MedicalCheckRequest body = requireBody(request);
        return success(request.getHeader(), medicalCheckService.recheck(body.getContractNo(), request.getHeader().getEmpId()));
    }
}
