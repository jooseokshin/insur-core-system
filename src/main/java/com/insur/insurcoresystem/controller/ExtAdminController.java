package com.insur.insurcoresystem.controller;

import static com.insur.insurcoresystem.common.web.CommonMessages.requireBody;
import static com.insur.insurcoresystem.common.web.CommonMessages.success;

import com.insur.insurcoresystem.common.dto.CommonMessage;
import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.ext.outbox.OutboxService;
import com.insur.insurcoresystem.ext.outbox.dto.OutboxRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대외기관 전송 대기열 운영 API
 */
@Tag(name = "대외기관 연계 운영", description = "대외기관 전송 대기열 조회와 멈춘 건 재전송")
@RestController
@RequestMapping("/api/admin/ext")
@RequiredArgsConstructor
public class ExtAdminController {

    private final OutboxService outboxService;

    /**
     * 대기열을 최근순으로 조회한다. (POST /api/admin/ext/outbox-list, OPERATOR/ADMIN)
     *
     * @throws BusinessException 없는 상태코드(E-EXT-001)
     */
    @Operation(summary = "대외기관 전송 대기열을 조회한다", description = "최근순. 재시도 한도를 넘어 멈춘(90) 건을 찾는 데 쓴다.\n\n오류: 없는 상태코드(E-EXT-001)")
    @PostMapping("/outbox-list")
    public CommonMessage<List<OutboxService.Entry>> list(@RequestBody CommonMessage<OutboxRequests.ListCond> request) {
        OutboxRequests.ListCond body = requireBody(request);
        return success(request.getHeader(), outboxService.list(body.getStatCd(), body.getBizKeyPrefix(),
                body.getLimit() == null ? 50 : body.getLimit()));
    }

    /**
     * 멈춘(90) 건을 다시 전송 대기로 돌린다. (POST /api/admin/ext/outbox-retry, ADMIN)
     *
     * @throws BusinessException 일련번호 누락(E-EXT-002), 없는 건(E-EXT-002, 404), 멈춘 건이 아님(E-EXT-003, 409)
     */
    @Operation(summary = "멈춘 대외기관 전송을 다시 보낸다", description = "원인(전문 오류·기관 장애)을 확인한 뒤 쓴다. 전문과 추적번호는 그대로 다시 보낸다.\n\n"
            + "오류: 없는 건(E-EXT-002, 404), 멈춘 건이 아님(E-EXT-003, 409)")
    @PostMapping("/outbox-retry")
    public CommonMessage<OutboxService.Entry> retry(@RequestBody CommonMessage<OutboxRequests.Retry> request) {
        OutboxRequests.Retry body = requireBody(request);
        if (body.getOutboxId() == null) {
            throw new BusinessException("E-EXT-002", "대기열 일련번호를 입력해야 합니다.");
        }
        return success(request.getHeader(), outboxService.retry(body.getOutboxId(), request.getHeader().getEmpId()));
    }
}
