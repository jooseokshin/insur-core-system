package com.insur.auth.service;

import com.insur.auth.domain.Employee;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * JWT 발급기. 코어 백엔드가 읽는 클레임 규약을 따른다.
 * <ul>
 *     <li>sub: 사번 (코어에서 처리 사번이 된다)</li>
 *     <li>roles: 역할 문자열 배열 (코어에서 ROLE_ 권한이 된다)</li>
 *     <li>name: 이름 (화면 표시용)</li>
 *     <li>pwd_chg_req: 비밀번호 변경이 필요한 사원에게만 true. 이때는 roles 를 비워 업무 API 를 쓸 수 없게 한다</li>
 * </ul>
 */
@Component
public class TokenIssuer {

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration ttl;
    private final String issuer;

    public TokenIssuer(JwtEncoder encoder, Clock clock, @Value("${app.jwt.ttl-minutes}") long ttlMinutes,
                       @Value("${app.jwt.issuer}") String issuer) {
        this.encoder = encoder;
        this.clock = clock;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.issuer = issuer;
    }

    /** 발급한 토큰과 만료 시각 */
    public record IssuedToken(String value, Instant expiresAt, long expiresInSeconds) {
    }

    /**
     * 사원에게 토큰을 발급한다. 비밀번호 변경이 필요한 사원은 역할 없는 제한 토큰을 받는다.
     */
    public IssuedToken issue(Employee employee) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        boolean restricted = employee.isPasswordChangeRequired();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(employee.getEmpId())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("name", employee.getEmpNm())
                .claim("roles", restricted ? List.of() : List.copyOf(employee.sortedRoles()));
        if (restricted) {
            claims.claim("pwd_chg_req", true);
        }
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
        return new IssuedToken(token, expiresAt, ttl.toSeconds());
    }
}
