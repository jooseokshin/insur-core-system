package com.insur.insurcoresystem.service.contract;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.common.security.Roles;
import com.insur.insurcoresystem.domain.contract.ContractStatus;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * 계약 업무의 역할별/데이터 단위 권한 정책. 변경할 상태에 따라 수행할 수 있는 역할이 다르고, 조회 범위도 역할에 따라 달라진다.
 * <ul>
 *     <li>유효(20), 청약취소(90): 심사 담당(UNDERWRITER)</li>
 *     <li>해지(30): 계약 관리 담당(OPERATOR)</li>
 *     <li>ADMIN 은 모든 상태로 변경할 수 있다.</li>
 *     <li>조회: AGENT 만 가진 사용자는 본인이 접수한 계약만, 그 외 역할은 전체</li>
 * </ul>
 * 상태 변경 API 자체에 접근할 수 있는 역할은 SecurityConfig 의 URL 규칙이 먼저 걸러내고, 이 정책은 그 안에서 상태별로 한 번 더 확인한다.
 */
public final class ContractAccessPolicy {

    private ContractAccessPolicy() {
    }

    /**
     * 요청한 상태로 변경할 권한이 있는지 확인한다. 정의되지 않은 상태코드는 여기서 판단하지 않고(권한 확인 생략),
     * 서비스의 상태코드 검증(E-CTR-013)이 거절하도록 넘긴다.
     *
     * @param authentication 인증된 사용자 (JWT 의 roles 클레임이 ROLE_ 권한으로 변환된 상태)
     * @param targetCode     변경하려는 계약상태코드
     * @throws BusinessException 권한이 없는 경우(E-AUTH-002, HTTP 403)
     */
    public static void checkStatusChange(Authentication authentication, String targetCode) {
        Optional<ContractStatus> target = ContractStatus.findByCode(targetCode);
        if (target.isEmpty()) {
            return;
        }
        Set<String> allowed = allowedRoles(target.get());
        if (roles(authentication).stream().noneMatch(allowed::contains)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "E-AUTH-002",
                    "계약상태를 " + target.get().getCode() + "(으)로 변경할 권한이 없습니다.");
        }
    }

    /**
     * 계약 조회를 본인이 접수한 계약으로 제한해야 하는 사용자인지 판단한다.
     * 청약 접수 담당(AGENT)만 가진 사용자는 본인이 접수한 계약만 볼 수 있고, 심사/계약 관리/ADMIN 역할이 하나라도 있으면 전체를 볼 수 있다.
     *
     * @param authentication 인증된 사용자
     * @return 본인 접수 계약으로 제한해야 하면 true
     */
    public static boolean ownContractsOnly(Authentication authentication) {
        Set<String> roles = roles(authentication);
        return roles.contains(Roles.AGENT)
                && !roles.contains(Roles.UNDERWRITER) && !roles.contains(Roles.OPERATOR) && !roles.contains(Roles.ADMIN);
    }

    /** 인증 객체의 권한에서 ROLE_ 접두사를 뗀 역할 이름 목록 */
    private static Set<String> roles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.replaceFirst("^ROLE_", ""))
                .collect(Collectors.toSet());
    }

    /** 해당 상태로 변경할 수 있는 역할 */
    private static Set<String> allowedRoles(ContractStatus target) {
        return switch (target) {
            case IN_FORCE, CANCELED -> Set.of(Roles.UNDERWRITER, Roles.ADMIN);
            case TERMINATED -> Set.of(Roles.OPERATOR, Roles.ADMIN);
            case APPLIED -> Set.of(Roles.ADMIN);   // 청약 상태로는 되돌릴 수 없는 전이이므로 어차피 서비스에서 거절된다
        };
    }
}
