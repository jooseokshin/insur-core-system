package com.insur.auth.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;

/**
 * 토큰 서명용 RSA 키를 읽는다. 키 ID(kid)는 공개키 지문(RFC 7638)이라 같은 키면 재기동해도 같다.
 * <p>
 * 공유 비밀키(HS256) 대신 개인키·공개키를 쓰는 이유: 토큰을 검증하는 서버(코어, 앞으로 붙을 MCP 서버 등)는 공개키(JWKS)만 받으므로,
 * 검증하는 쪽이 늘어나도 토큰을 만들 수 있는 곳은 이 서버 하나로 남는다.
 */
@Slf4j
final class SigningKeys {

    private SigningKeys() {
    }

    /**
     * @param configured PKCS#8 PEM 본문("-----BEGIN PRIVATE KEY-----" 로 시작) 또는 그 파일 경로. 비어 있으면 임시 키를 만든다
     * @throws IllegalStateException 키를 읽지 못한 경우 (기동 시점에 실패)
     */
    static RSAKey load(String configured) {
        try {
            if (configured == null || configured.isBlank()) {
                log.warn("서명 키(AUTH_SIGNING_KEY)가 없어 임시 RSA 키를 만들었습니다. 재기동하면 이전 토큰이 모두 무효가 되고 여러 대로 띄울 수 없습니다. 개발용으로만 쓰세요.");
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                return build((RSAPublicKey) pair.getPublic(), (RSAPrivateCrtKey) pair.getPrivate());
            }
            String pem = configured.strip().startsWith("-----BEGIN") ? configured : Files.readString(Path.of(configured.strip()));
            String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            if (publicKey.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("서명 키는 2048비트 이상이어야 합니다.");
            }
            return build(publicKey, privateKey);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("서명 키(app.oauth.signing-key)를 읽을 수 없습니다. PKCS#8 PEM 형식의 RSA 개인키여야 합니다.", e);
        }
    }

    private static RSAKey build(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey) {
        try {
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
        } catch (JOSEException e) {
            throw new IllegalStateException("서명 키 ID 를 만들 수 없습니다.", e);
        }
    }
}
