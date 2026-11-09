package com.insur.insurcoresystem.common.ratelimit;

import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 요청 경로를 규칙에 맞춰 버킷을 고르고 사원별로 하나씩 꺼낸다. 버킷 키는 "규칙 이름:사번" 이다.
 */
public class RateLimiter {

    static final String DEFAULT_GROUP = "default";

    private final RateLimitProperties properties;
    private final RateLimitStore store;
    private final List<CompiledRule> rules;

    public RateLimiter(RateLimitProperties properties, RateLimitStore store) {
        this.properties = properties;
        this.store = store;
        this.rules = properties.rules().stream()
                .map(rule -> new CompiledRule(rule, rule.limit(),
                        rule.paths().stream().map(PathPatternParser.defaultInstance::parse).toList()))
                .toList();
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** 경로에 맞는 규칙의 버킷에서 사원의 호출 하나를 꺼낸다. (역할과 무관한 규칙만 본다) */
    public RateLimitStore.Decision tryConsume(String empId, String path) {
        return tryConsume(empId, path, java.util.Set.of());
    }

    /**
     * 경로와 역할에 맞는 첫 규칙의 버킷에서 호출 하나를 꺼낸다.
     *
     * @param roles 호출한 주체의 역할 (ROLE_ 접두사 없음). 역할을 지정한 규칙은 이 중 하나가 있어야 맞는다
     */
    public RateLimitStore.Decision tryConsume(String empId, String path, java.util.Set<String> roles) {
        PathContainer container = PathContainer.parsePath(path);
        for (CompiledRule rule : rules) {
            if (rule.rule().matchesRoles(roles) && rule.patterns().stream().anyMatch(p -> p.matches(container))) {
                return store.tryConsume(rule.rule().name() + ":" + empId, rule.limit());
            }
        }
        return store.tryConsume(DEFAULT_GROUP + ":" + empId, properties.defaults());
    }

    private record CompiledRule(RateLimitProperties.Rule rule, RateLimitProperties.Limit limit, List<PathPattern> patterns) {
    }
}
