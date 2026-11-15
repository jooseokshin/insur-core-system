package com.insur.insurcoresystem.ext.kcis;

import static com.insur.insurcoresystem.ext.FixedLengthCodec.CHARSET;

import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.FixedLengthCodec;
import com.insur.insurcoresystem.ext.fep.FepHeader;
import com.insur.insurcoresystem.ext.fep.FepTransport;
import com.insur.insurcoresystem.mapper.sim.SimulatorMapper;
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
public class KcisSimulator implements FepTransport {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SimulatorMapper sim;
    private final Clock clock;
    private final AtomicBoolean failAll = new AtomicBoolean(false);

    public KcisSimulator(SimulatorMapper sim, Clock clock) {
        this.sim = sim;
        this.clock = clock;
    }

    /** 모든 전송을 시간 초과로 실패시킨다 (재전송 확인용) */
    public void setFailAll(boolean fail) {
        failAll.set(fail);
    }

    @Override
    public String instCd() {
        return KcisLayouts.AGENCY;
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
        if (FepHeader.NETWORK_REQUEST.equals(header.get("kind"))) {
            return respond(header, KcisLayouts.OK, "");   // 개시·종료·회선 확인
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
        List<SimulatorMapper.KcisContract> rows = sim.findKcisContracts(insuredId, base);
        StringBuilder out = new StringBuilder(FixedLengthCodec.encode(KcisLayouts.Q001_RESPONSE_COUNT,
                Map.of("count", String.valueOf(Math.min(rows.size(), KcisLayouts.MAX_ROWS)))));
        rows.stream().limit(KcisLayouts.MAX_ROWS).forEach(r -> {
            Map<String, Object> row = new HashMap<>();
            row.put("insurerCode", r.insurerCd());
            row.put("policyNo", mask(r.policyNo()));
            row.put("kind", r.kindCd());
            row.put("startDate", r.startDate().format(YMD));
            row.put("endDate", r.endDate().format(YMD));
            row.put("coveredYn", r.coveredYn() ? "Y" : "N");
            row.put("nonCoveredYn", r.nonCoveredYn() ? "Y" : "N");
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
            sim.insertKcisContract(body.get("insuredId"), new SimulatorMapper.KcisContract(insurer, body.get("policyNo"),
                    body.get("kind"), start, end, "Y".equals(body.get("coveredYn")), "Y".equals(body.get("nonCoveredYn"))));
        } else {
            sim.updateKcisContractEnd(insurer, body.get("policyNo"), end);
        }
        return receive(header, message);
    }

    private byte[] receive(Map<String, String> header, String message) {
        sim.insertKcisReceipt(header.get("txType"), header.get("traceNo"), message, LocalDateTime.now(clock));
        return respond(header, KcisLayouts.OK, "");
    }

    private byte[] respond(Map<String, String> request, String code, String body) {
        return FepHeader.respond(request, code, body, LocalDateTime.now(clock));
    }

    /** 다른 회사 증권번호는 가운데를 가려서 준다 (조회한 회사가 알 필요가 없는 정보) */
    private static String mask(String policyNo) {
        if (policyNo.length() <= 6) {
            return "*".repeat(policyNo.length());
        }
        return policyNo.substring(0, 3) + "*".repeat(policyNo.length() - 6) + policyNo.substring(policyNo.length() - 3);
    }
}
