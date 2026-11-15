package com.insur.insurcoresystem.ext;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 고정길이 전문 인코더·디코더. 대외기관 연계(신용정보원, 금융결제원, 보험개발원 등)는 아직 JSON 보다 고정길이 전문이 흔하다.
 * <ul>
 *     <li>길이는 글자 수가 아니라 <b>바이트</b>로 센다. 기관 문자셋(MS949/EUC-KR)에서 한글은 2바이트라서, 글자 수로 자르면 뒤 항목이 밀린다.</li>
 *     <li>숫자(N) 항목은 왼쪽을 0으로, 문자(AN) 항목은 오른쪽을 공백으로 채운다.</li>
 *     <li>값이 칸보다 길면 기본은 거절한다(조용히 자르면 증권번호 같은 키가 바뀐다). 자르는 것이 허용된 항목(이름·설명)만
 *         {@link Field#truncatable()} 로 표시하고, 그때도 한글 한 글자가 반으로 갈리지 않게 글자 경계에서 자른다.</li>
 * </ul>
 */
public final class FixedLengthCodec {

    /** 기관 문자셋. 국내 금융권 대외 전문은 대부분 EUC-KR 계열이며, MS949 는 그 상위 집합이다 */
    public static final Charset CHARSET = Charset.forName("MS949");

    private FixedLengthCodec() {
    }

    /**
     * 전문 항목 정의.
     *
     * @param name        항목 이름
     * @param length      바이트 길이
     * @param numeric     숫자 항목(왼쪽 0 채움)인지
     * @param truncatable 칸보다 긴 값을 글자 경계에서 잘라도 되는 항목인지 (이름·설명류만)
     */
    public record Field(String name, int length, boolean numeric, boolean truncatable) {
        public static Field an(String name, int length) {
            return new Field(name, length, false, false);
        }

        public static Field n(String name, int length) {
            return new Field(name, length, true, false);
        }

        public static Field text(String name, int length) {
            return new Field(name, length, false, true);
        }
    }

    /** 항목 목록의 바이트 길이 합 */
    public static int length(List<Field> layout) {
        return layout.stream().mapToInt(Field::length).sum();
    }

    /** 항목 순서대로 값을 채워 고정길이 문자열로 만든다. 값이 없는 항목은 숫자면 0, 문자면 공백 */
    public static String encode(List<Field> layout, Map<String, ?> values) {
        StringBuilder out = new StringBuilder();
        for (Field field : layout) {
            Object raw = values.get(field.name());
            out.append(pad(field, raw == null ? "" : raw.toString()));
        }
        return out.toString();
    }

    /** 고정길이 문자열을 항목별 값으로 나눈다. 문자 항목은 뒤 공백을, 숫자 항목은 앞 0 을 지우지 않고 그대로 둔다(호출자가 해석) */
    public static Map<String, String> decode(List<Field> layout, String message, int fromByte) {
        byte[] bytes = message.getBytes(CHARSET);
        if (bytes.length < fromByte + length(layout)) {
            throw new IllegalArgumentException("전문 길이가 짧습니다: " + bytes.length + "바이트, 필요 " + (fromByte + length(layout)));
        }
        Map<String, String> values = new LinkedHashMap<>();
        int offset = fromByte;
        for (Field field : layout) {
            String value = new String(bytes, offset, field.length(), CHARSET);
            values.put(field.name(), field.numeric() ? value : stripTrailing(value));
            offset += field.length();
        }
        return values;
    }

    /** 반복부: 같은 항목 묶음이 count 번 이어진 부분을 나눈다 */
    public static List<Map<String, String>> decodeRepeated(List<Field> layout, String message, int fromByte, int count) {
        List<Map<String, String>> rows = new ArrayList<>();
        int size = length(layout);
        for (int i = 0; i < count; i++) {
            rows.add(decode(layout, message, fromByte + i * size));
        }
        return rows;
    }

    /** 문자열의 바이트 길이 (기관 문자셋 기준) */
    public static int byteLength(String value) {
        return value.getBytes(CHARSET).length;
    }

    private static String pad(Field field, String value) {
        if (field.numeric() && !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("숫자 항목에 숫자가 아닌 값: " + field.name() + "=" + value);
        }
        String fitted = value;
        if (byteLength(value) > field.length()) {
            if (!field.truncatable()) {
                throw new IllegalArgumentException("항목 길이 초과: " + field.name() + " (" + byteLength(value) + " > " + field.length() + "바이트)");
            }
            fitted = fitBytes(value, field.length());
        }
        int padding = field.length() - byteLength(fitted);
        return field.numeric() ? "0".repeat(padding) + fitted : fitted + " ".repeat(padding);
    }

    /** 바이트 한도 안에 들어가는 만큼만 글자 단위로 남긴다 (한글 한 글자를 반으로 자르지 않는다) */
    static String fitBytes(String value, int maxBytes) {
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int size = byteLength(ch);
            if (used + size > maxBytes) {
                break;
            }
            out.append(ch);
            used += size;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }
}
