package com.insur.insurcoresystem.ext.fep;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.TraceNumbers;
import com.insur.insurcoresystem.mapper.ext.FepMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * FEP 운영: 기관별 상태와 오늘 처리량, 개시·종료, 회선 확인(에코), 전문 로그 조회.
 * <ul>
 *     <li>개시(N001): 기관에 개시 전문을 보내 정상 응답을 받아야 개시 상태가 된다. 업무 시작 전에 하거나 점검이 끝난 뒤 한다.</li>
 *     <li>종료(N002): 종료 전문은 보내 보되(기관이 꺼져 있을 수 있음) 실패해도 종료 상태로 둔다. 종료 중 업무 전문은 FEP 가 보내지 않는다.</li>
 *     <li>에코(N003): 업무 없이 회선이 살아 있는지 확인한다. 성공하면 회선 차단도 푼다. 예약 작업이 주기적으로도 부른다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FepAdminService {

    private final FepGateway gateway;
    private final FepMapper fepMapper;
    private final TraceNumbers traceNumbers;
    private final Clock clock;
    @Value("${app.ext.kcis.org-code:K99}")
    private String orgCode;

    /** 기관 상태 */
    public record InstStatus(String instCd, String instNm, boolean signOn, boolean transport, LocalDateTime lastEchoDt,
                             Boolean lastEchoOk, boolean circuitOpen, int consecutiveFailures, Instant circuitOpenUntil,
                             long todayCount, long todayErrors, long todayTimeouts, long todayBlocked, Long todayAvgMs,
                             LocalDateTime updDt, String updEmpId) {
    }

    /** 전문 로그 한 줄 */
    public record MessageLog(long logId, String instCd, String txType, String traceNo, LocalDateTime reqDt, int elapsedMs,
                             String resultCd, String responseCd, int reqBytes, Integer resBytes, String reqHash, String errorMsg) {
    }

    public List<InstStatus> institutions() {
        LocalDate today = LocalDate.now(clock);
        return fepMapper.findInstitutions(today.atStartOfDay(), today.plusDays(1).atStartOfDay()).stream().map(r -> {
            FepGateway.CircuitView c = gateway.circuit(r.instCd());
            return new InstStatus(r.instCd(), r.instNm(), r.signOnYn(), gateway.hasTransport(r.instCd()), r.lastEchoDt(),
                    r.lastEchoOk(), c.open(), c.consecutiveFailures(), c.openUntil(), r.cnt(), r.err(), r.tmo(), r.blk(), r.avgMs(),
                    r.updDt(), r.updEmpId());
        }).toList();
    }

    /**
     * 개시한다.
     *
     * @throws BusinessException 없는 기관(E-FEP-001, 404), 기관이 개시를 받지 않음(E-FEP-002, 503)
     */
    public InstStatus signOn(String instCd, String empId) {
        requireInst(instCd);
        try {
            network(instCd, FepHeader.SIGN_ON);
        } catch (ExternalAgencyException e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "E-FEP-002", instCd + " 개시 실패: " + e.getMessage());
        }
        gateway.resetCircuit(instCd);
        update(instCd, true, empId);
        log.info("FEP 개시: 기관={}, 처리자={}", instCd, empId);
        return find(instCd);
    }

    /** 종료한다. 종료 전문이 실패해도 종료 상태로 둔다 */
    public InstStatus signOff(String instCd, String empId) {
        requireInst(instCd);
        try {
            network(instCd, FepHeader.SIGN_OFF);
        } catch (ExternalAgencyException e) {
            log.warn("FEP 종료 전문 실패(종료 상태로 둔다): 기관={}, 사유={}", instCd, e.getMessage());
        }
        update(instCd, false, empId);
        log.info("FEP 종료: 기관={}, 처리자={}", instCd, empId);
        return find(instCd);
    }

    /** 회선을 확인한다. 실패해도 예외 없이 결과를 남기고 돌려준다 */
    public InstStatus echo(String instCd) {
        requireInst(instCd);
        boolean ok;
        try {
            network(instCd, FepHeader.ECHO);
            gateway.resetCircuit(instCd);
            ok = true;
        } catch (ExternalAgencyException e) {
            ok = false;
        }
        fepMapper.updateEcho(instCd, ok, LocalDateTime.now(clock));
        return find(instCd);
    }

    /** 개시한 기관 모두 회선 확인 (예약 작업) */
    public void echoAll() {
        institutions().stream().filter(i -> i.signOn() && i.transport()).forEach(i -> echo(i.instCd()));
    }

    /**
     * 전문 로그를 최근순으로 조회한다.
     *
     * @throws BusinessException 조건 오류(E-FEP-003)
     */
    public List<MessageLog> messages(String instCd, String traceNo, String resultCd, LocalDate date, Integer limit) {
        if (StringUtils.hasText(resultCd) && !List.of("OK", "ERROR", "TIMEOUT", "BLOCKED").contains(resultCd)) {
            throw new BusinessException("E-FEP-003", "결과는 OK, ERROR, TIMEOUT, BLOCKED 중 하나여야 합니다: " + resultCd);
        }
        return fepMapper.findMessageLogs(FepMapper.MessageLogCondition.builder()
                .instCd(StringUtils.hasText(instCd) ? instCd : null)
                .traceNo(StringUtils.hasText(traceNo) ? traceNo : null)
                .resultCd(StringUtils.hasText(resultCd) ? resultCd : null)
                .reqFrom(date == null ? null : date.atStartOfDay())
                .reqTo(date == null ? null : date.plusDays(1).atStartOfDay())
                .limit(limit == null ? 100 : Math.max(1, Math.min(limit, 500)))
                .build()).stream()
                .map(r -> new MessageLog(r.logId(), r.instCd(), r.txType(), r.traceNo(), r.reqDt(), r.elapsedMs(), r.resultCd(),
                        r.responseCd(), r.reqBytes(), r.resBytes(), r.reqHash(), r.errorMsg()))
                .toList();
    }

    private void network(String instCd, String txType) {
        String response = gateway.exchange(instCd, FepHeader.request(FepHeader.NETWORK_REQUEST, txType, orgCode, traceNumbers.next(),
                LocalDateTime.now(clock), ""));
        String code = FepHeader.parse(response).get("responseCode");
        if (!FepHeader.OK.equals(code)) {
            throw new ExternalAgencyException(instCd, code, instCd + " 망관리 오류 응답: " + code);
        }
    }

    private void update(String instCd, boolean on, String empId) {
        fepMapper.updateSignOn(instCd, on, LocalDateTime.now(clock), empId);
    }

    private InstStatus find(String instCd) {
        return institutions().stream().filter(i -> i.instCd().equals(instCd)).findFirst().orElseThrow();
    }

    private void requireInst(String instCd) {
        if (!fepMapper.existsInst(instCd == null ? "" : instCd)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "E-FEP-001", "없는 기관입니다: " + instCd);
        }
    }
}
