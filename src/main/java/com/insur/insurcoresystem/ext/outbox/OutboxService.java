package com.insur.insurcoresystem.ext.outbox;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.ext.TraceNumbers;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 대외기관 전송 대기열 (트랜잭셔널 아웃박스, TB_EXT_OUTBOX).
 * <p>
 * 계약 승인·해지, 보험금 지급처럼 기관에 알려야 하는 업무는 그 거래 안에서 전문을 대기열에 쌓기만 하고, 보내는 것은 {@link OutboxSender} 가 따로 한다.
 * 이렇게 하면 (1) 업무가 롤백되면 전문도 같이 사라져 "보냈는데 업무는 없는" 상태가 생기지 않고, (2) 기관이 멈춰도 업무는 계속되며,
 * (3) 업무가 커밋되면 전문은 반드시 남아서 언젠가 보내진다. 업무 키(BIZ_KEY)가 유니크라서 같은 사건을 두 번 쌓지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    public static final String STAT_PENDING = "10";
    public static final String STAT_SENT = "20";
    public static final String STAT_FAILED = "90";

    private final NamedParameterJdbcTemplate jdbc;
    private final TraceNumbers traceNumbers;
    private final Clock clock;

    /**
     * 전문을 대기열에 쌓는다. 호출한 업무 트랜잭션에 참여해야 하므로 트랜잭션 안에서 불러야 한다.
     *
     * @param payload 추적번호를 받아 전문을 만드는 함수
     * @return 새로 쌓았으면 true, 같은 업무 키가 이미 있으면 false
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(String agency, String txType, String bizKey, Function<String, String> payload) {
        int inserted = jdbc.update("""
                INSERT INTO TB_EXT_OUTBOX (AGENCY_CD, TX_TYPE, BIZ_KEY, PAYLOAD, STAT_CD, ATTEMPTS, NEXT_TRY_DT, CREATED_DT)
                VALUES (:agency, :txType, :bizKey, :payload, '10', 0, :now, :now)
                ON CONFLICT (AGENCY_CD, TX_TYPE, BIZ_KEY) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("agency", agency).addValue("txType", txType).addValue("bizKey", bizKey)
                .addValue("payload", payload.apply(traceNumbers.next()))
                .addValue("now", LocalDateTime.now(clock)));
        if (inserted > 0) {
            log.info("대외기관 전송 대기: 기관={}, 거래={}, 업무키={}", agency, txType, bizKey);
        }
        return inserted > 0;
    }

    /** 대기열 한 건 */
    public record Entry(long outboxId, String agencyCd, String txType, String bizKey, String statCd, int attempts,
                        LocalDateTime nextTryDt, String lastError, LocalDateTime createdDt, LocalDateTime sentDt) {
    }

    /**
     * 대기열을 최근순으로 조회한다 (운영 화면용).
     *
     * @param statCd 상태 (10, 20, 90). null 이면 전부
     * @throws BusinessException 없는 상태코드(E-EXT-001)
     */
    @Transactional(readOnly = true)
    public List<Entry> list(String statCd, String bizKeyPrefix, int limit) {
        if (StringUtils.hasText(statCd) && !List.of(STAT_PENDING, STAT_SENT, STAT_FAILED).contains(statCd)) {
            throw new BusinessException("E-EXT-001", "대기열 상태는 10, 20, 90 중 하나여야 합니다: " + statCd);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT OUTBOX_ID, AGENCY_CD, TX_TYPE, BIZ_KEY, STAT_CD, ATTEMPTS, NEXT_TRY_DT, LAST_ERROR, CREATED_DT, SENT_DT
                  FROM TB_EXT_OUTBOX WHERE 1 = 1""");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", Math.max(1, Math.min(limit, 200)));
        if (StringUtils.hasText(statCd)) {
            sql.append(" AND STAT_CD = :stat");
            params.addValue("stat", statCd);
        }
        if (StringUtils.hasText(bizKeyPrefix)) {
            sql.append(" AND BIZ_KEY LIKE :prefix");
            params.addValue("prefix", bizKeyPrefix.replace("%", "").replace("_", "\\_") + "%");
        }
        sql.append(" ORDER BY OUTBOX_ID DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, (rs, i) -> new Entry(rs.getLong("OUTBOX_ID"), rs.getString("AGENCY_CD"),
                rs.getString("TX_TYPE"), rs.getString("BIZ_KEY"), rs.getString("STAT_CD"), rs.getInt("ATTEMPTS"),
                rs.getObject("NEXT_TRY_DT", LocalDateTime.class), rs.getString("LAST_ERROR"),
                rs.getObject("CREATED_DT", LocalDateTime.class), rs.getObject("SENT_DT", LocalDateTime.class)));
    }

    /**
     * 재시도 한도를 넘어 멈춘(90) 건을 담당자가 원인을 확인한 뒤 다시 대기(10)로 돌린다. 전문과 추적번호는 그대로라 기관이 중복을 가려낼 수 있다.
     *
     * @throws BusinessException 없는 건(E-EXT-002, 404), 멈춘 건이 아님(E-EXT-003, 409)
     */
    @Transactional
    public Entry retry(long outboxId, String empId) {
        String stat = jdbc.query("SELECT STAT_CD FROM TB_EXT_OUTBOX WHERE OUTBOX_ID = :id FOR UPDATE",
                        new MapSqlParameterSource("id", outboxId), (rs, i) -> rs.getString(1)).stream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "E-EXT-002", "없는 대기열 번호입니다: " + outboxId));
        if (!STAT_FAILED.equals(stat)) {
            throw new BusinessException(HttpStatus.CONFLICT, "E-EXT-003", "재시도 한도를 넘어 멈춘(90) 건만 다시 보낼 수 있습니다. (상태 " + stat + ")");
        }
        jdbc.update("UPDATE TB_EXT_OUTBOX SET STAT_CD = '10', ATTEMPTS = 0, NEXT_TRY_DT = :now WHERE OUTBOX_ID = :id",
                new MapSqlParameterSource("id", outboxId).addValue("now", LocalDateTime.now(clock)));
        log.info("대외기관 전송 수동 재시도: 대기열={}, 처리자={}", outboxId, empId);
        return jdbc.query("""
                SELECT OUTBOX_ID, AGENCY_CD, TX_TYPE, BIZ_KEY, STAT_CD, ATTEMPTS, NEXT_TRY_DT, LAST_ERROR, CREATED_DT, SENT_DT
                  FROM TB_EXT_OUTBOX WHERE OUTBOX_ID = :id""", new MapSqlParameterSource("id", outboxId),
                (rs, i) -> new Entry(rs.getLong("OUTBOX_ID"), rs.getString("AGENCY_CD"), rs.getString("TX_TYPE"),
                        rs.getString("BIZ_KEY"), rs.getString("STAT_CD"), rs.getInt("ATTEMPTS"),
                        rs.getObject("NEXT_TRY_DT", LocalDateTime.class), rs.getString("LAST_ERROR"),
                        rs.getObject("CREATED_DT", LocalDateTime.class), rs.getObject("SENT_DT", LocalDateTime.class))).get(0);
    }
}
