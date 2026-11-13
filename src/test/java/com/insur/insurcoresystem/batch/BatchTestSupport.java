package com.insur.insurcoresystem.batch;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 배치 시험 공용: 식별 파라미터 값으로 배치 실행 기록(BATCH_*)을 지운다. 같은 파라미터로 다시 시험할 수 있게 한다.
 */
final class BatchTestSupport {

    private BatchTestSupport() {
    }

    static void deleteBatchRuns(JdbcTemplate jdbc, String job, String param, String value) {
        List<Long> execs = jdbc.queryForList("""
                SELECT E.JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION E JOIN BATCH_JOB_INSTANCE I ON I.JOB_INSTANCE_ID = E.JOB_INSTANCE_ID
                 WHERE I.JOB_NAME = ? AND E.JOB_INSTANCE_ID IN (
                       SELECT X.JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION X JOIN BATCH_JOB_EXECUTION_PARAMS P ON P.JOB_EXECUTION_ID = X.JOB_EXECUTION_ID
                        WHERE P.PARAMETER_NAME = ? AND P.PARAMETER_VALUE = ?)
                """, Long.class, job, param, value);
        List<Long> instances = new ArrayList<>();
        for (Long id : execs) {
            instances.add(jdbc.queryForObject("SELECT JOB_INSTANCE_ID FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?", Long.class, id));
            jdbc.update("DELETE FROM BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID IN (SELECT STEP_EXECUTION_ID FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ?)", id);
            jdbc.update("DELETE FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ?", id);
            jdbc.update("DELETE FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID = ?", id);
            jdbc.update("DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID = ?", id);
            jdbc.update("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?", id);
        }
        instances.stream().distinct().forEach(i -> jdbc.update("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = ?", i));
    }
}
