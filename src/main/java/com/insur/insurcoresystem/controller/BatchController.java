package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.batch.BatchService;
import com.insur.insurcoresystem.batch.dto.BatchRequests;
import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배치(스프링 배치) 실행과 결과 조회
 */
@Tag(name = "배치", description = "분기 지급준비금 결산, 일일 지급 대사, 실행 이력")
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class BatchController {

    private final BatchService batchService;

    /**
     * 분기 지급준비금 결산을 실행한다. (POST /api/batch/reserve-close, ADMIN)
     *
     * @throws BusinessException 요청 바디 없음(E-CMN-001), 분기 말일 아님·미래(E-BAT-001), 이미 결산함(E-BAT-002), 실행 중(E-BAT-003)
     */
    @Operation(summary = "분기 지급준비금 결산을 실행한다", description = "보험종목별 개별추산액과 IBNR 을 계산해 TB_RSV_CLOSE 에 확정한다. 기준일당 한 번만 완료되고, 실패한 실행은 다시 실행하면 재시작된다. 끝날 때까지 기다렸다가 실행 결과를 돌려준다.\n\n오류: 요청 바디 없음(E-CMN-001), 분기 말일 아님·미래(E-BAT-001), 이미 결산함(E-BAT-002, 409), 실행 중(E-BAT-003, 409)")
    @PostMapping("/reserve-close")
    public CommonMessage<BatchService.Execution> reserveClose(@RequestBody CommonMessage<BatchRequests.ReserveClose> request) {
        return success(request.getHeader(), batchService.runReserveClose(requireBody(request).getCloseDate(), request.getHeader().getEmpId()));
    }

    /** 결산 결과를 조회한다. (POST /api/batch/reserve-close-list, UNDERWRITER/CLAIM/ADMIN) */
    @Operation(summary = "지급준비금 결산 결과를 조회한다", description = "기준일을 생략하면 최근 결산 8번을 돌려준다.")
    @PostMapping("/reserve-close-list")
    public CommonMessage<List<BatchService.CloseRow>> reserveCloseList(@RequestBody CommonMessage<BatchRequests.ReserveClose> request) {
        return success(request.getHeader(), batchService.reserveCloses(requireBody(request).getCloseDate()));
    }

    /**
     * 일일 지급 대사를 실행한다. (POST /api/batch/payout-recon, OPERATOR/ADMIN)
     *
     * @throws BusinessException 요청 바디 없음(E-CMN-001), 미래 날짜(E-BAT-001), 실행 중(E-BAT-003)
     */
    @Operation(summary = "지급 대사를 실행한다", description = "은행 이체 내역과 우리 지급 원장을 거래고유번호로 맞춰 본다. 같은 날짜도 다시 실행할 수 있다(그날 결과를 새로 쓴다). 매일 새벽 전날 것은 예약 작업이 돌린다.\n\n오류: 요청 바디 없음(E-CMN-001), 미래 날짜(E-BAT-001), 실행 중(E-BAT-003, 409)")
    @PostMapping("/payout-recon")
    public CommonMessage<BatchService.Execution> payoutRecon(@RequestBody CommonMessage<BatchRequests.Recon> request) {
        return success(request.getHeader(), batchService.runPayoutRecon(requireBody(request).getReconDate(), request.getHeader().getEmpId()));
    }

    /**
     * 대사 결과를 조회한다. (POST /api/batch/payout-recon-list, OPERATOR/CLAIM/ADMIN)
     *
     * @throws BusinessException 날짜 없음(E-BAT-001)
     */
    @Operation(summary = "지급 대사 결과를 조회한다", description = "차이 건(MATCHED 가 아닌 건)이 먼저 나온다.\n\n오류: 날짜 없음(E-BAT-001)")
    @PostMapping("/payout-recon-list")
    public CommonMessage<List<BatchService.ReconResult>> payoutReconList(@RequestBody CommonMessage<BatchRequests.Recon> request) {
        BatchRequests.Recon body = requireBody(request);
        return success(request.getHeader(), batchService.reconResults(body.getReconDate(), Boolean.TRUE.equals(body.getDiffOnly())));
    }

    /**
     * 보험료 자동이체(CMS) 배치를 실행한다. (POST /api/batch/cms-debit, OPERATOR/ADMIN)
     *
     * @throws BusinessException 요청 바디 없음(E-CMN-001), 날짜 없음·미래(E-BAT-001), 이미 완료(E-BAT-002), 실행 중(E-BAT-003)
     */
    @Operation(summary = "보험료 자동이체 배치를 실행한다", description = "결제원에 출금 동의 등록을 의뢰하고 결과를 반영한 뒤, 청약 계약의 남은 보험료를 출금 의뢰하고 결과를 수납으로 기록한다. 출금일당 한 번 완료되며, 결과 파일이 늦어 실패하면 같은 출금일로 다시 실행해 실패한 단계부터 이어서 한다. 매일 07:00 에 예약 작업이 돌린다.\n\n오류: 요청 바디 없음(E-CMN-001), 날짜 없음·미래(E-BAT-001), 이미 완료(E-BAT-002, 409), 실행 중(E-BAT-003, 409)")
    @PostMapping("/cms-debit")
    public CommonMessage<BatchService.Execution> cmsDebit(@RequestBody CommonMessage<BatchRequests.CmsDebit> request) {
        return success(request.getHeader(), batchService.runCmsDebit(requireBody(request).getDebitDate(), request.getHeader().getEmpId()));
    }

    /**
     * 출금일의 자동이체 현황을 조회한다. (POST /api/batch/cms-debit-list, OPERATOR/ADMIN)
     *
     * @throws BusinessException 날짜 없음(E-BAT-001)
     */
    @Operation(summary = "보험료 자동이체 현황을 조회한다", description = "출금일의 출금 건(실패가 먼저)과 결제원 파일 송수신 기록(건수·합계·해시).\n\n오류: 날짜 없음(E-BAT-001)")
    @PostMapping("/cms-debit-list")
    public CommonMessage<BatchService.CmsDay> cmsDebitList(@RequestBody CommonMessage<BatchRequests.CmsDebit> request) {
        return success(request.getHeader(), batchService.cmsDay(requireBody(request).getDebitDate()));
    }

    /** 배치 실행 이력을 조회한다. (POST /api/batch/executions, UNDERWRITER/OPERATOR/CLAIM/ADMIN) */
    @Operation(summary = "배치 실행 이력을 조회한다", description = "스프링 배치 실행 표(BATCH_*)에서 최근순으로 읽는다. 단계별 읽기·쓰기·걸러냄 건수를 함께 준다.")
    @PostMapping("/executions")
    public CommonMessage<List<BatchService.Execution>> executions(@RequestBody CommonMessage<BatchRequests.Executions> request) {
        BatchRequests.Executions body = requireBody(request);
        return success(request.getHeader(), batchService.executions(body.getJobName(), body.getLimit()));
    }
}
