package com.insur.insurcoresystem.ext.kcis;

/**
 * 신용정보원과 전문을 주고받는 통로. 바이트 배열을 보내고 바이트 배열을 받는다.
 * 운영에서는 기관 전용회선·대외계 게이트웨이(MCI/FEP 등)를 거치는 구현으로 바꾸고, 개발·테스트는 {@link KcisSimulator} 를 쓴다.
 * 구현은 연결·응답 시간 초과를 {@link com.insur.insurcoresystem.ext.ExternalAgencyException} 으로 던진다.
 */
public interface KcisTransport {

    byte[] exchange(byte[] request);
}
