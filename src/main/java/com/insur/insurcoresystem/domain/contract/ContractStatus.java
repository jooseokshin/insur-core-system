package com.insur.insurcoresystem.domain.contract;

import com.insur.insurcoresystem.common.exception.BusinessException;
import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 계약상태 (TB_CTR_LGD.CTR_STAT_CD) 와 허용되는 상태 전이.
 * <pre>
 * 청약(10) ─→ 유효(20) ─→ 해지(30)
 *    └──────→ 청약취소(90)
 * </pre>
 * 해지와 청약취소는 종료 상태이므로 더 이상 변경할 수 없다.
 */
@Getter
@RequiredArgsConstructor
public enum ContractStatus {

    /** 청약 접수 */
    APPLIED("10"),
    /** 유효 (청약 승인으로 성립한 계약) */
    IN_FORCE("20"),
    /** 해지 */
    TERMINATED("30"),
    /** 청약취소 (성립 전 취소/거절) */
    CANCELED("90");

    private final String code;

    /**
     * 상태코드로 계약상태를 조회한다.
     *
     * @param code 계약상태코드 (10, 20, 30, 90)
     * @return 코드에 대응하는 계약상태
     * @throws BusinessException 정의되지 않은 상태코드인 경우(E-CTR-013)
     */
    public static ContractStatus fromCode(String code) {
        return findByCode(code)
                .orElseThrow(() -> new BusinessException("E-CTR-013", "지원하지 않는 계약상태코드입니다: " + code));
    }

    /**
     * 상태코드로 계약상태를 찾는다. 정의되지 않은 코드면 예외 대신 빈 Optional 을 돌려준다.
     *
     * @param code 계약상태코드
     * @return 코드에 대응하는 계약상태. 없으면 빈 Optional
     */
    public static Optional<ContractStatus> findByCode(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code.equals(code))
                .findFirst();
    }

    /**
     * 현재 상태에서 다음 상태로 변경할 수 있는지 판단한다. 같은 상태로의 변경은 전이로 보지 않는다.
     *
     * @param next 변경하려는 상태
     * @return 허용되는 전이이면 true
     */
    public boolean canChangeTo(ContractStatus next) {
        return switch (this) {
            case APPLIED -> next == IN_FORCE || next == CANCELED;
            case IN_FORCE -> next == TERMINATED;
            case TERMINATED, CANCELED -> false;
        };
    }
}
