package com.insur.insurcoresystem.service.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.insur.insurcoresystem.common.exception.BusinessException;
import com.insur.insurcoresystem.mapper.common.SequenceMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ContractNumberGeneratorTest {

    @Test
    void 일련번호가_한도를_넘으면_500_업무예외가_발생한다() {
        SequenceMapper sequenceMapper = mock(SequenceMapper.class, invocation -> 1_000_000L);
        ContractNumberGenerator generator = new ContractNumberGenerator(sequenceMapper, Clock.systemUTC());

        assertThatThrownBy(() -> generator.generate(ProductType.CAR))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("E-SEQ-002");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                });
    }
}
