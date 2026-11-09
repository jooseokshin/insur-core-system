package com.insur.insurcoresystem.common.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 코어(public) 스키마의 모든 테이블과 컬럼에 설명(COMMENT)이 있는지 확인한다. DB 도구에서 뜻을 바로 볼 수 있게 하려는 규칙이다.
 * 테이블이나 컬럼을 추가하는 마이그레이션은 같은 파일에 COMMENT ON 도 써야 한다. (Flyway 이력 테이블은 제외)
 */
@SpringBootTest
class SchemaCommentTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 모든_테이블에_설명이_있다() {
        assertThat(jdbc.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = current_schema() AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                   AND obj_description(c.oid, 'pg_class') IS NULL
                 ORDER BY 1
                """, String.class)).as("설명이 없는 테이블").isEmpty();
    }

    @Test
    void 모든_컬럼에_설명이_있다() {
        assertThat(jdbc.queryForList("""
                SELECT c.relname || '.' || a.attname FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = current_schema() AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                   AND a.attnum > 0 AND NOT a.attisdropped AND col_description(c.oid, a.attnum) IS NULL
                 ORDER BY 1
                """, String.class)).as("설명이 없는 컬럼").isEmpty();
    }
}
