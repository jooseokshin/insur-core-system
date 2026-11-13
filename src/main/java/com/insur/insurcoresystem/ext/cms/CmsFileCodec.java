package com.insur.insurcoresystem.ext.cms;

import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.an;
import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.n;
import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.text;

import com.insur.insurcoresystem.ext.FixedLengthCodec;
import com.insur.insurcoresystem.ext.FixedLengthCodec.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 결제원 CMS 파일 (100바이트 고정길이 레코드, 줄바꿈 구분, MS949). <b>실제 결제원 규격이 아니다.</b>
 * <pre>
 * 머리(H): 레코드구분(1) 파일종류(4) 이용기관코드(10) 파일일자(8) 예비
 * 데이터(R) EB11 등록 의뢰: 순번(6) 납부자번호(30) 은행(3) 계좌(16) 예금주(20) 동의방식(1) 동의일(8)
 *           EB12 등록 결과: 순번(6) 납부자번호(30) 결과코드(4)
 *           EB21 출금 의뢰: 순번(6) 납부자번호(30) 은행(3) 계좌(16) 의뢰금액(13)
 *           EB22 출금 결과: 순번(6) 납부자번호(30) 의뢰금액(13) 출금금액(13) 결과코드(4)
 * 꼬리(T): 레코드구분(1) 데이터 건수(8) 금액 합계(15) 예비      (금액 합계: EB21 의뢰금액, EB22 출금금액, 등록 파일은 0)
 * </pre>
 * 파일을 받을 때는 머리의 파일 종류·일자, 레코드 길이, 꼬리의 건수·합계를 실제 데이터와 맞춰 보고 다르면 받지 않는다.
 * 파일 전송 중 잘림·중복은 실무에서 실제로 생기며, 합계가 다른 파일로 수납을 반영하면 되돌리기 어렵다.
 */
public final class CmsFileCodec {

    public static final int RECORD_BYTES = 100;
    public static final String EB11 = "EB11";
    public static final String EB12 = "EB12";
    public static final String EB21 = "EB21";
    public static final String EB22 = "EB22";
    public static final String OK = "0000";
    public static final String INSUFFICIENT = "C301";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    static final List<Field> HEADER = List.of(an("recType", 1), an("fileType", 4), an("orgCode", 10), n("fileDate", 8), an("filler", 77));
    static final List<Field> TRAILER = List.of(an("recType", 1), n("count", 8), n("totalAmt", 15), an("filler", 76));
    static final Map<String, List<Field>> DATA = Map.of(
            EB11, List.of(an("recType", 1), n("seq", 6), an("payerNo", 30), an("bankCd", 3), an("accountNo", 16), text("payerNm", 20),
                    an("consent", 1), n("consentDate", 8), an("filler", 15)),
            EB12, List.of(an("recType", 1), n("seq", 6), an("payerNo", 30), an("resultCd", 4), an("filler", 59)),
            EB21, List.of(an("recType", 1), n("seq", 6), an("payerNo", 30), an("bankCd", 3), an("accountNo", 16), n("amount", 13),
                    an("filler", 31)),
            EB22, List.of(an("recType", 1), n("seq", 6), an("payerNo", 30), n("amount", 13), n("debited", 13), an("resultCd", 4),
                    an("filler", 33)));
    /** 꼬리 합계에 쓰는 금액 항목 (없으면 0) */
    private static final Map<String, String> AMOUNT_FIELD = Map.of(EB21, "amount", EB22, "debited");

    private CmsFileCodec() {
    }

    /** 받은 파일 */
    public record CmsFile(String fileType, LocalDate fileDate, String orgCode, List<Map<String, String>> records, long totalAmount) {
    }

    /** 파일을 받을 수 없음 (형식·건수·합계 불일치) */
    public static class InvalidFileException extends IllegalStateException {
        public InvalidFileException(String message) {
            super(message);
        }
    }

