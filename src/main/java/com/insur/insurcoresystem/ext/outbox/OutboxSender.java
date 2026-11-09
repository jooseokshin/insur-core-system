package com.insur.insurcoresystem.ext.outbox;

import com.insur.insurcoresystem.ext.ExternalAgencyException;
import com.insur.insurcoresystem.ext.kcis.KcisClient;
import com.insur.insurcoresystem.ext.kcis.KcisLayouts;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<String, Consumer<String>> senders;

    public OutboxSender(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock, KcisClient kcis) {
        this.jdbc = jdbc;
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
        var due = jdbc.query("""
                SELECT OUTBOX_ID, AGENCY_CD, TX_TYPE, BIZ_KEY, PAYLOAD, ATTEMPTS FROM TB_EXT_OUTBOX
                 WHERE STAT_CD = '10' AND NEXT_TRY_DT <= :now
                 ORDER BY NEXT_TRY_DT, OUTBOX_ID
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource("now", now), (rs, i) -> new Object[] {
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)});
        if (due.isEmpty()) {
            return false;
        }
        Object[] row = due.get(0);
        long id = (Long) row[0];
        String agency = (String) row[1];
        int attempts = (Integer) row[5] + 1;
        try {
            Consumer<String> sender = senders.get(agency);
            if (sender == null) {
                throw new ExternalAgencyException(agency, null, "전송 방법이 없는 기관입니다: " + agency);
            }
            sender.accept((String) row[4]);
            jdbc.update("UPDATE TB_EXT_OUTBOX SET STAT_CD = '20', ATTEMPTS = :attempts, SENT_DT = :now, LAST_ERROR = NULL WHERE OUTBOX_ID = :id",
                    new MapSqlParameterSource("id", id).addValue("attempts", attempts).addValue("now", now));
            log.info("대외기관 전송 완료: 대기열={}, 기관={}, 거래={}, 업무키={}", id, agency, row[2], row[3]);
        } catch (RuntimeException e) {
            boolean giveUp = attempts >= MAX_ATTEMPTS;
            jdbc.update("""
                    UPDATE TB_EXT_OUTBOX SET STAT_CD = :stat, ATTEMPTS = :attempts, NEXT_TRY_DT = :next, LAST_ERROR = :error
                     WHERE OUTBOX_ID = :id""", new MapSqlParameterSource("id", id)
                    .addValue("stat", giveUp ? OutboxService.STAT_FAILED : OutboxService.STAT_PENDING)
                    .addValue("attempts", attempts)
                    .addValue("next", now.plus(backoff(attempts)))
                    .addValue("error", truncate(e.getMessage())));
            if (giveUp) {
                log.error("대외기관 전송 재시도 한도 초과, 담당자 확인 필요: 대기열={}, 기관={}, 업무키={}, 사유={}", id, agency, row[3], e.getMessage());
            } else {
                log.warn("대외기관 전송 실패, 다시 시도 예정: 대기열={}, 시도={}, 사유={}", id, attempts, e.getMessage());
            }
        }
        return true;
    }

    /** n번째 실패 뒤 기다릴 시간: 30초 × 2^(n−1), 최대 1시간 */
    static Duration backoff(int attempts) {
        Duration wait = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts - 1, 20));
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    private static String truncate(String message) {
        String text = message == null ? "알 수 없는 오류" : message;
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
