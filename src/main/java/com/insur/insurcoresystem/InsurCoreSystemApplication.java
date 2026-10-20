package com.insur.insurcoresystem;

import com.insur.insurcoresystem.common.config.BusinessClockConfig;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InsurCoreSystemApplication {

    /**
     * 보험 코어 시스템 애플리케이션 진입점. 스프링 부트를 기동한다.
     * 기동 전에 JVM 기본 시간대를 업무 시간대(Asia/Seoul)로 맞춘다. Hibernate 는 LocalDateTime 을 JVM 기본 시간대로 해석한 뒤
     * hibernate.jdbc.time_zone(Asia/Seoul)으로 다시 변환해 저장하므로, 둘이 다르면(예: UTC 컨테이너) 저장 시각이 어긋난다.
     *
     * @param args 실행 인자
     */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(BusinessClockConfig.BIZ_ZONE));
        SpringApplication.run(InsurCoreSystemApplication.class, args);
    }

}
