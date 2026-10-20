package com.insur.insurcoresystem.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpInputMessage;

class LogSanitizerTest {

    @Test
    void 줄바꿈과_제어_문자는_밑줄로_바꾸고_긴_값은_자른다() {
        assertThat(LogSanitizer.clean("tx-1\nINFO fake line\r\t")).isEqualTo("tx-1_INFO fake line__");
        assertThat(LogSanitizer.clean("a b\u0085c")).isEqualTo("a_b_c");
        assertThat(LogSanitizer.clean("X".repeat(150))).isEqualTo("X".repeat(100) + "...");
        assertThat(LogSanitizer.clean("정상 값-123")).isEqualTo("정상 값-123");
        assertThat(LogSanitizer.clean(null)).isNull();
    }

    @Test
    void 요청_헤더의_거래ID에_줄바꿈이_있어도_로그_MDC에는_정리된_값만_들어간다() {
        com.insur.insurcoresystem.common.dto.CommonMessage<Object> message = new com.insur.insurcoresystem.common.dto.CommonMessage<>(
                com.insur.insurcoresystem.common.dto.CommonHeader.builder().globalTxId("tx-9\n2026-10-09 ERROR forged").build(), null);
        try {
            new CommonHeaderRequestBodyAdvice().afterBodyRead(message, org.mockito.Mockito.mock(HttpInputMessage.class),
                    null, null, null);

            assertThat(MDC.get(MdcClearFilter.MDC_KEY)).isEqualTo("tx-9_2026-10-09 ERROR forged");
            // 응답과 DB 에 쓰는 원래 값은 바뀌지 않는다
            assertThat(message.getHeader().getGlobalTxId()).isEqualTo("tx-9\n2026-10-09 ERROR forged");
        } finally {
            MDC.remove(MdcClearFilter.MDC_KEY);
        }
    }
}
