package com.insur.insurcoresystem.common.security;

import com.insur.insurcoresystem.common.exception.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 계좌번호 암호화 (AES-256-GCM). 계좌번호는 지급할 때 은행에 평문으로 보내야 하므로 해시가 아니라 복호화 가능한 암호화로 저장한다.
 * 저장 값 = Base64(IV 12바이트 + 암호문·인증태그). 키는 {@code app.crypto.account-key}(ACCOUNT_ENC_KEY, Base64 32바이트)이며
 * 운영에서는 키 관리 시스템(KMS·HSM)에서 받아야 한다. 키가 없으면 지급 기능만 쓸 수 없다(E-PAY-009).
 */
@Slf4j
@Component
public class AccountCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AccountCipher(@Value("${app.crypto.account-key:}") String base64Key) {
        if (!StringUtils.hasText(base64Key)) {
            this.key = null;
            log.warn("계좌번호 암호화 키(ACCOUNT_ENC_KEY)가 없어 지급 기능을 쓸 수 없습니다.");
            return;
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("app.crypto.account-key 는 Base64 로 인코딩한 32바이트(AES-256) 키여야 합니다.");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("계좌번호 암호화 실패", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("계좌번호 복호화 실패 (키가 바뀌었거나 값이 손상됨)", e);
        }
    }

    public boolean configured() {
        return key != null;
    }

    /** 앞 3자리와 끝 3자리만 남긴다 */
    public static String mask(String accountNo) {
        if (accountNo.length() <= 6) {
            return "*".repeat(accountNo.length());
        }
        return accountNo.substring(0, 3) + "*".repeat(accountNo.length() - 6) + accountNo.substring(accountNo.length() - 3);
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "E-PAY-009", "계좌번호 암호화 키가 설정되지 않아 지급을 처리할 수 없습니다.");
        }
        return key;
    }
}
