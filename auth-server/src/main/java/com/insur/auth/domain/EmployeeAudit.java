package com.insur.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사원 관리 감사 이력. 누가 언제 어떤 계정을 어떻게 바꿨는지 남긴다. 추가 전용.
 */
@Entity
@Table(name = "TB_EMP_AUDIT")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class EmployeeAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AUDIT_ID")
    private Long auditId;

    /** 대상 사번 */
    @Column(name = "EMP_ID", length = 20, nullable = false)
    private String empId;

    /** 작업 (CREATE, UPDATE, RESET_PASSWORD, UNLOCK, CHANGE_PASSWORD) */
    @Column(name = "ACTION", length = 20, nullable = false)
    private String action;

    /** 변경 내용 요약 (비밀번호는 남기지 않는다) */
    @Column(name = "DETAIL", length = 500)
    private String detail;

    /** 처리한 사번 */
    @Column(name = "ACT_EMP_ID", length = 20)
    private String actEmpId;

    /** 거래 UUID */
    @Column(name = "GLOBAL_TX_ID", length = 64)
    private String globalTxId;

    /** 처리 일시 */
    @Column(name = "ACT_DT", nullable = false)
    private LocalDateTime actDt;
}
