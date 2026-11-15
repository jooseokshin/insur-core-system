package com.insur.insurcoresystem.support;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** PostgreSQL 오류 응답을 흉내 낸다 (단위 시험용). */
public final class PgErrors {

    private PgErrors() {
    }

    /** 유니크 제약 위반(23505). constraint 가 null 이면 제약명 없는 오류 */
    public static PSQLException uniqueViolation(String message, String constraint) {
        String fields = "SERROR\0C23505\0M" + message + "\0" + (constraint == null ? "" : "n" + constraint + "\0");
        return new PSQLException(new ServerErrorMessage(fields));
    }
}
