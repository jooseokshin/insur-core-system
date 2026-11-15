package com.insur.insurcoresystem.ext.bank;

import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.an;
import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.n;
import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.text;

import com.insur.insurcoresystem.ext.FixedLengthCodec.Field;
import java.util.List;

/**
 * 은행 펌뱅킹 전문 개별부. <b>실제 은행 규격이 아니다.</b> 공통부는 FEP 공통 배치({@link com.insur.insurcoresystem.ext.fep.FepHeader})다.
 * <pre>
 * B100 예금주 조회   요청: 은행(3) 계좌(16)                             응답: 예금주(20)
 * B200 이체          요청: 은행(3) 계좌(16) 금액(13) 받는분(20) 적요(20)   응답: 처리일시(14)   (공통부 추적번호 = 거래고유번호)
 * B300 이체 결과 조회 요청: 원거래 거래고유번호(20)                       응답: 상태(1: S 처리, F 실패, N 받은 적 없음) 금액(13) 처리일시(14) 실패코드(4)
 * B900 이체 내역     요청: 처리일(8) 페이지(4)                          응답: 건수(2) 다음 있음(1) + 건수 × [거래고유번호(20) 은행(3) 계좌(16) 금액(13) 처리일시(14)]
 * 응답코드: 0000 정상, B101 없는 계좌, B102 해지 계좌, B103 지급정지 계좌, B901 이미 처리한 거래고유번호, E001 전문 길이 오류
 * </pre>
 */
public final class BankLayouts {

    public static final String INST = "BANK";
    public static final String VERIFY = "B100";
    public static final String TRANSFER = "B200";
    public static final String INQUIRY = "B300";
    public static final String LIST = "B900";
    public static final String NO_ACCOUNT = "B101";
    public static final String CLOSED = "B102";
    public static final String FROZEN = "B103";
    public static final String DUPLICATE = "B901";
    public static final int PAGE_SIZE = 50;

    public static final List<Field> VERIFY_REQ = List.of(an("bankCd", 3), an("accountNo", 16));
    public static final List<Field> VERIFY_RES = List.of(text("holderNm", 20));
    public static final List<Field> TRANSFER_REQ = List.of(an("bankCd", 3), an("accountNo", 16), n("amount", 13),
            text("recipientNm", 20), text("memo", 20));
    public static final List<Field> TRANSFER_RES = List.of(n("procDt", 14));
    public static final List<Field> INQUIRY_REQ = List.of(an("orgTraceNo", 20));
    public static final List<Field> INQUIRY_RES = List.of(an("status", 1), n("amount", 13), n("procDt", 14), an("failCode", 4));
    public static final List<Field> LIST_REQ = List.of(n("date", 8), n("page", 4));
    public static final List<Field> LIST_RES_HEAD = List.of(n("count", 2), an("more", 1));
    public static final List<Field> LIST_RES_ROW = List.of(an("traceNo", 20), an("bankCd", 3), an("accountNo", 16), n("amount", 13),
            n("procDt", 14));

    private BankLayouts() {
    }

    /** 응답코드 설명 */
    public static String describe(String code) {
        return switch (code) {
            case "0000" -> "정상";
            case NO_ACCOUNT -> "없는 계좌";
            case CLOSED -> "해지된 계좌";
            case FROZEN -> "지급정지 계좌";
            case DUPLICATE -> "이미 처리한 거래고유번호";
            default -> "은행 오류 " + code;
        };
    }
}
