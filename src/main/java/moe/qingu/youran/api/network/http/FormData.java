package moe.qingu.youran.api.network.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code application/x-www-form-urlencoded}（WHATWG URL 标准第 5 节）的解码：{@code +} 是空格，{@code %XX} 按 UTF-8 */
public final class FormData {
    /** 最多收这么多对（再多的不要） */
    public static final int MAX_PAIRS = 256;

    private FormData() {
    }

    /** 解码；同名取第一个；不是合法 UTF-8 的值换成替换字符 */
    public static Map<String, String> parse(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<>();
        int n = 0;
        for (String pair : encoded.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            if (++n > MAX_PAIRS) {
                break;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.putIfAbsent(name, value);
        }
        return Collections.unmodifiableMap(out);
    }

    /** 解一段：{@code +} → 空格，{@code %XX} → 字节，坏的 {@code %} 原样保留 */
    public static String decode(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') {
                b.write(' ');
            } else if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                b.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 2;
            } else if (c < 0x80) {
                b.write(c);
            } else {
                byte[] u = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                b.write(u, 0, u.length);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(b.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
