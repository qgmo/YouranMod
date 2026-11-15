package moe.qingu.youran.server.lang;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 读 1.12 的语言文件（{@code key=value}，UTF-8），规则与游戏的 LanguageMap 相同：空行与 # 开头的行跳过，按第一个 = 分开；
 * {@code %d}、{@code %1$d}、{@code %.2f} 这类数字格式统一换成 {@code %s}（{@code %1$s}）；文件里有一行 {@code #PARSE_ESCAPES}
 * 时按 Java 的转义解析值（Forge 的扩展）。与 YouranAPI 的 {@code LangFile} 相同（语言目录两边共用）。
 */
public final class LangFile {

    /** 单个语言文件的大小上限（原版 zh_cn.lang 约 150 KB）。 */
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Pattern NUMERIC = Pattern.compile("%(\\d+\\$)?[\\d.]*[df]");

    private LangFile() {
    }

    public static Map<String, String> read(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
            if (buf.size() > MAX_BYTES) {
                throw new IOException("语言文件超过 " + (MAX_BYTES >> 20) + " MB");
            }
        }
        return parse(new String(buf.toByteArray(), StandardCharsets.UTF_8));
    }

    public static Map<String, String> parse(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\r?\n|\r", -1);
        boolean escapes = false;
        for (String line : lines) {
            if (line.trim().equals("#PARSE_ESCAPES")) {
                escapes = true;
                break;
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String value = NUMERIC.matcher(line.substring(eq + 1)).replaceAll("%$1s");
            out.put(line.substring(0, eq), escapes ? unescape(value) : value);
        }
        return out;
    }

    /** Java 风格的转义：换行、制表、回车、反斜杠、引号与 4 位十六进制的 Unicode 转义；不认识的保留原样。 */
    static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case '\\':
                case '"':
                case '\'':
                    sb.append(e);
                    break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (NumberFormatException ex) {
                            // 不是四位十六进制：原样保留
                        }
                    }
                    sb.append('\\').append(e);
                    break;
                default:
                    sb.append('\\').append(e);
            }
        }
        return sb.toString();
    }
}
