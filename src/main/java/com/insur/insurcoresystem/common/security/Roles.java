package com.insur.insurcoresystem.common.security;

/**
 * 역할 이름. JWT 의 roles 클레임에 이 값(접두사 ROLE_ 없이)을 담아 발급하며, 스프링 시큐리티에서는 ROLE_ 접두사가 붙은 권한으로 쓰인다.
 * <ul>
 *     <li>AGENT: 청약 접수와 계약 조회</li>
 *     <li>UNDERWRITER: 청약 심사 (승인, 청약취소)와 계약 조회</li>
 *     <li>OPERATOR: 계약 관리 (해지)와 계약 조회</li>
 *     <li>ADMIN: 모든 업무</li>
 * </ul>
 */
public final class Roles {

    public static final String AGENT = "AGENT";
    public static final String UNDERWRITER = "UNDERWRITER";
    public static final String OPERATOR = "OPERATOR";
    public static final String ADMIN = "ADMIN";

    private Roles() {
    }
}
