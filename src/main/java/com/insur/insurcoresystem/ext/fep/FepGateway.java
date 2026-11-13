package com.insur.insurcoresystem.ext.fep;

import static com.insur.insurcoresystem.ext.FixedLengthCodec.CHARSET;

import com.insur.insurcoresystem.ext.ExternalAgencyException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FEP(Front-End Processor, 대외계 중계). 업무 코드는 기관과 직접 연결하지 않고 이 게이트웨이로 전문을 보낸다.
 * 기관마다 따로 만들면 흩어지는 공통 기능을 한곳에서 한다.
 * <ul>
 *     <li><b>개시·종료</b>: 개시(TB_FEP_INST.SIGN_ON_YN)한 기관에만 업무 전문을 보낸다. 기관 정기 점검 시간에는 종료해 두면 전문이 나가지 않고
 *         호출자는 "보내지 않음"({@link ExternalAgencyException#notSent})을 받아 대기열에 남긴다.</li>
 *     <li><b>회선 차단(서킷 브레이커)</b>: 응답 없음이 연속 {@code app.ext.fep.circuit-failures}번이면 {@code circuit-open-seconds} 동안 보내지 않는다.
 *         죽은 기관에 요청을 계속 걸어 스레드·연결을 잡아 두는 장애 전파를 막는다. 시간이 지나면 한 번 보내 보고(반개방) 성공하면 닫는다.
 *         차단 상태는 서버마다 메모리에 둔다.</li>
 *     <li><b>전문 로그</b>: 모든 송수신을 {@link FepMessageLogger} 로 남긴다 (본문 대신 해시, 업무 롤백과 무관).</li>
 *     <li><b>응답 확인</b>: 응답 공통부의 종별·거래구분·추적번호가 요청과 같아야 받는다. 응답코드 판단은 업무 쪽이 한다.</li>
 * </ul>
 */
@Slf4j
@Component
public class FepGateway {

    private final Map<String, FepTransport> transports;
    private final NamedParameterJdbcTemplate jdbc;
    private final FepMessageLogger messageLogger;
    private final Clock clock;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

    public FepGateway(List<FepTransport> transports, NamedParameterJdbcTemplate jdbc, FepMessageLogger messageLogger, Clock clock,
                      @Value("${app.ext.fep.circuit-failures:5}") int failureThreshold,
                      @Value("${app.ext.fep.circuit-open-seconds:60}") int openSeconds) {
        this.transports = transports.stream().collect(Collectors.toMap(FepTransport::instCd, Function.identity()));
        this.jdbc = jdbc;
        this.messageLogger = messageLogger;
        this.clock = clock;
        this.failureThreshold = failureThreshold;
        this.openDuration = Duration.ofSeconds(openSeconds);
    }

    /** 회선 차단 상태 (서버별 메모리) */
    public record CircuitView(boolean open, int consecutiveFailures, Instant openUntil) {
    }

    private static final class Circuit {
        int failures;
        Instant openUntil;
    }

    /**
     * 업무 전문을 보내고 응답 전문을 받는다.
     *
     * @throws ExternalAgencyException 보내지 않음(종료·차단·통로 없음, maybeDelivered=false), 응답 없음(maybeDelivered=true), 응답 불일치
     */
    public String exchange(String instCd, String request) {
        Map<String, String> header = FepHeader.parse(request);
        boolean network = FepHeader.NETWORK_REQUEST.equals(header.get("kind"));
        byte[] bytes = request.getBytes(CHARSET);
        LocalDateTime reqDt = LocalDateTime.now(clock);
        String txType = header.get("txType");
        String traceNo = header.get("traceNo");

        FepTransport transport = transports.get(instCd);
        String blocked = transport == null ? "통로가 없는 기관입니다" : !network && !signedOn(instCd) ? "개시하지 않은(종료된) 기관입니다"
                : !network && isOpen(instCd) ? "응답 없음이 이어져 회선을 잠시 차단했습니다" : null;
        if (blocked != null) {
            messageLogger.log(instCd, txType, traceNo, reqDt, 0, "BLOCKED", null, bytes, null, blocked);
            throw ExternalAgencyException.notSent(instCd, instCd + ": " + blocked);
        }

        long started = System.nanoTime();
        byte[] raw;
        try {
            raw = transport.exchange(bytes);
        } catch (RuntimeException e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            recordFailure(instCd);
            messageLogger.log(instCd, txType, traceNo, reqDt, elapsed, "TIMEOUT", null, bytes, null, e.getMessage());
            log.warn("FEP 응답 없음: 기관={}, 거래={}, 추적번호={}, 사유={}", instCd, txType, traceNo, e.getMessage());
            throw new ExternalAgencyException(instCd, instCd + " 응답 없음: " + e.getMessage(), e);
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String response = new String(raw, CHARSET);
        Map<String, String> got;
        try {
            got = FepHeader.parse(response);
        } catch (RuntimeException e) {
            messageLogger.log(instCd, txType, traceNo, reqDt, elapsed, "ERROR", null, bytes, raw.length, "응답 해석 실패");
            throw new ExternalAgencyException(instCd, null, instCd + " 응답 전문을 해석하지 못했습니다.");
        }
        recordSuccess(instCd);
        String expectedKind = network ? FepHeader.NETWORK_RESPONSE : FepHeader.RESPONSE;
        if (!expectedKind.equals(got.get("kind")) || !txType.equals(got.get("txType")) || !traceNo.equals(got.get("traceNo"))) {
            messageLogger.log(instCd, txType, traceNo, reqDt, elapsed, "ERROR", got.get("responseCode"), bytes, raw.length, "요청과 맞지 않는 응답");
            throw new ExternalAgencyException(instCd, null, instCd + " 요청과 맞지 않는 응답 전문입니다.");
        }
        String code = got.get("responseCode");
        messageLogger.log(instCd, txType, traceNo, reqDt, elapsed, FepHeader.OK.equals(code) ? "OK" : "ERROR", code, bytes, raw.length, null);
        return response;
    }

    /** 회선 차단 상태 */
    public CircuitView circuit(String instCd) {
        Circuit c = circuits.get(instCd);
        if (c == null) {
            return new CircuitView(false, 0, null);
        }
        synchronized (c) {
            return new CircuitView(c.openUntil != null && Instant.now(clock).isBefore(c.openUntil), c.failures, c.openUntil);
        }
    }

    /** 회선 확인 성공 등으로 차단을 푼다 */
    public void resetCircuit(String instCd) {
        circuits.remove(instCd);
    }

    /** 통로가 있는 기관인지 */
    public boolean hasTransport(String instCd) {
        return transports.containsKey(instCd);
    }

    private boolean signedOn(String instCd) {
        List<Boolean> on = jdbc.query("SELECT SIGN_ON_YN FROM TB_FEP_INST WHERE INST_CD = :inst",
                new MapSqlParameterSource("inst", instCd), (rs, i) -> rs.getBoolean(1));
        return !on.isEmpty() && on.get(0);
    }

    private boolean isOpen(String instCd) {
        Circuit c = circuits.get(instCd);
        if (c == null) {
            return false;
        }
        synchronized (c) {
            // 차단 시간이 지나면 한 번 통과시킨다(반개방). 실패하면 recordFailure 가 다시 차단한다
            return c.openUntil != null && Instant.now(clock).isBefore(c.openUntil);
        }
    }

    private void recordFailure(String instCd) {
        Circuit c = circuits.computeIfAbsent(instCd, k -> new Circuit());
        synchronized (c) {
            c.failures++;
            if (c.failures >= failureThreshold) {
                c.openUntil = Instant.now(clock).plus(openDuration);
                log.error("FEP 회선 차단: 기관={}, 연속 응답 없음 {}번, {}까지", instCd, c.failures, c.openUntil);
            }
        }
    }

    private void recordSuccess(String instCd) {
        Circuit c = circuits.get(instCd);
        if (c != null) {
            synchronized (c) {
                c.failures = 0;
                c.openUntil = null;
            }
        }
    }
}
