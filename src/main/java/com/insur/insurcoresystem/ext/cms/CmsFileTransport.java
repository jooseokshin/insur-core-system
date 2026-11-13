package com.insur.insurcoresystem.ext.cms;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 결제원과 CMS 파일을 주고받는 통로. 실무에서는 결제원 파일 송수신 프로그램(전용망)을 쓰고 결과 파일은 정해진 시각 뒤에 받을 수 있다.
 * 개발·테스트는 {@link KftcCmsSimulator}.
 */
public interface CmsFileTransport {

    /** 의뢰 파일(EB11, EB21)을 보낸다 */
    void send(String fileType, LocalDate fileDate, String content);

    /** 결과 파일(EB12, EB22)을 받는다. 아직 만들어지지 않았으면 빈 값 */
    Optional<String> receive(String fileType, LocalDate fileDate);
}
