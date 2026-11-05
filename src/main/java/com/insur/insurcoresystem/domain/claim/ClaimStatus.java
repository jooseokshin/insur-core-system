package com.insur.insurcoresystem.domain.claim;

import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 보상 상태 (TB_CLM.CLM_STAT_CD) 와 허용되는 전이.
 * <pre>
 * 접수(10) ─→ 손해사정 완료(20) ─→ 지급 완료(40)
 *   │  └───────────┴──────────→ 면책(80)
 *   └──→ 접수 취소(90)
 * </pre>
 * 손해사정 완료 상태에서는 다시 사정할 수 있다(20 → 20). 지급 완료·면책·접수 취소는 종료 상태다.
 */
@Getter
@RequiredArgsConstructor
public enum ClaimStatus {

    /** 사고 접수 */
    REPORTED("10"),
    /** 손해사정 완료 (지급 대기) */
    ASSESSED("20"),
    /** 보험금 지급 완료 */
    PAID("40"),
    /** 면책 (부지급 종결) */
    DENIED("80"),
    /** 접수 취소 */
    WITHDRAWN("90");

    private final String code;

    public static Optional<ClaimStatus> findByCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }

    /** 아직 종결되지 않은(진행 중인) 상태인지 */
    public boolean isOpen() {
        return this == REPORTED || this == ASSESSED;
    }

    /** 현재 상태에서 다음 상태로 갈 수 있는지 */
    public boolean canChangeTo(ClaimStatus next) {
        return switch (this) {
            case REPORTED -> next == ASSESSED || next == DENIED || next == WITHDRAWN;
            case ASSESSED -> next == ASSESSED || next == PAID || next == DENIED;
            case PAID, DENIED, WITHDRAWN -> false;
        };
    }
}