    /** 파일을 만든다. 순번과 꼬리 레코드는 여기서 채운다 */
    public static String build(String fileType, LocalDate fileDate, String orgCode, List<Map<String, Object>> records) {
        List<String> lines = new ArrayList<>();
        lines.add(FixedLengthCodec.encode(HEADER, Map.of("recType", "H", "fileType", fileType, "orgCode", orgCode, "fileDate", fileDate.format(YMD))));
        long total = 0;
        int seq = 0;
        for (Map<String, Object> r : records) {
            Map<String, Object> row = new HashMap<>(r);
            row.put("recType", "R");
            row.put("seq", ++seq);
            lines.add(FixedLengthCodec.encode(DATA.get(fileType), row));
            String amountField = AMOUNT_FIELD.get(fileType);
            if (amountField != null) {
                total += Long.parseLong(String.valueOf(r.get(amountField)));
            }
        }
        lines.add(FixedLengthCodec.encode(TRAILER, Map.of("recType", "T", "count", records.size(), "totalAmt", total)));
        return String.join("\n", lines);
    }

    /**
     * 파일을 해석하고 검증한다.
     *
     * @throws InvalidFileException 머리·꼬리 누락, 파일 종류·일자 불일치, 레코드 길이 오류, 건수·합계 불일치
     */
    public static CmsFile parse(String expectedType, LocalDate expectedDate, String content) {
        String[] lines = content.split("\n", -1);
        if (lines.length < 2) {
            throw new InvalidFileException(expectedType + " 파일에 머리·꼬리 레코드가 없습니다.");
        }
        for (int i = 0; i < lines.length; i++) {
            if (FixedLengthCodec.byteLength(lines[i]) != RECORD_BYTES) {
                throw new InvalidFileException(expectedType + " 파일 " + (i + 1) + "번째 레코드 길이가 " + RECORD_BYTES + "바이트가 아닙니다.");
            }
        }
        Map<String, String> head = FixedLengthCodec.decode(HEADER, lines[0], 0);
        if (!"H".equals(head.get("recType")) || !expectedType.equals(head.get("fileType"))
                || !expectedDate.format(YMD).equals(head.get("fileDate"))) {
            throw new InvalidFileException("기대한 파일(" + expectedType + ", " + expectedDate + ")이 아닙니다: " + head.get("fileType") + " " + head.get("fileDate"));
        }
        Map<String, String> tail = FixedLengthCodec.decode(TRAILER, lines[lines.length - 1], 0);
        if (!"T".equals(tail.get("recType"))) {
            throw new InvalidFileException(expectedType + " 파일에 꼬리 레코드가 없습니다 (파일이 잘렸을 수 있음).");
        }
        List<Map<String, String>> records = new ArrayList<>();
        long total = 0;
        String amountField = AMOUNT_FIELD.get(expectedType);
        for (int i = 1; i < lines.length - 1; i++) {
            Map<String, String> r = FixedLengthCodec.decode(DATA.get(expectedType), lines[i], 0);
            if (!"R".equals(r.get("recType"))) {
                throw new InvalidFileException(expectedType + " 파일 " + (i + 1) + "번째 레코드가 데이터 레코드가 아닙니다.");
            }
            records.add(r);
            if (amountField != null) {
                total += Long.parseLong(r.get(amountField));
            }
        }
        if (Integer.parseInt(tail.get("count")) != records.size() || Long.parseLong(tail.get("totalAmt")) != total) {
            throw new InvalidFileException(expectedType + " 파일 꼬리 레코드와 데이터가 다릅니다 (건수 " + Integer.parseInt(tail.get("count")) + "/" + records.size()
                    + ", 합계 " + Long.parseLong(tail.get("totalAmt")) + "/" + total + ").");
        }
        return new CmsFile(expectedType, expectedDate, head.get("orgCode"), records, total);
    }

    /** 결과코드 설명 */
    public static String describe(String code) {
        return switch (code) {
            case OK -> "정상";
            case "B101" -> "없는 계좌";
            case "B102" -> "해지된 계좌";
            case "B103" -> "지급정지 계좌";
            case "C201" -> "예금주 불일치";
            case INSUFFICIENT -> "잔액 부족";
            case "C302" -> "등록되지 않은 납부자";
            default -> "결제원 오류 " + code;
        };
    }
}
