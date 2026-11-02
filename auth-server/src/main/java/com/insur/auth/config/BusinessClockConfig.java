package com.insur.auth.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 업무 기준 시각. 로그인 일시, 토큰 발급·만료 시각을 업무 시간대(Asia/Seoul)로 계산한다.
 */
@Configuration
public class BusinessClockConfig {

    /** 업무 시간대 */
    public static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Seoul");

    /** 업무 시간대로 현재 시각을 돌려주는 Clock. 시각이 필요한 곳은 now() 대신 이 Clock 을 주입받아 쓴다. */
    @Bean
    Clock businessClock() {
        return Clock.system(BIZ_ZONE);
    }
}
