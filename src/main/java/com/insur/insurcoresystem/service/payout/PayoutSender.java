package com.insur.insurcoresystem.service.payout;

import com.insur.insurcoresystem.common.security.AccountCipher;
import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.bank.BankClient;
import com.insur.insurcoresystem.ext.bank.BankLayouts;
import com.insur.insurcoresystem.ext.outbox.OutboxSender;
import com.insur.insurcoresystem.mapper.payout.PayoutMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지급 원장의 이체 대기(10)와 결과 불명(70)을 처리한다. 한 건씩 {@code FOR UPDATE SKIP LOCKED} 로 집는다.
 *
 * <h3>이중 지급을 막는 규칙</h3>
 * 이체 요청에 응답이 없으면 은행이 처리했는지 알 수 없다. 이때 같은 이체를 다시 보내면 은행이 이미 처리한 경우 돈이 두 번 나간다.
 * 그래서 응답이 없으면 결과 불명(70)으로 두고, 다음에는 이체가 아니라 <b>결과 조회</b>(원거래 거래고유번호)를 보낸다.
 * <pre>
 *   대기(10) ─이체─▶ 정상 응답 → 완료(30)
 *                   계좌 오류 응답(B101~B103) → 실패(80, 계좌를 고쳐 새로 요청)
 *                   중복 거래고유번호(B901) → 결과 불명(70)
 *                   응답 없음 → 결과 불명(70)
 *                   FEP 가 보내지 않음(종료·회선 차단) → 대기(10) 유지, 나중에 다시
 *   결과 불명(70) ─결과 조회─▶ S 처리됨 → 완료(30) / F 실패 → 실패(80) / N 받은 적 없음 → 대기(10), 같은 거래고유번호로 다시 이체
 *                             조회도 응답 없음 → 결과 불명(70) 유지, 간격을 늘려 다시 조회 (포기하지 않는다)
 * </pre>
 */
@Slf4j
@Component
public class PayoutSender {

    private static final int BATCH = 100;
    private static final String MEMO = "보험금지급";
    /** 결과 불명이 된 뒤 첫 결과 조회까지 기다리는 시간 */
    static final Duration INQUIRY_DELAY = Duration.ofSeconds(30);

    private final PayoutMapper payoutMapper;
    private final TransactionTemplate tx;
    private final BankClient bank;
    private final AccountCipher cipher;
    private final Clock clock;

    public PayoutSender(PayoutMapper payoutMapper, PlatformTransactionManager txManager, BankClient bank, AccountCipher cipher, Clock clock) {
        this.payoutMapper = payoutMapper;
        this.tx = new TransactionTemplate(txManager);
        this.bank = bank;
        this.cipher = cipher;
        this.clock = clock;
    }

    /**
     * 처리할 때가 된 지급을 처리한다.
     *
     * @return 처리한 건수
     */
    public int processDue() {
        if (!cipher.configured()) {
            return 0;
        }
        int handled = 0;
        while (handled < BATCH && Boolean.TRUE.equals(tx.execute(status -> processOne()))) {
            handled++;
        }
        return handled;
    }

    private boolean processOne() {
        LocalDateTime now = LocalDateTime.now(clock);
        var due = payoutMapper.lockNextDue(now);
        if (due.isEmpty()) {
            return false;
        }
        PayoutMapper.Due p = due.get();
        if ("10".equals(p.statCd())) {
            send(p, now);
        } else {
            inquire(p, now);
        }
        return true;
    }

    private void send(PayoutMapper.Due p, LocalDateTime now) {
        long id = p.payoutId();
        int sends = p.sendCount();
        String trace = p.traceNo();
        try {
            BankClient.TransferResult r = bank.transfer(trace, p.bankCd(), cipher.decrypt(p.accountEnc()),
                    p.payAmt().longValueExact(), p.holderNm(), PayoutService.SRC_MILEAGE.equals(p.srcType()) ? "마일리지환급" : MEMO);
            if (r.ok()) {
                update(id, "30", r.code(), "이체 완료", sends + 1, null, now, true, true);
                log.info("지급 이체 완료: 지급={}, 거래고유번호={}", id, trace);
            } else if (BankLayouts.DUPLICATE.equals(r.code())) {
                // 은행이 이미 받은 번호라고 한다 → 처리 결과를 조회해서 정한다
                update(id, "70", r.code(), "이미 처리한 거래고유번호 응답, 결과 조회 예정", sends + 1, null, now.plus(INQUIRY_DELAY), true, false);
            } else {
                update(id, "80", r.code(), "이체 실패: " + BankLayouts.describe(r.code()), sends + 1, null, now, true, true);
                log.warn("지급 이체 실패: 지급={}, 응답={}", id, r.code());
            }
        } catch (ExternalAgencyException e) {
            if (e.isMaybeDelivered()) {
                // 은행이 처리 중일 수 있어 바로 묻지 않고 잠시 뒤에 결과를 조회한다
                update(id, "70", null, "응답 없음 - 결과 조회로 확인: " + e.getMessage(), sends + 1, null, now.plus(INQUIRY_DELAY), true, false);
                log.warn("지급 결과 불명(이체 재전송 금지, 결과 조회 예정): 지급={}, 거래고유번호={}", id, trace);
            } else {
                update(id, "10", null, "보내지 않음: " + e.getMessage(), sends, null, now.plus(OutboxSender.backoff(sends + 1)), false, false);
            }
        }
    }

    private void inquire(PayoutMapper.Due p, LocalDateTime now) {
        long id = p.payoutId();
        int sends = p.sendCount();
        int inquiries = p.inquiryCount() + 1;
        try {
            BankClient.InquiryResult r = bank.inquire(p.traceNo());
            switch (r.status()) {
                case "S" -> {
                    long amount = p.payAmt().longValueExact();
                    String msg = r.amount() == amount ? "결과 조회로 이체 확인" : "결과 조회로 이체 확인 (은행 금액 " + r.amount() + " 다름, 대사 확인 필요)";
                    update(id, "30", "0000", msg, sends, inquiries, now, false, true);
                    log.info("지급 결과 조회: 이체됨 → 완료, 지급={}", id);
                }
                case "F" -> update(id, "80", r.failCode(), "결과 조회로 실패 확인: " + BankLayouts.describe(String.valueOf(r.failCode())),
                        sends, inquiries, now, false, true);
                default -> {
                    // 은행이 받은 적이 없다 → 같은 거래고유번호로 다시 이체해도 안전하다
                    update(id, "10", null, "결과 조회: 은행 미수신 → 다시 이체", sends, inquiries, now, false, false);
                    log.info("지급 결과 조회: 미수신 → 재전송 예정, 지급={}", id);
                }
            }
        } catch (ExternalAgencyException e) {
            update(id, "70", null, "결과 조회 실패, 다시 조회 예정: " + e.getMessage(), sends, inquiries,
                    now.plus(OutboxSender.backoff(inquiries)), false, false);
        }
    }

    private void update(long id, String stat, String code, String msg, int sends, Integer inquiries, LocalDateTime next,
                        boolean markSent, boolean done) {
        LocalDateTime now = LocalDateTime.now(clock);
        payoutMapper.updateResult(id, stat, code, msg.length() > 200 ? msg.substring(0, 200) : msg, sends, inquiries, next,
                markSent, done, now);
    }
}
