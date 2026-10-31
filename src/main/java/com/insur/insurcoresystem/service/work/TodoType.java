package com.insur.insurcoresystem.service.work;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.security.Roles;
import java.util.Arrays;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 할 일함의 업무 유형. 유형마다 처리할 수 있는 역할이 정해져 있고, 그 역할을 가진 사용자에게만 목록과 건수를 보여 준다.
 * <ul>
 *     <li>PENDING_APPROVAL: 보험료를 완납해 승인(유효)할 수 있는 청약. 심사 담당(UNDERWRITER)이 처리한다</li>
 *     <li>UNPAID: 아직 완납하지 않은 청약. 수납은 계약 관리 담당(OPERATOR)이 하고, 설계사(AGENT)는 본인 접수 건을 챙긴다</li>
 *     <li>STALE_APPLICATION: 접수 후 {@value WorkService#STALE_DAYS}일 이상 청약 상태로 남은 건. 심사 담당과 설계사가 챙긴다</li>
 * </ul>
 * ADMIN 은 모든 유형을 본다.
 */
@Getter
@RequiredArgsConstructor
public enum TodoType {

    /** 승인 대기 (완납한 청약) */
    PENDING_APPROVAL("승인 대기", Set.of(Roles.UNDERWRITER, Roles.ADMIN)),
    /** 미납 청약 */
    UNPAID("미납 청약", Set.of(Roles.AGENT, Roles.OPERATOR, Roles.ADMIN)),
    /** 장기 미처리 청약 */
    STALE_APPLICATION("장기 미처리", Set.of(Roles.AGENT, Roles.UNDERWRITER, Roles.ADMIN));

    private final String label;
    private final Set<String> allowedRoles;

    /** 사용자 역할 중 하나라도 이 유형을 처리할 수 있으면 true */
    public boolean isVisibleTo(Set<String> roles) {
        return roles.stream().anyMatch(allowedRoles::contains);
    }

    /**
     * 이름으로 유형을 찾는다.
     *
     * @throws BusinessException 비었거나 정의되지 않은 유형인 경우(E-WRK-001)
     */
    public static TodoType from(String name) {
        return Arrays.stream(values())
                .filter(type -> type.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new BusinessException("E-WRK-001",
                        "할 일 유형은 PENDING_APPROVAL, UNPAID, STALE_APPLICATION 중 하나여야 합니다: " + name));
    }
}
