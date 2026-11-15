package com.insur.insurcoresystem.mapper.ext;

import com.insur.insurcoresystem.ext.outbox.OutboxService.Entry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

/**
 * 대외기관 전송 대기열(TB_EXT_OUTBOX, 트랜잭셔널 아웃박스) 매퍼.
 */
public interface OutboxMapper {

    /** 대기열에 쌓는다. 같은 (기관, 거래, 업무 키)가 있으면 아무것도 하지 않고 0 을 돌려준다 */
    int insertIgnore(@Param("agency") String agency, @Param("txType") String txType, @Param("bizKey") String bizKey,
                     @Param("payload") String payload, @Param("now") LocalDateTime now);

    /** 운영 화면 조회 (최근 순). statCd, bizKeyLike 가 null 이면 조건에서 빠진다 */
    List<Entry> findEntries(@Param("statCd") String statCd, @Param("bizKeyLike") String bizKeyLike, @Param("limit") int limit);

    Optional<Entry> findEntry(long outboxId);

    /** 상태를 잠그고 읽는다 */
    Optional<String> findStatForUpdate(long outboxId);

    /** 멈춘 건을 다시 대기로 돌린다 */
    int resetForRetry(@Param("outboxId") long outboxId, @Param("now") LocalDateTime now);

    /**
     * 보낼 때가 된 건 하나를 잠그고 가져온다 (FOR UPDATE SKIP LOCKED: 다른 인스턴스가 잡은 건은 건너뛴다).
     */
    Optional<Due> lockNextDue(LocalDateTime now);

    int markSent(@Param("outboxId") long outboxId, @Param("attempts") int attempts, @Param("now") LocalDateTime now);

    int markFailure(@Param("outboxId") long outboxId, @Param("statCd") String statCd, @Param("attempts") int attempts,
                    @Param("nextTryDt") LocalDateTime nextTryDt, @Param("lastError") String lastError);

    /** 보낼 건 */
    record Due(long outboxId, String agencyCd, String txType, String bizKey, String payload, int attempts) {
    }
}
