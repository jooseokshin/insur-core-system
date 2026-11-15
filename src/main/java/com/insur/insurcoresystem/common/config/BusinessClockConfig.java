package com.insur.insurcoresystem.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 업무 기준 시각. 저장하는 일시와 채번 연도를 JVM 기본 시간대가 아니라 업무 시간대(Asia/Seoul)로 계산한다.
 저장은 MyBatis 가 LocalDateTime 을 시간대 변환 없이 TIMESTAMP 로 넘기고 DB 도 Asia/Seoul 이므로,
 * 애플리케이션이 UTC 컨테이너에서 실행되어도 저장되는 시각이 어긋나지 않는다.
 */
@Configuration
public class BusinessClockConfig {

    /** 업무 시간대 */
    public static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Seoul");

    /** 업무 시간대로 현재 시각을 돌려주는 Clock. 시각이 필요한 곳은 LocalDateTime.now() 대신 이 Clock 을 주입받아 쓴다. */
    @Bean
    Clock businessClock() {
        return Clock.system(BIZ_ZONE);
    }
}
