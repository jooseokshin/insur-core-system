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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 한국신용정보원 연계 클라이언트. 업무 코드는 이 클래스만 쓰고 전문 배치·통로는 몰라도 된다.
 * <ul>
 *     <li>조회(Q001)는 업무 처리 중에 바로 부르고(동기), 실패하면 {@link ExternalAgencyException} 을 던진다.</li>
 *     <li>집중(R001, R002)은 전문을 만들어 대기열에 쌓고({@code buildContractReport}, {@code buildPaymentReport}),
 *         별도 작업이 {@link #send(String)} 로 보낸다. 다시 보내도 같은 추적번호를 써서 기관이 중복을 가려낼 수 있게 한다.</li>
 *     <li>응답은 전문종별·거래구분·추적번호가 요청과 같은지, 응답코드가 0000 인지 확인한다. 다른 전문이 섞여 오는 사고를 막는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class KcisClient {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final KcisTransport transport;
    private final Clock clock;
    private final String orgCode;

    public KcisClient(KcisTransport transport, Clock clock, @Value("${app.ext.kcis.org-code:K99}") String orgCode) {
        this.transport = transport;
        this.clock = clock;
        this.orgCode = orgCode;
    }

    /** 우리 회사 기관코드 */
    public String orgCode() {
        return orgCode;
    }

    /**
     * 신용정보원에 등록된 다른 보험사 실손 계약 (조회 기준일에 보장 중인 것)
     *
     * @param insurerCode 보험회사 코드
     * @param policyNo    마스킹된 증권번호
     * @param kind        01 개인, 02 단체
     * @param endDate     보장 종료일 (이 날은 보장하지 않음)
     */
    public record OtherContract(String insurerCode, String policyNo, String kind, LocalDate startDate, LocalDate endDate,
                                boolean covered, boolean nonCovered) {
        /** 그 날짜를 보장하는지 */
        public boolean covers(LocalDate date) {
            return !date.isBefore(startDate) && date.isBefore(endDate);
        }
    }

    /**
     * 피보험자의 다른 보험사 실손 계약을 조회한다 (우리 회사 계약은 뺀다).
     *
     * @param traceNo 추적번호 (최대 20자, 거래 UUID 등에서 만든다)
     * @throws ExternalAgencyException 연결 실패·시간 초과·오류 응답
     */
    public List<OtherContract> findMedicalContracts(String insuredId, LocalDate baseDate, String traceNo) {
        String body = FixedLengthCodec.encode(KcisLayouts.Q001_REQUEST, Map.of("insuredId", insuredId, "baseDate", baseDate.format(YMD)));
        String response = exchange(build(KcisLayouts.Q001, traceNo, body));
        int offset = FixedLengthCodec.length(KcisLayouts.HEADER);
        int count = Integer.parseInt(FixedLengthCodec.decode(KcisLayouts.Q001_RESPONSE_COUNT, response, offset).get("count"));
        return FixedLengthCodec.decodeRepeated(KcisLayouts.Q001_RESPONSE_ROW, response,
                        offset + FixedLengthCodec.length(KcisLayouts.Q001_RESPONSE_COUNT), count).stream()
                .filter(r -> !orgCode.equals(r.get("insurerCode")))
                .map(r -> new OtherContract(r.get("insurerCode"), r.get("policyNo"), r.get("kind"),
                        LocalDate.parse(r.get("startDate"), YMD), LocalDate.parse(r.get("endDate"), YMD),
                        "Y".equals(r.get("coveredYn")), "Y".equals(r.get("nonCoveredYn"))))
                .toList();
    }

    /**
     * 실손 계약정보 집중 전문(R001)을 만든다. 성립하면 신규(N), 해지하면 해지(T, 보장종료 = 해지일).
     */
    public String buildContractReport(String traceNo, boolean newContract, String policyNo, String insuredId, LocalDate startDate,
                                      LocalDate endDate, boolean nonCovered, String productName) {
        Map<String, Object> body = new HashMap<>();
        body.put("change", newContract ? "N" : "T");
        body.put("policyNo", policyNo);
        body.put("insuredId", insuredId);
        body.put("kind", "01");
        body.put("startDate", startDate.format(YMD));
        body.put("endDate", endDate.format(YMD));
        body.put("coveredYn", "Y");
        body.put("nonCoveredYn", nonCovered ? "Y" : "N");
        body.put("productName", productName);
        return build(KcisLayouts.R001, traceNo, FixedLengthCodec.encode(KcisLayouts.R001_BODY, body));
    }

    /** 실손 보험금 지급정보 집중 전문(R002)을 만든다 */
    public String buildPaymentReport(String traceNo, String claimNo, String policyNo, String insuredId, LocalDate firstTreatDate,
                                     long coveredPaid, long nonCoveredPaid, LocalDate paidDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("claimNo", claimNo);
        body.put("policyNo", policyNo);
        body.put("insuredId", insuredId);
        body.put("firstTreatDate", firstTreatDate.format(YMD));
        body.put("coveredPaid", String.valueOf(coveredPaid));
        body.put("nonCoveredPaid", String.valueOf(nonCoveredPaid));
        body.put("paidDate", paidDate.format(YMD));
        return build(KcisLayouts.R002, traceNo, FixedLengthCodec.encode(KcisLayouts.R002_BODY, body));
    }

    /** 만들어 둔 전문을 보낸다 (대기열 전송용). 응답이 정상이 아니면 예외 */
    public void send(String message) {
        exchange(message);
    }

    private String build(String txType, String traceNo, String body) {
        Map<String, Object> header = new HashMap<>();
        header.put("kind", KcisLayouts.REQUEST);
        header.put("txType", txType);
        header.put("orgCode", orgCode);
        header.put("txDateTime", LocalDateTime.now(clock).format(TS));
        header.put("traceNo", traceNo.length() > 20 ? traceNo.substring(0, 20) : traceNo);
        header.put("length", String.valueOf(FixedLengthCodec.length(KcisLayouts.HEADER) + FixedLengthCodec.byteLength(body)));
        return FixedLengthCodec.encode(KcisLayouts.HEADER, header) + body;
    }

    private String exchange(String request) {
        Map<String, String> sent = FixedLengthCodec.decode(KcisLayouts.HEADER, request, 0);
        byte[] raw;
        try {
            raw = transport.exchange(request.getBytes(CHARSET));
        } catch (ExternalAgencyException e) {
            log.warn("신용정보원 연계 실패: 거래={}, 추적번호={}, 사유={}", sent.get("txType"), sent.get("traceNo"), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            throw new ExternalAgencyException(KcisLayouts.AGENCY, "신용정보원 연계 중 오류: " + e.getMessage(), e);
        }
        String response = new String(raw, CHARSET);
        Map<String, String> got = FixedLengthCodec.decode(KcisLayouts.HEADER, response, 0);
        if (!KcisLayouts.RESPONSE.equals(got.get("kind")) || !sent.get("txType").equals(got.get("txType"))
                || !sent.get("traceNo").equals(got.get("traceNo"))) {
            throw new ExternalAgencyException(KcisLayouts.AGENCY, null, "요청과 맞지 않는 응답 전문입니다.");
        }
        if (!KcisLayouts.OK.equals(got.get("responseCode"))) {
            throw new ExternalAgencyException(KcisLayouts.AGENCY, got.get("responseCode"),
                    "신용정보원 오류 응답: " + got.get("responseCode"));
        }
        return response;
    }
}
