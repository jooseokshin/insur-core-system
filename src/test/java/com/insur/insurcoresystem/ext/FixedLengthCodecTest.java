package com.insur.insurcoresystem.ext;

import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.an;
import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.n;
import static com.insur.insurcoresystem.ext.FixedLengthCodec.Field.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 고정길이 전문. 길이는 글자 수가 아니라 기관 문자셋(MS949) 바이트 수이고, 한글은 한 글자가 2바이트다.
 */
class FixedLengthCodecTest {

    private final List<FixedLengthCodec.Field> layout = List.of(n("amount", 6), an("code", 4), text("name", 7));

    @Test
    void 숫자는_앞을_0으로_문자는_뒤를_공백으로_바이트_길이만큼_채운다() {
        String encoded = FixedLengthCodec.encode(layout, Map.of("amount", 1500, "code", "AB", "name", "실손"));
        assertThat(encoded).isEqualTo("001500AB  실손   ");
        assertThat(encoded.length()).isEqualTo(15);                       // 글자는 15개지만
        assertThat(FixedLengthCodec.byteLength(encoded)).isEqualTo(17);    // 바이트는 6 + 4 + 7 = 17
        assertThat(FixedLengthCodec.decode(layout, encoded, 0))
                .containsEntry("amount", "001500").containsEntry("code", "AB").containsEntry("name", "실손");
    }

    @Test
    void 자를_수_있는_글_항목은_한글을_반으로_자르지_않고_나머지는_길이를_넘으면_거절한다() {
        // 7바이트에 한글 4자(8바이트)는 3자(6바이트) + 공백 1바이트로 줄인다
        String encoded = FixedLengthCodec.encode(layout, Map.of("amount", 0, "code", "X", "name", "의료보험"));
        assertThat(encoded).endsWith("의료보 ");
        assertThat(FixedLengthCodec.byteLength(encoded)).isEqualTo(17);

        assertThatThrownBy(() -> FixedLengthCodec.encode(layout, Map.of("amount", 1234567)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> FixedLengthCodec.encode(layout, Map.of("code", "가나다")))   // 6바이트 > 4
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("code");
        assertThatThrownBy(() -> FixedLengthCodec.encode(layout, Map.of("amount", "-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 반복부는_같은_묶음을_건수만큼_읽는다() {
        List<FixedLengthCodec.Field> row = List.of(an("k", 2), n("v", 3));
        String message = "HEAD" + FixedLengthCodec.encode(row, Map.of("k", "A", "v", 1)) + FixedLengthCodec.encode(row, Map.of("k", "B", "v", 22));
        assertThat(FixedLengthCodec.decodeRepeated(row, message, 4, 2))
                .extracting(m -> m.get("k") + "=" + Integer.parseInt(m.get("v"))).containsExactly("A=1", "B=22");
        assertThatThrownBy(() -> FixedLengthCodec.decodeRepeated(row, message, 4, 3)).isInstanceOf(IllegalArgumentException.class);
    }
}
