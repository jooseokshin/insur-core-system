package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.ext.fep.FepAdminService;
import com.insur.insurcoresystem.ext.fep.dto.FepRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FEP(대외계 중계) 운영 API
 */
@Tag(name = "FEP 운영", description = "대외기관 상태, 개시·종료, 회선 확인, 전문 로그")
@RestController
@RequestMapping("/api/admin/fep")
@RequiredArgsConstructor
public class FepAdminController {

    private final FepAdminService fepAdminService;

    /** 기관별 상태와 오늘 처리량. (POST /api/admin/fep/inst-list, OPERATOR/ADMIN) */
    @Operation(summary = "대외기관 상태를 조회한다", description = "개시 여부, 회선 차단(이 서버 기준), 마지막 회선 확인, 오늘 전문 건수·오류·응답 없음·차단·평균 응답시간")
    @PostMapping("/inst-list")
    public CommonMessage<List<FepAdminService.InstStatus>> institutions(@RequestBody CommonMessage<Object> request) {
        return success(request.getHeader(), fepAdminService.institutions());
    }

    /**
     * 개시한다. (POST /api/admin/fep/sign-on, ADMIN)
     *
     * @throws BusinessException 없는 기관(E-FEP-001), 기관이 개시를 받지 않음(E-FEP-002, 503)
     */
    @Operation(summary = "대외기관을 개시한다", description = "개시 전문(N001)이 정상 응답을 받아야 개시 상태가 된다.\n\n오류: 없는 기관(E-FEP-001, 404), 개시 실패(E-FEP-002, 503)")
    @PostMapping("/sign-on")
    public CommonMessage<FepAdminService.InstStatus> signOn(@RequestBody CommonMessage<FepRequests.Inst> request) {
        return success(request.getHeader(), fepAdminService.signOn(requireBody(request).getInstCd(), request.getHeader().getEmpId()));
    }

    /**
     * 종료한다. (POST /api/admin/fep/sign-off, ADMIN)
     *
     * @throws BusinessException 없는 기관(E-FEP-001)
     */
    @Operation(summary = "대외기관을 종료한다", description = "종료 중에는 업무 전문을 보내지 않는다(대기열·지급은 대기로 남았다가 개시 후 보낸다). 기관 정기 점검 시간에 쓴다.\n\n오류: 없는 기관(E-FEP-001, 404)")
    @PostMapping("/sign-off")
    public CommonMessage<FepAdminService.InstStatus> signOff(@RequestBody CommonMessage<FepRequests.Inst> request) {
        return success(request.getHeader(), fepAdminService.signOff(requireBody(request).getInstCd(), request.getHeader().getEmpId()));
    }

    /**
     * 회선을 확인한다. (POST /api/admin/fep/echo, OPERATOR/ADMIN)
     *
     * @throws BusinessException 없는 기관(E-FEP-001)
     */
    @Operation(summary = "회선을 확인한다 (에코)", description = "망관리 전문(N003)으로 기관 응답을 확인한다. 성공하면 회선 차단도 푼다.\n\n오류: 없는 기관(E-FEP-001, 404)")
    @PostMapping("/echo")
    public CommonMessage<FepAdminService.InstStatus> echo(@RequestBody CommonMessage<FepRequests.Inst> request) {
        return success(request.getHeader(), fepAdminService.echo(requireBody(request).getInstCd()));
    }

    /**
     * 전문 로그를 조회한다. (POST /api/admin/fep/msg-log, OPERATOR/ADMIN)
     *
     * @throws BusinessException 결과 코드 오류(E-FEP-003)
     */
    @Operation(summary = "전문 로그를 조회한다", description = "최근순. 본문 대신 길이와 SHA-256 만 남긴다.\n\n오류: 결과 코드 오류(E-FEP-003)")
    @PostMapping("/msg-log")
    public CommonMessage<List<FepAdminService.MessageLog>> messages(@RequestBody CommonMessage<FepRequests.MessageLog> request) {
        FepRequests.MessageLog body = requireBody(request);
        return success(request.getHeader(), fepAdminService.messages(body.getInstCd(), body.getTraceNo(), body.getResultCd(), body.getDate(), body.getLimit()));
    }
}
