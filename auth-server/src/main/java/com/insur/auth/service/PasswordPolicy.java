package com.insur.auth.service;

import com.insur.auth.common.BusinessException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 비밀번호 규칙과 임시 비밀번호 생성.
 * 규칙: 8~64자, 영문·숫자·특수문자를 각각 하나 이상, 공백 불가, 사번을 포함할 수 없다.
 */
public final class PasswordPolicy {

    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 64;
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SPECIALS = "!@#$%^&*";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordPolicy() {
    }

    /**
     * 새 비밀번호가 규칙에 맞는지 확인한다.
     *
     * @throws BusinessException 규칙 위반(E-AUTH-104)
     */
    public static void validate(String empId, String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw violation("비밀번호는 " + MIN_LENGTH + "~" + MAX_LENGTH + "자여야 합니다.");
        }
        if (password.chars().anyMatch(Character::isWhitespace)) {
            throw violation("비밀번호에 공백을 쓸 수 없습니다.");
        }
        boolean letter = password.chars().anyMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
        boolean digit = password.chars().anyMatch(c -> c >= '0' && c <= '9');
        boolean special = password.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        if (!letter || !digit || !special) {
            throw violation("비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다.");
        }
        if (empId != null && password.toLowerCase().contains(empId.toLowerCase())) {
            throw violation("비밀번호에 사번을 포함할 수 없습니다.");
        }
    }

    /** 규칙을 만족하는 12자 임시 비밀번호를 만든다. 헷갈리는 글자(0, O, 1, l, I)는 쓰지 않는다 */
    public static String temporary() {
        List<Character> chars = new ArrayList<>();
        chars.add(pick(LETTERS));
        chars.add(pick(DIGITS));
        chars.add(pick(SPECIALS));
        String all = LETTERS + DIGITS + SPECIALS;
        while (chars.size() < 12) {
            chars.add(pick(all));
        }
        Collections.shuffle(chars, RANDOM);
        StringBuilder sb = new StringBuilder();
        chars.forEach(sb::append);
        return sb.toString();
    }

    private static char pick(String source) {
        return source.charAt(RANDOM.nextInt(source.length()));
    }

    private static BusinessException violation(String message) {
        return new BusinessException("E-AUTH-104", message);
    }
}
