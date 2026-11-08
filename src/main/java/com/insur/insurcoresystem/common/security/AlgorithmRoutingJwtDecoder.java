package com.insur.insurcoresystem.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 토큰 헤더의 alg 로 검증기를 고른다.
 * <ul>
 *     <li>RS256: 인증 서버(OAuth 인가 서버)가 발급한 토큰. 인증 서버의 공개키(JWKS)로 서명을, 설정한 issuer 로 iss 를 확인한다.</li>
 *     <li>HS256: 공유 비밀키(JWT_SECRET)를 설정했을 때만 받는다. 개발용 토큰(scripts/dev-token.sh)과 테스트용이며, 운영에서는 설정하지 않는다.
 *         비밀키를 가진 쪽은 누구든 토큰을 만들 수 있기 때문이다.</li>
 * </ul>
 * 그 밖의 알고리즘(none 포함)과 설정하지 않은 쪽의 토큰은 모두 거절한다.
 */
class AlgorithmRoutingJwtDecoder implements JwtDecoder {

    private final JwtDecoder rsaDecoder;
    private final JwtDecoder hmacDecoder;

    /**
     * @param rsaDecoder  RS256 검증기 (없으면 null)
     * @param hmacDecoder HS256 검증기 (없으면 null)
     */
    AlgorithmRoutingJwtDecoder(JwtDecoder rsaDecoder, JwtDecoder hmacDecoder) {
        this.rsaDecoder = rsaDecoder;
        this.hmacDecoder = hmacDecoder;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        JWSAlgorithm alg;
        try {
            JWT parsed = JWTParser.parse(token);
            if (!(parsed instanceof SignedJWT signed)) {
                throw new BadJwtException("서명되지 않은 토큰은 받지 않습니다.");
            }
            alg = signed.getHeader().getAlgorithm();
        } catch (ParseException e) {
            throw new BadJwtException("토큰 형식이 올바르지 않습니다.", e);
        }
        if (JWSAlgorithm.RS256.equals(alg) && rsaDecoder != null) {
            return rsaDecoder.decode(token);
        }
        if (JWSAlgorithm.HS256.equals(alg) && hmacDecoder != null) {
            return hmacDecoder.decode(token);
        }
        throw new BadJwtException("받지 않는 서명 알고리즘입니다: " + alg);
    }
}
