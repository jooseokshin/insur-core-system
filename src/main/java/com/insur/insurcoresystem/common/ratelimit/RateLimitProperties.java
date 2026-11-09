package com.insur.insurcoresystem.common.ratelimit;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 사원별 호출 제한 설정 (app.rate-limit.*). 토큰 버킷: capacity 만큼 한 번에 몰아 보낼 수 있고, 분당 perMinute 개씩 다시 찬다.
 *
 * @param enabled  끄면 제한하지 않는다 (테스트 기본값)
 * @param defaults 규칙에 걸리지 않는 /api/** 호출의 한도
 * @param rules    경로별 별도 한도. 위에서부터 처음 맞는 규칙 하나만 적용하고, 규칙마다 버킷이 따로 있다.
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Limit defaults,
        @DefaultValue List<Rule> rules) {

    /**
     * @param capacity  버킷 크기 (연속으로 보낼 수 있는 최대 호출 수)
     * @param perMinute 분당 다시 차는 수 (지속 가능한 호출 속도)
     */
    public record Limit(@DefaultValue("60") int capacity, @DefaultValue("300") int perMinute) {
        public Limit {
            if (capacity < 1 || perMinute < 1) {
                throw new IllegalArgumentException("호출 제한의 capacity 와 perMinute 는 1 이상이어야 합니다.");
            }
        }
    }

    /**
     * @param name  규칙 이름 (버킷 키와 로그에 쓴다)
     * @param paths 경로 패턴 (예: /api/stats/**)
     * @param roles 이 역할 중 하나를 가진 호출에만 적용 (예: CHANNEL). 비우면 역할과 무관
     */
    public record Rule(String name, List<String> paths, List<String> roles, int capacity, int perMinute) {

        /** 이 역할 집합(ROLE_ 접두사 없음)의 호출에 적용되는 규칙인지 */
        public boolean matchesRoles(java.util.Set<String> callerRoles) {
            return roles == null || roles.isEmpty() || roles.stream().anyMatch(callerRoles::contains);
        }

        public Limit limit() {
            return new Limit(capacity, perMinute);
        }
    }
}
