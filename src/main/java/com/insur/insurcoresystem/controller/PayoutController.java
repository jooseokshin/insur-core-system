package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonHeader;
import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.payout.PayoutService;
import com.insur.insurcoresystem.service.payout.dto.PayoutRequests;
import com.insur.insurcoresystem.service.payout.dto.PayoutResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보험금·환급금 지급 (은행 펌뱅킹)
 */
@Tag(name = "지급", description = "예금주 조회, 지급 요청, 지급 목록 (이체는 예약 작업이 보낸다)")
@RestController
@RequestMapping("/api/payout")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService payoutService;

    /**
     * 예금주를 조회한다. (POST /api/payout/account-verify, CLAIM/OPERATOR/ADMIN)
     *
     * @throws BusinessException 요청 바디 없음(E-CMN-001), 입력 오류(E-PAY-001), 은행 장애(E-PAY-005, 503)
     */
    @Operation(summary = "예금주를 조회한다", description = "은행에 예금주 조회 전문을 보내 계좌 상태와 예금주가 입력한 이름과 같은지 알려 준다. 저장하지 않는다.\n\n오류: 요청 바디 없음(E-CMN-001), 입력 오류(E-PAY-001), 은행 장애(E-PAY-005, 503)")
    @PostMapping("/account-verify")
    public CommonMessage<PayoutResponses.VerifyResult> verify(@RequestBody CommonMessage<PayoutRequests.Verify> request) {
        return success(request.getHeader(), payoutService.verify(requireBody(request)));
    }

    /**
     * 지급을 요청한다. (POST /api/payout/request, CLAIM/OPERATOR/ADMIN)
     *
     * @throws BusinessException 요청 바디 없음(E-CMN-001), 입력 오류(E-PAY-001), 지급할 수 없는 원천(E-PAY-002), 계좌 오류·예금주 불일치(E-PAY-003),
     *                           진행 중인 지급(E-PAY-004, 409), 은행 장애(E-PAY-005, 503), 같은 거래 UUID 로 다른 요청(E-PAY-007, 409), 암호화 키 없음(E-PAY-009, 503)
     */
    @Operation(summary = "지급을 요청한다", description = "지급 결정된 보험금(CLAIM, 사고번호)이나 정산된 마일리지 환급금(MILEAGE, 증권번호)을 은행 계좌로 보낼 지급을 만든다. 금액은 원천에서 가져오고, 예금주 조회가 맞아야 받는다. 이체는 예약 작업이 보내고 결과는 지급 목록에서 본다.\n\n오류: 요청 바디 없음(E-CMN-001), 입력 오류(E-PAY-001), 지급할 수 없는 원천(E-PAY-002), 계좌 오류·예금주 불일치(E-PAY-003), 진행 중인 지급(E-PAY-004, 409), 은행 장애(E-PAY-005, 503), 같은 거래 UUID 로 다른 요청(E-PAY-007, 409), 암호화 키 없음(E-PAY-009, 503)")
    @PostMapping("/request")
    public CommonMessage<PayoutResponses.Payout> request(@RequestBody CommonMessage<PayoutRequests.Request> request) {
        CommonHeader header = request.getHeader();
        return success(header, payoutService.request(header.getGlobalTxId(), header.getEmpId(), requireBody(request)));
    }

    /**
     * 지급 목록을 조회한다. (POST /api/payout/list, CLAIM/OPERATOR/ADMIN)
     *
     * @throws BusinessException 조건 오류(E-PAY-001)
     */
    @Operation(summary = "지급 목록을 조회한다", description = "최근순. 결과 불명(70)은 예약 작업이 결과 조회로 확인 중인 건이다.\n\n오류: 조건 오류(E-PAY-001)")
    @PostMapping("/list")
    public CommonMessage<List<PayoutResponses.Payout>> list(@RequestBody CommonMessage<PayoutRequests.ListCond> request) {
        return success(request.getHeader(), payoutService.list(requireBody(request)));
    }
}
