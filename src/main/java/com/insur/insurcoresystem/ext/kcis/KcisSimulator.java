package com.insur.insurcoresystem.ext.kcis;

import static com.insur.insurcoresystem.ext.FixedLengthCodec.CHARSET;

import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.FixedLengthCodec;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 모의 신용정보원 (개발·테스트 전용, {@code app.ext.kcis.mode=simulator} 일 때만, 기본값). 실제 기관처럼 전문을 받아 해석하고 전문으로 답한다.
 * 다른 보험사 실손 계약은 TB_SIM_KCIS_CONTRACT 에서 찾고, 우리 회사가 보낸 계약정보 집중(R001)도 그 표에 쌓는다.
 * <ul>
 *     <li>장애 흉내: 피보험자 식별값이 {@code ERR} 로 시작하면 응답 없이 시간 초과를 낸다. {@link #setFailAll(boolean)} 로 모든 전송을 실패시킬 수 있다.</li>
 *     <li>오류 응답 흉내: 피보험자 식별값이 {@code BAD} 로 시작하면 응답코드 E101(조회 대상 오류)로 답한다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ext.kcis.mode", havingValue = "simulator", matchIfMissing = true)
public class KcisSimulator implements KcisTransport {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicBoolean failAll = new AtomicBoolean(false);

    public KcisSimulator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 모든 전송을 시간 초과로 실패시킨다 (재전송 확인용) */
    public void setFailAll(boolean fail) {
        failAll.set(fail);
    }

    @Override
    public byte[] exchange(byte[] request) {
        String message = new String(request, CHARSET);
        Map<String, String> header = FixedLengthCodec.decode(KcisLayouts.HEADER, message, 0);
        int headerLength = FixedLengthCodec.length(KcisLayouts.HEADER);
        if (Integer.parseInt(header.get("length")) != request.length) {
            return respond(header, "E001", "");   // 전문 길이 불일치
        }
        if (failAll.get()) {
            throw new ExternalAgencyException(KcisLayouts.AGENCY, "모의 신용정보원 응답 시간 초과 (전체 장애 흉내)", (Throwable) null);
        }
        return switch (header.get("txType")) {
            case KcisLayouts.Q001 -> inquire(header, FixedLengthCodec.decode(KcisLayouts.Q001_REQUEST, message, headerLength));
            case KcisLayouts.R001 -> receiveContract(header, message, FixedLengthCodec.decode(KcisLayouts.R001_BODY, message, headerLength));
            case KcisLayouts.R002 -> receive(header, message);
            default -> respond(header, "E002", "");   // 모르는 거래
        };
    }

    private byte[] inquire(Map<String, String> header, Map<String, String> body) {
        String insuredId = body.get("insuredId");
        if (insuredId.startsWith("ERR")) {
            throw new ExternalAgencyException(KcisLayouts.AGENCY, "모의 신용정보원 응답 시간 초과", (Throwable) null);
        }
        if (insuredId.startsWith("BAD")) {
            return respond(header, "E101", "");
        }
        LocalDate base = LocalDate.parse(body.get("baseDate"), YMD);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT INSURER_CD, POLICY_NO, KIND_CD, START_DATE, END_DATE, COVERED_YN, NON_COVERED_YN
                  FROM TB_SIM_KCIS_CONTRACT
                 WHERE INSURED_ID = ? AND START_DATE <= ? AND END_DATE > ?
                 ORDER BY INSURER_CD, POLICY_NO
                """, insuredId, base, base);
        StringBuilder out = new StringBuilder(FixedLengthCodec.encode(KcisLayouts.Q001_RESPONSE_COUNT,
                Map.of("count", String.valueOf(Math.min(rows.size(), KcisLayouts.MAX_ROWS)))));
        rows.stream().limit(KcisLayouts.MAX_ROWS).forEach(r -> {
            Map<String, Object> row = new HashMap<>();
            row.put("insurerCode", r.get("insurer_cd"));
            row.put("policyNo", mask((String) r.get("policy_no")));
            row.put("kind", r.get("kind_cd"));
            row.put("startDate", ((java.sql.Date) r.get("start_date")).toLocalDate().format(YMD));
            row.put("endDate", ((java.sql.Date) r.get("end_date")).toLocalDate().format(YMD));
            row.put("coveredYn", Boolean.TRUE.equals(r.get("covered_yn")) ? "Y" : "N");
            row.put("nonCoveredYn", Boolean.TRUE.equals(r.get("non_covered_yn")) ? "Y" : "N");
            row.put("productName", "실손의료보험");
            out.append(FixedLengthCodec.encode(KcisLayouts.Q001_RESPONSE_ROW, row));
        });
        return respond(header, KcisLayouts.OK, out.toString());
    }

    private byte[] receiveContract(Map<String, String> header, String message, Map<String, String> body) {
        String insurer = header.get("orgCode");
        LocalDate start = LocalDate.parse(body.get("startDate"), YMD);
        LocalDate end = LocalDate.parse(body.get("endDate"), YMD);
        if ("N".equals(body.get("change"))) {
            jdbc.update("""
                    INSERT INTO TB_SIM_KCIS_CONTRACT (INSURED_ID, INSURER_CD, POLICY_NO, KIND_CD, START_DATE, END_DATE, COVERED_YN, NON_COVERED_YN)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (INSURER_CD, POLICY_NO) DO NOTHING
                    """, body.get("insuredId"), insurer, body.get("policyNo"), body.get("kind"), start, end,
                    "Y".equals(body.get("coveredYn")), "Y".equals(body.get("nonCoveredYn")));
        } else {
            jdbc.update("UPDATE TB_SIM_KCIS_CONTRACT SET END_DATE = ? WHERE INSURER_CD = ? AND POLICY_NO = ?",
                    end, insurer, body.get("policyNo"));
        }
        return receive(header, message);
    }

    private byte[] receive(Map<String, String> header, String message) {
        jdbc.update("INSERT INTO TB_SIM_KCIS_RECEIPT (TX_TYPE, TRACE_NO, MESSAGE, RECEIVED_DT) VALUES (?, ?, ?, ?)",
                header.get("txType"), header.get("traceNo"), message, LocalDateTime.now(clock));
        return respond(header, KcisLayouts.OK, "");
    }

    private byte[] respond(Map<String, String> request, String code, String body) {
        Map<String, Object> header = new HashMap<>(request);
        header.put("kind", KcisLayouts.RESPONSE);
        header.put("responseCode", code);
        header.put("txDateTime", LocalDateTime.now(clock).format(TS));
        int total = FixedLengthCodec.length(KcisLayouts.HEADER) + FixedLengthCodec.byteLength(body);
        header.put("length", String.valueOf(total));
        return (FixedLengthCodec.encode(KcisLayouts.HEADER, header) + body).getBytes(CHARSET);
    }

    /** 다른 회사 증권번호는 가운데를 가려서 준다 (조회한 회사가 알 필요가 없는 정보) */
    private static String mask(String policyNo) {
        if (policyNo.length() <= 6) {
            return "*".repeat(policyNo.length());
        }
        return policyNo.substring(0, 3) + "*".repeat(policyNo.length() - 6) + policyNo.substring(policyNo.length() - 3);
    }
}
