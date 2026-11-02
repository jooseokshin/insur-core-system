package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.service.contract.ContractAccessPolicy;
import com.insur.insurcoresystem.service.work.WorkService;
import com.insur.insurcoresystem.service.work.dto.DashboardResponse;
import com.insur.insurcoresystem.service.work.dto.RenewalTargetRequest;
import com.insur.insurcoresystem.service.work.dto.RenewalTargetResponse;
import com.insur.insurcoresystem.service.work.dto.TodoRequest;
import com.insur.insurcoresystem.service.work.dto.TodoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현업 업무 화면 컨트롤러: 업무 현황(대시보드), 할 일함, 만기·갱신 대상
 */
@Tag(name = "업무", description = "업무 현황, 할 일함, 만기·갱신 대상")
@RestController
@RequestMapping("/api/work")
@RequiredArgsConstructor
public class WorkController {

    private final WorkService workService;

    /**
     * 업무 현황을 조회한다. (POST /api/work/dashboard) 바디는 비워 두거나 빈 객체로 보낸다.
     * 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약 기준으로 집계한다.
     *
     * @param request        요청 전문 (헤더만 사용)
     * @param authentication 인증된 사용자 (JWT)
     * @return 계약상태별 건수, 오늘 처리 실적, 처리할 수 있는 할 일 건수, 갱신 대상 건수
     */
    @Operation(summary = "업무 현황(대시보드)을 조회한다", description = "바디는 비워 두거나 빈 객체로 보낸다. 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약 기준으로 집계한다. 할 일 건수는 사용자 역할로 처리할 수 있는 유형만 담는다.")
    @PostMapping("/dashboard")
    public CommonMessage<DashboardResponse> dashboard(
            @RequestBody CommonMessage<Map<String, Object>> request, Authentication authentication) {
        String ownerEmpId = ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;

        return success(request.getHeader(), workService.dashboard(ownerEmpId, ContractAccessPolicy.roles(authentication)));
    }

    /**
     * 할 일함 목록을 조회한다. (POST /api/work/todo)
     * 유형마다 처리할 수 있는 역할이 정해져 있고(승인 대기: 심사 담당, 미납: 계약 관리 담당·설계사, 장기 미처리: 심사 담당·설계사), ADMIN 은 모두 볼 수 있다.
     *
     * @param request        조회 요청 전문 (헤더 + 유형, 페이지)
     * @param authentication 인증된 사용자 (JWT)
     * @return 해당 유형의 청약 목록 (오래된 접수순)
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 유형 오류(E-WRK-001), 그 유형을 볼 권한이 없는 경우(E-AUTH-002, 403), 페이징 값 오류(E-CTR-016)
     */
    @Operation(summary = "할 일함 목록을 조회한다", description = "유형마다 처리할 수 있는 역할이 정해져 있다(승인 대기: 심사 담당, 미납: 계약 관리 담당·설계사, 장기 미처리: 심사 담당·설계사). ADMIN 은 모두 볼 수 있고, AGENT 만 가진 사용자는 본인 접수 건만 나온다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 유형 오류(E-WRK-001), 그 유형을 볼 권한이 없는 경우(E-AUTH-002, 403), 페이징 값 오류(E-CTR-016)")
    @PostMapping("/todo")
    public CommonMessage<TodoResponse> todo(@RequestBody CommonMessage<TodoRequest> request, Authentication authentication) {
        TodoRequest body = requireBody(request);
        String ownerEmpId = ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;

        return success(request.getHeader(), workService.todo(ownerEmpId, ContractAccessPolicy.roles(authentication), body));
    }

    /**
     * 만기·갱신 대상을 조회한다. (POST /api/work/renewal-targets)
     * 오늘부터 지정한 일수(최대 60일) 안에 보험종기가 오는 유효 계약 중 아직 갱신하지 않은 것을 종기가 가까운 순으로 돌려준다.
     *
     * @param request        조회 요청 전문 (헤더 + 조회 기간, 보험종목, 페이지)
     * @param authentication 인증된 사용자 (JWT)
     * @return 갱신 대상 계약 목록
     * @throws BusinessException 요청 바디가 없는 경우(E-CMN-001), 조회 기간 오류(E-WRK-002), 보험종목 오류(E-SEQ-001), 페이징 값 오류(E-CTR-016)
     */
    @Operation(summary = "만기·갱신 대상을 조회한다", description = "오늘부터 지정한 일수(1~60, 기본 60) 안에 보험종기가 오는 유효 계약 중 아직 갱신하지 않은 것을 종기가 가까운 순으로 돌려준다. AGENT 만 가진 사용자는 본인 접수 건만 나온다.\n\n오류: 요청 바디가 없는 경우(E-CMN-001), 조회 기간 오류(E-WRK-002), 보험종목 오류(E-SEQ-001), 페이징 값 오류(E-CTR-016)")
    @PostMapping("/renewal-targets")
    public CommonMessage<RenewalTargetResponse> renewalTargets(
            @RequestBody CommonMessage<RenewalTargetRequest> request, Authentication authentication) {
        RenewalTargetRequest body = requireBody(request);
        String ownerEmpId = ContractAccessPolicy.ownContractsOnly(authentication) ? authentication.getName() : null;

        return success(request.getHeader(), workService.renewalTargets(ownerEmpId, body));
    }
}
