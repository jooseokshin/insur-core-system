package com.insur.insurcoresystem.common.security;

/**
 * 역할 이름. JWT 의 roles 클레임에 이 값(접두사 ROLE_ 없이)을 담아 발급하며, 스프링 시큐리티에서는 ROLE_ 접두사가 붙은 권한으로 쓰인다.
 * <ul>
 *     <li>AGENT: 청약 접수와 계약 조회</li>
 *     <li>UNDERWRITER: 청약 심사 (승인, 청약취소)와 계약 조회</li>
 *     <li>OPERATOR: 계약 관리 (해지)와 계약 조회</li>
 *     <li>CLAIM: 보상 (손해사정, 지급·면책)과 계약 조회</li>
 *     <li>ADMIN: 모든 업무</li>
 *     <li>CHANNEL: 채널계 시스템(다이렉트 홈페이지·앱, TM 등). 사람이 아니라 시스템이 OAuth client credentials 로 받은 토큰에만 있다.
 *         보험료 산출, 청약, 수납, 갱신, 사고 접수·접수 취소를 하고, 자기 채널이 접수한 계약만 본다</li>
 *     <li>EXTERNAL: 대외기관 시스템(보험개발원 실손 청구 전산화 중계 등). client credentials 토큰에만 있고, 그 기관용으로 연 수신 API 만 쓴다</li>
 * </ul>
 */
public final class Roles {

    public static final String AGENT = "AGENT";
    public static final String UNDERWRITER = "UNDERWRITER";
    public static final String OPERATOR = "OPERATOR";
    public static final String CLAIM = "CLAIM";
    public static final String ADMIN = "ADMIN";
    public static final String CHANNEL = "CHANNEL";
    public static final String EXTERNAL = "EXTERNAL";

    private Roles() {
    }
}
