package com.insur.insurcoresystem.domain.contract;

import static com.insur.insurcoresystem.domain.contract.ContractStatus.APPLIED;
import static com.insur.insurcoresystem.domain.contract.ContractStatus.CANCELED;
import static com.insur.insurcoresystem.domain.contract.ContractStatus.IN_FORCE;
import static com.insur.insurcoresystem.domain.contract.ContractStatus.TERMINATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insur.insurcoresystem.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractStatusTest {

    @Test
    void 허용되는_상태_전이는_청약에서_유효_또는_취소_유효에서_해지_뿐이다() {
        List<ContractStatus> all = List.of(ContractStatus.values());

        for (ContractStatus from : all) {
            for (ContractStatus to : all) {
                boolean expected = (from == APPLIED && (to == IN_FORCE || to == CANCELED))
                        || (from == IN_FORCE && to == TERMINATED);
                assertThat(from.canChangeTo(to)).as("%s -> %s", from, to).isEqualTo(expected);
            }
        }
    }

    @Test
    void 상태코드로_상태를_찾고_모르는_코드는_예외가_발생한다() {
        assertThat(ContractStatus.fromCode("10")).isEqualTo(APPLIED);
        assertThat(ContractStatus.fromCode("20")).isEqualTo(IN_FORCE);
        assertThat(ContractStatus.fromCode("30")).isEqualTo(TERMINATED);
        assertThat(ContractStatus.fromCode("90")).isEqualTo(CANCELED);

        assertThatThrownBy(() -> ContractStatus.fromCode("99"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("E-CTR-013");
    }
}
