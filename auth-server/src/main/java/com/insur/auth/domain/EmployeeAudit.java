package com.insur.auth.domain;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사원 관리 감사 이력. 누가 언제 어떤 계정을 어떻게 바꿨는지 남긴다. 추가 전용.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class EmployeeAudit {

    private Long auditId;

    /** 대상 사번 */
    private String empId;

    /** 작업 (CREATE, UPDATE, RESET_PASSWORD, UNLOCK, CHANGE_PASSWORD) */
    private String action;

    /** 변경 내용 요약 (비밀번호는 남기지 않는다) */
    private String detail;

    /** 처리한 사번 */
    private String actEmpId;

    /** 거래 UUID */
    private String globalTxId;

    /** 처리 일시 */
    private LocalDateTime actDt;
}
