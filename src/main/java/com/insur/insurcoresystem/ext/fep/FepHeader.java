package com.insur.insurcoresystem.ext.fep;

import static com.insur.insurcoresystem.ext.FixedLengthCodec.CHARSET;
import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.an;
import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.n;

import com.insur.insurcoresystem.ext.FixedLengthCodec;
import com.insur.insurcoresystem.ext.FixedLengthCodec.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FEP 공통부. 기관마다 개별부는 다르지만 공통부는 FEP 가 한 모양으로 맞춘다(이 프로젝트의 정한 배치, 실제 규격 아님).
 * <pre>
 * 공통부 60바이트: 전문길이(4) 전문종별(4) 거래구분(4) 기관코드(3) 거래일시(14) 추적번호(20) 응답코드(4) 예비(7)
 *   전문종별 0200 업무 요청 / 0210 업무 응답, 0800 망관리 요청 / 0810 망관리 응답
 *   망관리 거래구분 N001 개시, N002 종료, N003 회선 확인(에코)
 * </pre>
 */
public final class FepHeader {

    public static final List<Field> LAYOUT = List.of(
            n("length", 4), an("kind", 4), an("txType", 4), an("orgCode", 3), n("txDateTime", 14),
            an("traceNo", 20), an("responseCode", 4), an("reserved", 7));
    public static final int LENGTH = FixedLengthCodec.length(LAYOUT);

    public static final String REQUEST = "0200";
    public static final String RESPONSE = "0210";
    public static final String NETWORK_REQUEST = "0800";
    public static final String NETWORK_RESPONSE = "0810";
    public static final String OK = "0000";
    public static final String SIGN_ON = "N001";
    public static final String SIGN_OFF = "N002";
    public static final String ECHO = "N003";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private FepHeader() {
    }

    /** 요청 전문 (공통부 + 개별부) */
    public static String request(String kind, String txType, String orgCode, String traceNo, LocalDateTime now, String body) {
        Map<String, Object> header = new HashMap<>();
        header.put("kind", kind);
        header.put("txType", txType);
        header.put("orgCode", orgCode);
        header.put("txDateTime", now.format(TS));
        header.put("traceNo", traceNo.length() > 20 ? traceNo.substring(0, 20) : traceNo);
        header.put("length", String.valueOf(LENGTH + FixedLengthCodec.byteLength(body)));
        return FixedLengthCodec.encode(LAYOUT, header) + body;
    }

    /** 공통부 해석 */
    public static Map<String, String> parse(String message) {
        return FixedLengthCodec.decode(LAYOUT, message, 0);
    }

    /** 요청 공통부에 응답코드를 넣어 응답 전문을 만든다 (모의 기관용) */
    public static byte[] respond(Map<String, String> request, String code, String body, LocalDateTime now) {
        Map<String, Object> header = new HashMap<>(request);
        header.put("kind", NETWORK_REQUEST.equals(request.get("kind")) ? NETWORK_RESPONSE : RESPONSE);
        header.put("responseCode", code);
        header.put("txDateTime", now.format(TS));
        header.put("length", String.valueOf(LENGTH + FixedLengthCodec.byteLength(body)));
        return (FixedLengthCodec.encode(LAYOUT, header) + body).getBytes(CHARSET);
    }
}
