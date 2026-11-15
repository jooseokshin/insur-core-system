package com.insur.insurcoresystem.ext.fep;

/**
 * FEP 가 기관 하나와 전문을 주고받는 통로 (전용회선·VPN 위의 TCP 소켓 등). 바이트를 보내고 바이트를 받는다.
 * 개발·테스트는 모의 기관이 구현한다. 응답이 없거나 연결이 끊기면 예외를 던진다(기관이 받았는지 알 수 없는 상태).
 */
public interface FepTransport {

    /** 기관 코드 (TB_FEP_INST.INST_CD) */
    String instCd();

    byte[] exchange(byte[] request);
}
