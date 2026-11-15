package com.insur.insurcoresystem.ext.bank;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 모의 은행 (개발·테스트 전용, {@code app.ext.bank.mode=simulator}, 기본값). 계좌는 TB_SIM_BANK_ACCOUNT, 처리한 이체는 TB_SIM_BANK_TRANSFER.
 * <ul>
 *     <li>계좌 끝자리 7777: 이체를 처리한 뒤 응답을 잃는다 (우리는 결과 불명, 실제로는 이체됨 → 그냥 다시 보내면 이중 지급)</li>
 *     <li>계좌 끝자리 8888: 받기 전에 끊긴다 (결과 불명, 실제로는 미처리 → 결과 조회가 N 이면 다시 보내야 함)</li>
 *     <li>같은 거래고유번호의 두 번째 이체는 B901 로 거절한다. {@link #setFailAll(boolean)} 은 모든 전문을 응답 없음으로 만든다.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.ext.bank.mode", havingValue = "simulator", matchIfMissing = true)
public class BankSimulator implements FepTransport {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SimulatorMapper sim;
    private final Clock clock;
    private final AtomicBoolean failAll = new AtomicBoolean(false);

    public BankSimulator(SimulatorMapper sim, Clock clock) {
        this.sim = sim;
        this.clock = clock;
    }

    public void setFailAll(boolean fail) {
        failAll.set(fail);
    }

    @Override
    public String instCd() {
        return BankLayouts.INST;
    }

    @Override
    public byte[] exchange(byte[] request) {
        String message = new String(request, CHARSET);
        Map<String, String> header = FepHeader.parse(message);
        if (Integer.parseInt(header.get("length")) != request.length) {
            return respond(header, "E001", "");
        }
        if (failAll.get()) {
            throw timeout("전체 장애 흉내");
        }
        if (FepHeader.NETWORK_REQUEST.equals(header.get("kind"))) {
            return respond(header, FepHeader.OK, "");
        }
        int at = FepHeader.LENGTH;
        return switch (header.get("txType")) {
            case BankLayouts.VERIFY -> verify(header, FixedLengthCodec.decode(BankLayouts.VERIFY_REQ, message, at));
            case BankLayouts.TRANSFER -> transfer(header, FixedLengthCodec.decode(BankLayouts.TRANSFER_REQ, message, at));
            case BankLayouts.INQUIRY -> inquiry(header, FixedLengthCodec.decode(BankLayouts.INQUIRY_REQ, message, at));
            case BankLayouts.LIST -> list(header, FixedLengthCodec.decode(BankLayouts.LIST_REQ, message, at));
            default -> respond(header, "E002", "");
        };
    }

    private byte[] verify(Map<String, String> header, Map<String, String> body) {
        SimulatorMapper.BankAccount account = sim.findBankAccount(body.get("bankCd"), body.get("accountNo")).orElse(null);
        String code = statusCode(account);
        if (!FepHeader.OK.equals(code)) {
            return respond(header, code, "");
        }
        return respond(header, FepHeader.OK, FixedLengthCodec.encode(BankLayouts.VERIFY_RES, Map.of("holderNm", account.holderNm())));
    }

    private byte[] transfer(Map<String, String> header, Map<String, String> body) {
        String trace = header.get("traceNo");
        String accountNo = body.get("accountNo");
        if (accountNo.endsWith("8888")) {
            throw timeout("받기 전에 연결이 끊김");
        }
        if (sim.findBankTransfer(trace).isPresent()) {
            return respond(header, BankLayouts.DUPLICATE, "");
        }
        SimulatorMapper.BankAccount account = sim.findBankAccount(body.get("bankCd"), accountNo).orElse(null);
        String code = statusCode(account);
        if (!FepHeader.OK.equals(code)) {
            return respond(header, code, "");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        sim.insertBankTransfer(new SimulatorMapper.BankTransfer(trace, body.get("bankCd"), accountNo, Long.parseLong(body.get("amount")),
                body.get("recipientNm"), now));
        if (accountNo.endsWith("7777")) {
            throw timeout("처리 후 응답 유실");
        }
        return respond(header, FepHeader.OK, FixedLengthCodec.encode(BankLayouts.TRANSFER_RES, Map.of("procDt", now.format(TS))));
    }

    private byte[] inquiry(Map<String, String> header, Map<String, String> body) {
        var done = sim.findBankTransfer(body.get("orgTraceNo"));
        Map<String, Object> out = new HashMap<>();
        if (done.isEmpty()) {
            out.put("status", "N");
        } else {
            out.put("status", "S");
            out.put("amount", done.get().amount());
            out.put("procDt", done.get().procDt().format(TS));
        }
        return respond(header, FepHeader.OK, FixedLengthCodec.encode(BankLayouts.INQUIRY_RES, out));
    }

    private byte[] list(Map<String, String> header, Map<String, String> body) {
        LocalDate date = LocalDate.parse(body.get("date"), YMD);
        int page = Integer.parseInt(body.get("page"));
        List<SimulatorMapper.BankTransfer> rows = sim.findBankTransfers(date.atStartOfDay(), date.plusDays(1).atStartOfDay(),
                BankLayouts.PAGE_SIZE + 1, page * BankLayouts.PAGE_SIZE);
        boolean more = rows.size() > BankLayouts.PAGE_SIZE;
        List<SimulatorMapper.BankTransfer> pageRows = more ? rows.subList(0, BankLayouts.PAGE_SIZE) : rows;
        StringBuilder out = new StringBuilder(FixedLengthCodec.encode(BankLayouts.LIST_RES_HEAD,
                Map.of("count", pageRows.size(), "more", more ? "Y" : "N")));
        for (SimulatorMapper.BankTransfer r : pageRows) {
            out.append(FixedLengthCodec.encode(BankLayouts.LIST_RES_ROW, Map.of("traceNo", r.traceNo(), "bankCd", r.bankCd(),
                    "accountNo", r.accountNo(), "amount", r.amount(), "procDt", r.procDt().format(TS))));
        }
        return respond(header, FepHeader.OK, out.toString());
    }

    private static String statusCode(SimulatorMapper.BankAccount account) {
        if (account == null) {
            return BankLayouts.NO_ACCOUNT;
        }
        return switch (account.statCd()) {
            case "CLOSED" -> BankLayouts.CLOSED;
            case "FROZEN" -> BankLayouts.FROZEN;
            default -> FepHeader.OK;
        };
    }

    private byte[] respond(Map<String, String> header, String code, String body) {
        return FepHeader.respond(header, code, body, LocalDateTime.now(clock));
    }

    private static ExternalAgencyException timeout(String why) {
        return new ExternalAgencyException(BankLayouts.INST, "모의 은행 응답 시간 초과 (" + why + ")", (Throwable) null);
    }
}
