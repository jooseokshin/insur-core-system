package com.insur.insurcoresystem.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MdcClearFilterTest {

    private final MdcClearFilter filter = new MdcClearFilter();

    @Test
    void 요청_처리가_끝나면_MDC의_거래ID를_지운다() throws Exception {
        AtomicReference<String> duringRequest = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
            MDC.put(MdcClearFilter.MDC_KEY, "tx-1");
            duringRequest.set(MDC.get(MdcClearFilter.MDC_KEY));
        });

        assertThat(duringRequest.get()).isEqualTo("tx-1");
        assertThat(MDC.get(MdcClearFilter.MDC_KEY)).isNull();
    }

    @Test
    void 처리_중_예외가_발생해도_MDC의_거래ID를_지운다() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> {
                    MDC.put(MdcClearFilter.MDC_KEY, "tx-2");
                    throw new ServletException("boom");
                }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(MdcClearFilter.MDC_KEY)).isNull();
    }
}
