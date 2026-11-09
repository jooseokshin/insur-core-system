package com.insur.insurcoresystem.common.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 호출 제한 구성. 저장소는 다른 {@link RateLimitStore} 빈이 없을 때만 메모리 구현을 쓴다. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    @ConditionalOnMissingBean
    RateLimitStore rateLimitStore() {
        return new InMemoryRateLimitStore();
    }

    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties, RateLimitStore store) {
        return new RateLimiter(properties, store);
    }
}
