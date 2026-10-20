package com.insur.insurcoresystem.domain.premium;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 요율/요율 계수 관리 감사 이력. 추가 전용이며 저장한 이력은 수정하거나 삭제하지 않는다.
 */
@Entity
@Table(name = "TB_PRM_AUDIT")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PremiumAudit {

    /** 대상 종류: 요율 */
    public static final String TARGET_RATE = "RATE";
    /** 대상 종류: 요율 계수 */
    public static final String TARGET_FACTOR = "FACTOR";
    /** 대상 종류: 필수 요율 계수 정책 */
    public static final String TARGET_POLICY = "POLICY";
    /** 동작: 처음 만듦 */
    public static final String ACTION_CREATE = "CREATE";
    /** 동작: 개정(기존 행을 닫고 새 행 추가) */
    public static final String ACTION_REVISE = "REVISE";

    /** 감사 일련번호 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AUDIT_ID")
    private Long auditId;

    /** 대상 종류 (RATE, FACTOR, POLICY) */
    @Column(name = "TARGET_TYPE", length = 10, nullable = false)
    private String targetType;

    /** 대상 식별 (예: 01/CA001, 01/DRIVER_AGE/18-25, 01/DISCOUNT_GRADE/1) */
    @Column(name = "TARGET_KEY", length = 100, nullable = false)
    private String targetKey;

    /** 동작 (CREATE, REVISE) */
    @Column(name = "ACTION", length = 10, nullable = false)
    private String action;

    /** 개정 전 값 (요율 또는 계수). 처음 만든 경우 null */
    @Column(name = "BEFORE_VAL", length = 50)
    private String beforeVal;

    /** 개정 후 값 */
    @Column(name = "AFTER_VAL", length = 50, nullable = false)
    private String afterVal;

    /** 새 값의 적용 시작일 */
    @Column(name = "EFF_FROM", nullable = false)
    private LocalDate effFrom;

    /** 처리 사번 */
    @Column(name = "EMP_ID", length = 64)
    private String empId;

    /** 거래 UUID (재전송 판별에도 쓴다) */
    @Column(name = "GLOBAL_TX_ID", length = 64, nullable = false)
    private String globalTxId;

    /** 처리 일시 */
    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime chgDt;
}
