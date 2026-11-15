package com.insur.insurcoresystem.ext.outbox;

import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.kcis.KcisClient;
import com.insur.insurcoresystem.ext.kcis.KcisLayouts;
import com.insur.insurcoresystem.mapper.ext.OutboxMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대기열(TB_EXT_OUTBOX)의 전문을 기관에 보낸다.
 * <ul>
 *     <li>한 건씩 {@code FOR UPDATE SKIP LOCKED} 로 집어서 보낸다. 인스턴스가 여럿이어도 같은 건을 둘이 동시에 보내지 않는다.</li>
 *     <li>실패하면 다음 시도를 30초 × 2^(시도−1) 뒤(최대 1시간)로 미룬다. 기관 장애 중에 계속 두드려 장애를 키우지 않기 위해서다.</li>
 *     <li>{@value #MAX_ATTEMPTS}번 실패하면 멈춤(90)으로 두고 담당자가 확인한다. 전문 자체가 잘못된 경우 끝없이 재시도하지 않게 한다.</li>
 *     <li>기관이 받았는데 응답만 못 받은 경우 다시 보내게 되므로, 같은 추적번호를 쓰고 기관이 추적번호로 중복을 가려낸다는 전제다.</li>
 * </ul>
 */
@Slf4j
@Component
public class OutboxSender {

    public static final int MAX_ATTEMPTS = 5;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);
    private static final int BATCH = 100;

    private final OutboxMapper outboxMapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<String, Consumer<String>> senders;

    public OutboxSender(OutboxMapper outboxMapper, PlatformTransactionManager txManager, Clock clock, KcisClient kcis) {
        this.outboxMapper = outboxMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.senders = Map.of(KcisLayouts.AGENCY, kcis::send);
    }

    /**
     * 보낼 때가 된 전문을 보낸다 (한 번에 최대 {@value #BATCH}건).
     *
     * @return 처리(성공·실패 모두)한 건수
     */
    public int sendDue() {
        int handled = 0;
        while (handled < BATCH && Boolean.TRUE.equals(tx.execute(status -> sendOne()))) {
            handled++;
        }
        return handled;
    }

    /** 한 건을 보낸다. 보낼 건이 없으면 false */
    private boolean sendOne() {
        LocalDateTime now = LocalDateTime.now(clock);
        var due = outboxMapper.lockNextDue(now);
        if (due.isEmpty()) {
            return false;
        }
        OutboxMapper.Due row = due.get();
        long id = row.outboxId();
        String agency = row.agencyCd();
        int attempts = row.attempts() + 1;
        try {
            Consumer<String> sender = senders.get(agency);
            if (sender == null) {
                throw new ExternalAgencyException(agency, null, "전송 방법이 없는 기관입니다: " + agency);
            }
            sender.accept(row.payload());
            outboxMapper.markSent(id, attempts, now);
            log.info("대외기관 전송 완료: 대기열={}, 기관={}, 거래={}, 업무키={}", id, agency, row.txType(), row.bizKey());
        } catch (RuntimeException e) {
            boolean giveUp = attempts >= MAX_ATTEMPTS;
            outboxMapper.markFailure(id, giveUp ? OutboxService.STAT_FAILED : OutboxService.STAT_PENDING, attempts,
                    now.plus(backoff(attempts)), truncate(e.getMessage()));
            if (giveUp) {
                log.error("대외기관 전송 재시도 한도 초과, 담당자 확인 필요: 대기열={}, 기관={}, 업무키={}, 사유={}", id, agency, row.bizKey(), e.getMessage());
            } else {
                log.warn("대외기관 전송 실패, 다시 시도 예정: 대기열={}, 시도={}, 사유={}", id, attempts, e.getMessage());
            }
        }
        return true;
    }

    /** n번째 실패 뒤 기다릴 시간: 30초 × 2^(n−1), 최대 1시간 */
    public static Duration backoff(int attempts) {
        Duration wait = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts - 1, 20));
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    private static String truncate(String message) {
        String text = message == null ? "알 수 없는 오류" : message;
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
