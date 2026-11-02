package com.insur.auth;

import com.insur.auth.config.BusinessClockConfig;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuthServerApplication {

    /**
     * 인증 서버 진입점. 코어 백엔드와 같은 이유로 기동 전에 JVM 기본 시간대를 업무 시간대(Asia/Seoul)로 맞춘다.
     * (Hibernate 가 LocalDateTime 을 JVM 시간대로 해석한 뒤 jdbc.time_zone 으로 변환하므로 둘이 같아야 저장 시각이 어긋나지 않는다)
     *
     * @param args 실행 인자
     */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(BusinessClockConfig.BIZ_ZONE));
        SpringApplication.run(AuthServerApplication.class, args);
    }
}
