package com.insur.insurcoresystem.ext.kidi;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보험개발원 실손 청구 전산화 수신 API (대외기관 시스템 전용, EXTERNAL)
 */
@Tag(name = "대외기관 수신", description = "보험개발원 실손 청구 전산화(실손24) 중계 청구 수신")
@RestController
@RequestMapping("/api/ext/kidi")
@RequiredArgsConstructor
public class KidiController {

    private final KidiClaimService kidiClaimService;

    /**
     * 중계된 실손 청구를 사고로 접수한다. (POST /api/ext/kidi/medical-claim, EXTERNAL)
     *
     * @throws BusinessException 요청 바디 없음(E-CMN-001), 입력 오류(E-KIDI-001), 보장 계약 없음(E-KIDI-002), 계약 여럿(E-KIDI-003),
     *                           진료 내역 오류(E-MED-005, E-CLM-005, E-CLM-003), 같은 전송번호로 다른 내용(E-CLM-009)
     */
    @Operation(summary = "실손 청구 전산화 청구를 접수한다", description = "피보험자의 실손 계약을 찾아 진료 내역으로 사고를 접수한다. 같은 전송번호는 한 번만 접수하고 "
            + "재전송에는 같은 사고를 돌려준다.\n\n오류: 요청 바디 없음(E-CMN-001), 입력 오류(E-KIDI-001), 보장 계약 없음(E-KIDI-002, 404), "
            + "계약 여럿(E-KIDI-003, 409), 진료 내역 오류(E-MED-005, E-CLM-005, E-CLM-003), 같은 전송번호로 다른 내용(E-CLM-009, 409)")
    @PostMapping("/medical-claim")
    public CommonMessage<KidiClaimResponse> medicalClaim(@RequestBody CommonMessage<KidiClaimRequest> request, Authentication authentication) {
        return success(request.getHeader(), kidiClaimService.receive(authentication.getName(), requireBody(request)));
    }
}
