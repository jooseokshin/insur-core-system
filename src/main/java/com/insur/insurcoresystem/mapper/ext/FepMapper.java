package com.insur.insurcoresystem.mapper.ext;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import org.apache.ibatis.annotations.Param;

/**
 * FEP 기관(TB_FEP_INST), 전문 로그(TB_FEP_MSG_LOG), 추적번호 시퀀스(SQ_EXT_TRACE) 매퍼.
 */
public interface FepMapper {

    /** 추적번호 일련번호 (인스턴스가 여럿이어도 겹치지 않는다) */
    long nextTraceSeq();

    /** 개시 상태. 없는 기관이면 null */
    Boolean findSignOn(String instCd);

    boolean existsInst(String instCd);

    /** 기관별 상태와 [from, to) 사이 전문 처리량 (기관코드 순) */
    List<InstRow> findInstitutions(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    int updateSignOn(@Param("instCd") String instCd, @Param("on") boolean on, @Param("now") LocalDateTime now,
                     @Param("empId") String empId);

    int updateEcho(@Param("instCd") String instCd, @Param("ok") boolean ok, @Param("now") LocalDateTime now);

    void insertMessageLog(MessageLogRow row);

    /** 조건에 맞는 전문 로그 (최근 순, limit 건) */
    List<MessageLogRow> findMessageLogs(@Param("c") MessageLogCondition condition);

    /** 기관 한 줄과 처리 건수 (오류·응답 없음·차단·평균 응답 시간) */
    record InstRow(String instCd, String instNm, boolean signOnYn, LocalDateTime lastEchoDt, Boolean lastEchoOk,
                   LocalDateTime updDt, String updEmpId, long cnt, long err, long tmo, long blk, Long avgMs) {
    }

    /** 전문 로그 한 줄. 본문은 개인정보라 길이와 SHA-256 만 둔다 */
    @Builder
    record MessageLogRow(Long logId, String instCd, String txType, String traceNo, LocalDateTime reqDt, int elapsedMs,
                         String resultCd, String responseCd, int reqBytes, Integer resBytes, String reqHash, String errorMsg) {
    }

    /** 전문 로그 조회 조건. null 인 항목은 조건에서 빠진다. reqFrom 이상 reqTo 미만 */
    @Builder
    record MessageLogCondition(String instCd, String traceNo, String resultCd, LocalDateTime reqFrom, LocalDateTime reqTo,
                               int limit) {
    }
}
