package com.insur.auth;

import com.insur.auth.config.BusinessClockConfig;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.insur.auth.mapper")
public class AuthServerApplication {

    /**
     * 인증 서버 진입점. 코어 백엔드와 같은 이유로 기동 전에 JVM 기본 시간대를 업무 시간대(Asia/Seoul)로 맞춘다.
     * (JDBC 드라이버가 세션 시간대를 JVM 기본 시간대로 잡으므로 DB 의 Asia/Seoul 과 맞춘다)
     *
     * @param args 실행 인자
     */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(BusinessClockConfig.BIZ_ZONE));
        SpringApplication.run(AuthServerApplication.class, args);
    }
}
