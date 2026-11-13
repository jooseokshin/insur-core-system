package com.insur.insurcoresystem.ext.fep;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전문 로그(TB_FEP_MSG_LOG)를 별도 트랜잭션으로 남긴다. 업무가 롤백돼도 기관에는 이미 전문이 갔으므로 로그는 남아야 한다.
 * 본문에는 계좌번호·이름 같은 개인정보가 있어 저장하지 않고 길이와 SHA-256 만 남긴다.
 */
@Component
@RequiredArgsConstructor
public class FepMessageLogger {

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String instCd, String txType, String traceNo, LocalDateTime reqDt, long elapsedMs, String result,
                    String responseCode, byte[] request, Integer resBytes, String error) {
        jdbc.update("""
                INSERT INTO TB_FEP_MSG_LOG (INST_CD, TX_TYPE, TRACE_NO, REQ_DT, ELAPSED_MS, RESULT_CD, RESPONSE_CD, REQ_BYTES, RES_BYTES, REQ_HASH, ERROR_MSG)
                VALUES (:inst, :txType, :traceNo, :reqDt, :elapsed, :result, :code, :reqBytes, :resBytes, :hash, :error)
                """, new MapSqlParameterSource()
                .addValue("inst", instCd).addValue("txType", txType).addValue("traceNo", traceNo).addValue("reqDt", reqDt)
                .addValue("elapsed", (int) Math.min(elapsedMs, Integer.MAX_VALUE)).addValue("result", result)
                .addValue("code", responseCode).addValue("reqBytes", request.length).addValue("resBytes", resBytes)
                .addValue("hash", sha256(request))
                .addValue("error", error == null ? null : error.length() > 200 ? error.substring(0, 200) : error));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
