package com.insur.insurcoresystem.ext.fep;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.TraceNumbers;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
    private final NamedParameterJdbcTemplate jdbc;
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
        return jdbc.query("""
                SELECT I.*, 
                       (SELECT COUNT(*) FROM TB_FEP_MSG_LOG L WHERE L.INST_CD = I.INST_CD AND L.REQ_DT >= :from AND L.REQ_DT < :to) AS CNT,
                       (SELECT COUNT(*) FROM TB_FEP_MSG_LOG L WHERE L.INST_CD = I.INST_CD AND L.REQ_DT >= :from AND L.REQ_DT < :to AND L.RESULT_CD = 'ERROR') AS ERR,
                       (SELECT COUNT(*) FROM TB_FEP_MSG_LOG L WHERE L.INST_CD = I.INST_CD AND L.REQ_DT >= :from AND L.REQ_DT < :to AND L.RESULT_CD = 'TIMEOUT') AS TMO,
                       (SELECT COUNT(*) FROM TB_FEP_MSG_LOG L WHERE L.INST_CD = I.INST_CD AND L.REQ_DT >= :from AND L.REQ_DT < :to AND L.RESULT_CD = 'BLOCKED') AS BLK,
                       (SELECT ROUND(AVG(ELAPSED_MS)) FROM TB_FEP_MSG_LOG L WHERE L.INST_CD = I.INST_CD AND L.REQ_DT >= :from AND L.REQ_DT < :to AND L.RESULT_CD IN ('OK', 'ERROR')) AS AVG_MS
                  FROM TB_FEP_INST I ORDER BY I.INST_CD
                """, new MapSqlParameterSource("from", today.atStartOfDay()).addValue("to", today.plusDays(1).atStartOfDay()), (rs, i) -> {
            String inst = rs.getString("INST_CD");
            FepGateway.CircuitView c = gateway.circuit(inst);
            Number avg = (Number) rs.getObject("AVG_MS");
            return new InstStatus(inst, rs.getString("INST_NM"), rs.getBoolean("SIGN_ON_YN"), gateway.hasTransport(inst),
                    rs.getObject("LAST_ECHO_DT", LocalDateTime.class), (Boolean) rs.getObject("LAST_ECHO_OK"),
                    c.open(), c.consecutiveFailures(), c.openUntil(), rs.getLong("CNT"), rs.getLong("ERR"), rs.getLong("TMO"),
                    rs.getLong("BLK"), avg == null ? null : avg.longValue(),
                    rs.getObject("UPD_DT", LocalDateTime.class), rs.getString("UPD_EMP_ID"));
        });
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
        jdbc.update("UPDATE TB_FEP_INST SET LAST_ECHO_DT = :now, LAST_ECHO_OK = :ok WHERE INST_CD = :inst",
                new MapSqlParameterSource("now", LocalDateTime.now(clock)).addValue("ok", ok).addValue("inst", instCd));
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
        StringBuilder sql = new StringBuilder("SELECT * FROM TB_FEP_MSG_LOG WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit == null ? 100 : Math.max(1, Math.min(limit, 500)));
        if (StringUtils.hasText(instCd)) {
            sql.append(" AND INST_CD = :inst");
            p.addValue("inst", instCd);
        }
        if (StringUtils.hasText(traceNo)) {
            sql.append(" AND TRACE_NO = :trace");
            p.addValue("trace", traceNo);
        }
        if (StringUtils.hasText(resultCd)) {
            sql.append(" AND RESULT_CD = :result");
            p.addValue("result", resultCd);
        }
        if (date != null) {
            sql.append(" AND REQ_DT >= :from AND REQ_DT < :to");
            p.addValue("from", date.atStartOfDay()).addValue("to", date.plusDays(1).atStartOfDay());
        }
        sql.append(" ORDER BY LOG_ID DESC LIMIT :limit");
        return jdbc.query(sql.toString(), p, (rs, i) -> new MessageLog(rs.getLong("LOG_ID"), rs.getString("INST_CD"),
                rs.getString("TX_TYPE"), rs.getString("TRACE_NO"), rs.getObject("REQ_DT", LocalDateTime.class), rs.getInt("ELAPSED_MS"),
                rs.getString("RESULT_CD"), rs.getString("RESPONSE_CD"), rs.getInt("REQ_BYTES"), (Integer) rs.getObject("RES_BYTES"),
                rs.getString("REQ_HASH"), rs.getString("ERROR_MSG")));
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
        jdbc.update("UPDATE TB_FEP_INST SET SIGN_ON_YN = :on, UPD_DT = :now, UPD_EMP_ID = :emp WHERE INST_CD = :inst",
                new MapSqlParameterSource("on", on).addValue("now", LocalDateTime.now(clock)).addValue("emp", empId).addValue("inst", instCd));
    }

    private InstStatus find(String instCd) {
        return institutions().stream().filter(i -> i.instCd().equals(instCd)).findFirst().orElseThrow();
    }

    private void requireInst(String instCd) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM TB_FEP_INST WHERE INST_CD = :inst",
                new MapSqlParameterSource("inst", instCd == null ? "" : instCd), Integer.class);
        if (n == null || n == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "E-FEP-001", "없는 기관입니다: " + instCd);
        }
    }
}
