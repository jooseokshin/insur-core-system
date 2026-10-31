package com.insur.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 시도 이력. 추가 전용이며 없는 사번으로 시도한 것도 남긴다.
 */
@Entity
@Table(name = "TB_LOGIN_HIST")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LoginHistory {

    public static final String SUCCESS = "SUCCESS";
    public static final String BAD_CREDENTIALS = "BAD_CREDENTIALS";
    public static final String LOCKED = "LOCKED";
    public static final String DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "HIST_ID")
    private Long histId;

    /** 입력한 사번 (64자까지) */
    @Column(name = "EMP_ID", length = 64, nullable = false)
    private String empId;

    /** 결과 (SUCCESS, BAD_CREDENTIALS, LOCKED, DISABLED) */
    @Column(name = "RESULT_CD", length = 20, nullable = false)
    private String resultCd;

    /** 접속 IP */
    @Column(name = "CLIENT_IP", length = 45)
    private String clientIp;

    /** 시도 일시 */
    @Column(name = "LOGIN_DT", nullable = false)
    private LocalDateTime loginDt;
}
