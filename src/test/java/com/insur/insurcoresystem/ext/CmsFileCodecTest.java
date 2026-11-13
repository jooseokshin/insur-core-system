package com.insur.insurcoresystem.ext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insur.insurcoresystem.ext.cms.CmsFileCodec;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 결제원 CMS 파일: 머리·데이터·꼬리 레코드, 받을 때 건수·합계 검증.
 */
class CmsFileCodecTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 13);

    private static String eb21() {
        return CmsFileCodec.build(CmsFileCodec.EB21, DAY, "9900000001", List.of(
                Map.of("payerNo", "POL-CAR-2026-000001", "bankCd", "004", "accountNo", "11122233344", "amount", 300_000),
                Map.of("payerNo", "POL-CAR-2026-000002", "bankCd", "088", "accountNo", "12345678888", "amount", 50_000)));
    }

    @Test
    void 출금_의뢰_파일은_100바이트_레코드와_건수_합계_꼬리로_만든다() {
        String file = eb21();
        String[] lines = file.split("\n");
        assertThat(lines).hasSize(4);
        assertThat(lines).allMatch(l -> FixedLengthCodec.byteLength(l) == CmsFileCodec.RECORD_BYTES);
        assertThat(lines[0]).startsWith("HEB219900000001" + "20261113");
        assertThat(lines[1]).startsWith("R000001POL-CAR-2026-000001");
        assertThat(lines[3]).startsWith("T00000002000000000350000");

        CmsFileCodec.CmsFile parsed = CmsFileCodec.parse(CmsFileCodec.EB21, DAY, file);
        assertThat(parsed.records()).hasSize(2);
        assertThat(parsed.totalAmount()).isEqualTo(350_000);
        assertThat(parsed.records().get(1).get("payerNo")).isEqualTo("POL-CAR-2026-000002");
    }

    @Test
    void 꼬리와_데이터가_맞지_않거나_잘린_파일은_받지_않는다() {
        String file = eb21();
        String[] lines = file.split("\n");
        // 데이터 한 줄이 빠진 파일 (건수 불일치)
        assertThatThrownBy(() -> CmsFileCodec.parse(CmsFileCodec.EB21, DAY, String.join("\n", lines[0], lines[1], lines[3])))
                .isInstanceOf(CmsFileCodec.InvalidFileException.class).hasMessageContaining("건수 2/1");
        // 꼬리가 잘린 파일
        assertThatThrownBy(() -> CmsFileCodec.parse(CmsFileCodec.EB21, DAY, String.join("\n", lines[0], lines[1], lines[2])))
                .isInstanceOf(CmsFileCodec.InvalidFileException.class).hasMessageContaining("꼬리");
        // 금액이 바뀐 파일 (합계 불일치)
        String tampered = file.replace("0000000300000", "0000000310000");
        assertThatThrownBy(() -> CmsFileCodec.parse(CmsFileCodec.EB21, DAY, tampered))
                .isInstanceOf(CmsFileCodec.InvalidFileException.class).hasMessageContaining("합계");
        // 다른 날짜·종류의 파일
        assertThatThrownBy(() -> CmsFileCodec.parse(CmsFileCodec.EB21, DAY.plusDays(1), file)).isInstanceOf(CmsFileCodec.InvalidFileException.class);
        assertThatThrownBy(() -> CmsFileCodec.parse(CmsFileCodec.EB22, DAY, file)).isInstanceOf(CmsFileCodec.InvalidFileException.class);
        // 레코드 길이가 다른 파일
        assertThatThrownBy(() -> CmsFileCodec.parse(CmsFileCodec.EB21, DAY, file.replace("\nR000002", "\nR00002")))
                .isInstanceOf(CmsFileCodec.InvalidFileException.class).hasMessageContaining("길이");
    }

    @Test
    void 등록_파일은_한글_예금주를_바이트_기준으로_담는다() {
        String file = CmsFileCodec.build(CmsFileCodec.EB11, DAY, "9900000001", List.of(Map.of("payerNo", "POL-1", "bankCd", "004",
                "accountNo", "11122233344", "payerNm", "홍길동", "consent", "P", "consentDate", "20261113")));
        CmsFileCodec.CmsFile parsed = CmsFileCodec.parse(CmsFileCodec.EB11, DAY, file);
        assertThat(parsed.records().get(0).get("payerNm")).isEqualTo("홍길동");
        assertThat(parsed.totalAmount()).isZero();
    }
}
