package com.insur.insurcoresystem;

import com.insur.insurcoresystem.common.config.BusinessClockConfig;
import java.util.TimeZone;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.insur.insurcoresystem.mapper")
public class InsurCoreSystemApplication {

    /**
     * 보험 코어 시스템 애플리케이션 진입점. 스프링 부트를 기동한다.
     * 기동 전에 JVM 기본 시간대를 업무 시간대(Asia/Seoul)로 맞춘다. MyBatis 는 LocalDateTime 을 시간대 변환 없이 TIMESTAMP 로 넘기지만,
     * JDBC 드라이버가 세션 시간대를 JVM 기본 시간대로 잡고 now() 같은 DB 함수와 java.util.Date 변환도 그 영향을 받으므로 DB(Asia/Seoul)와 맞춘다.
     *
     * @param args 실행 인자
     */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(BusinessClockConfig.BIZ_ZONE));
        SpringApplication.run(InsurCoreSystemApplication.class, args);
    }

}
