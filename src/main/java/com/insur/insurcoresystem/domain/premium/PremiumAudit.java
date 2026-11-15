package com.insur.insurcoresystem.domain.premium;

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
    private Long auditId;

    /** 대상 종류 (RATE, FACTOR, POLICY) */
    private String targetType;

    /** 대상 식별 (예: 01/CA001, 01/DRIVER_AGE/18-25, 01/DISCOUNT_GRADE/1) */
    private String targetKey;

    /** 동작 (CREATE, REVISE) */
    private String action;

    /** 개정 전 값 (요율 또는 계수). 처음 만든 경우 null */
    private String beforeVal;

    /** 개정 후 값 */
    private String afterVal;

    /** 새 값의 적용 시작일 */
    private LocalDate effFrom;

    /** 처리 사번 */
    private String empId;

    /** 거래 UUID (재전송 판별에도 쓴다) */
    private String globalTxId;

    /** 처리 일시 */
    private LocalDateTime chgDt;
}
