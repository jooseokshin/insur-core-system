package com.insur.insurcoresystem.ext;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 대외기관 전문의 추적번호 = 기관코드(3) + 거래일(8) + 일련번호(9), 20자리. 요청과 응답을 짝짓고, 기관과 장애를 따질 때 서로 가리키는 번호다.
 * 일련번호는 DB 시퀀스(SQ_EXT_TRACE)로 받아서 인스턴스가 여럿이어도 겹치지 않는다.
 */
@Component
public class TraceNumbers {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String orgCode;

    public TraceNumbers(JdbcTemplate jdbc, Clock clock, @Value("${app.ext.kcis.org-code:K99}") String orgCode) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.orgCode = orgCode;
    }

    /** 새 추적번호 */
    public String next() {
        Long seq = jdbc.queryForObject("SELECT nextval('SQ_EXT_TRACE')", Long.class);
        return orgCode + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE) + String.format("%09d", seq);
    }
}
