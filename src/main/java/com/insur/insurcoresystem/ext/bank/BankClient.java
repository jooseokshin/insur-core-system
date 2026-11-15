package com.insur.insurcoresystem.ext.bank;

import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.FixedLengthCodec;
import com.insur.insurcoresystem.ext.TraceNumbers;
import com.insur.insurcoresystem.ext.fep.FepGateway;
import com.insur.insurcoresystem.ext.fep.FepHeader;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 은행 펌뱅킹 클라이언트 (FEP 경유). 응답코드는 예외로 바꾸지 않고 결과로 돌려준다: 계좌 오류는 업무 판단(실패 처리)이고,
 * 응답이 없는 것만 {@link ExternalAgencyException} 이다(보냈는지 여부는 {@link ExternalAgencyException#isMaybeDelivered()}).
 */
@Component
public class BankClient {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final FepGateway fep;
    private final TraceNumbers traceNumbers;
    private final Clock clock;
    private final String orgCode;

    public BankClient(FepGateway fep, TraceNumbers traceNumbers, Clock clock, @Value("${app.ext.kcis.org-code:K99}") String orgCode) {
        this.fep = fep;
        this.traceNumbers = traceNumbers;
        this.clock = clock;
        this.orgCode = orgCode;
    }

    /** 예금주 조회 결과. 정상이면 holderNm 이 있다 */
    public record VerifyResult(String code, String holderNm) {
        public boolean ok() {
            return FepHeader.OK.equals(code);
        }
    }

    /** 이체 결과 (응답코드) */
    public record TransferResult(String code, LocalDateTime procDt) {
        public boolean ok() {
            return FepHeader.OK.equals(code);
        }
    }

    /** 결과 조회: status S 처리, F 실패, N 받은 적 없음 */
    public record InquiryResult(String status, long amount, LocalDateTime procDt, String failCode) {
    }

    /** 은행 이체 내역 한 건 */
    public record BankTransfer(String traceNo, String bankCd, String accountNo, long amount, LocalDateTime procDt) {
    }

    public VerifyResult verify(String bankCd, String accountNo) {
        String response = send(BankLayouts.VERIFY, traceNumbers.next(),
                FixedLengthCodec.encode(BankLayouts.VERIFY_REQ, Map.of("bankCd", bankCd, "accountNo", accountNo)));
        String code = code(response);
        if (!FepHeader.OK.equals(code)) {
            return new VerifyResult(code, null);
        }
        return new VerifyResult(code, FixedLengthCodec.decode(BankLayouts.VERIFY_RES, response, FepHeader.LENGTH).get("holderNm"));
    }

    /** 이체. 공통부 추적번호가 거래고유번호다 (재전송해도 같은 번호) */
    public TransferResult transfer(String traceNo, String bankCd, String accountNo, long amount, String recipientNm, String memo) {
        String response = send(BankLayouts.TRANSFER, traceNo, FixedLengthCodec.encode(BankLayouts.TRANSFER_REQ, Map.of(
                "bankCd", bankCd, "accountNo", accountNo, "amount", amount, "recipientNm", recipientNm, "memo", memo)));
        String code = code(response);
        LocalDateTime procDt = FepHeader.OK.equals(code)
                ? LocalDateTime.parse(FixedLengthCodec.decode(BankLayouts.TRANSFER_RES, response, FepHeader.LENGTH).get("procDt"), TS) : null;
        return new TransferResult(code, procDt);
    }

    /** 이체 결과 조회 (원거래 거래고유번호로) */
    public InquiryResult inquire(String orgTraceNo) {
        String response = send(BankLayouts.INQUIRY, traceNumbers.next(),
                FixedLengthCodec.encode(BankLayouts.INQUIRY_REQ, Map.of("orgTraceNo", orgTraceNo)));
        String code = code(response);
        if (!FepHeader.OK.equals(code)) {
            throw new ExternalAgencyException(BankLayouts.INST, code, "이체 결과 조회 오류 응답: " + code);
        }
        Map<String, String> r = FixedLengthCodec.decode(BankLayouts.INQUIRY_RES, response, FepHeader.LENGTH);
        String procDt = r.get("procDt");
        return new InquiryResult(r.get("status"), Long.parseLong(r.get("amount")),
                procDt.chars().allMatch(c -> c == '0') ? null : LocalDateTime.parse(procDt, TS), r.get("failCode").isBlank() ? null : r.get("failCode"));
    }

    /** 처리일의 이체 내역 전부 (페이지를 넘기며 받는다) */
    public List<BankTransfer> transfers(LocalDate date) {
        List<BankTransfer> all = new ArrayList<>();
        int page = 0;
        boolean more = true;
        while (more) {
            String response = send(BankLayouts.LIST, traceNumbers.next(),
                    FixedLengthCodec.encode(BankLayouts.LIST_REQ, Map.of("date", date.format(YMD), "page", page)));
            String code = code(response);
            if (!FepHeader.OK.equals(code)) {
                throw new ExternalAgencyException(BankLayouts.INST, code, "이체 내역 조회 오류 응답: " + code);
            }
            Map<String, String> head = FixedLengthCodec.decode(BankLayouts.LIST_RES_HEAD, response, FepHeader.LENGTH);
            int count = Integer.parseInt(head.get("count"));
            for (Map<String, String> r : FixedLengthCodec.decodeRepeated(BankLayouts.LIST_RES_ROW, response,
                    FepHeader.LENGTH + FixedLengthCodec.length(BankLayouts.LIST_RES_HEAD), count)) {
                all.add(new BankTransfer(r.get("traceNo"), r.get("bankCd"), r.get("accountNo"), Long.parseLong(r.get("amount")),
                        LocalDateTime.parse(r.get("procDt"), TS)));
            }
            more = "Y".equals(head.get("more"));
            page++;
        }
        return all;
    }

    private String send(String txType, String traceNo, String body) {
        return fep.exchange(BankLayouts.INST, FepHeader.request(FepHeader.REQUEST, txType, orgCode, traceNo, LocalDateTime.now(clock), body));
    }

    private static String code(String response) {
        return FepHeader.parse(response).get("responseCode");
    }
}
