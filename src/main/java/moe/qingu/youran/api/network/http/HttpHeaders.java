package moe.qingu.youran.api.network.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * HTTP 首部（按收到 / 写入的顺序，名字不分大小写）。同名的可以有多条（如多个 {@code Set-Cookie}），不合并。
 * 不是线程安全的；交给别的线程前先 {@link #copy()}。
 */
public final class HttpHeaders implements Iterable<HttpHeaders.Field> {
    /** 一条首部 */
    public static final class Field {
        public final String name;
        public final String value;

        public Field(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String toString() {
            return name + ": " + value;
        }
    }

    private final List<Field> fields = new ArrayList<>();

    public HttpHeaders() {
    }

    public HttpHeaders copy() {
        HttpHeaders h = new HttpHeaders();
        h.fields.addAll(fields);
        return h;
    }

    /**
     * 加一条。名字只能是 token 字符（RFC 9110 第 5.6.2 节），值里不能有回车、换行、NUL，否则抛出 {@link IllegalArgumentException}
     * （防首部注入）。
     */
    public HttpHeaders add(String name, String value) {
        if (!isToken(name)) {
            throw new IllegalArgumentException("首部名不合法：" + name);
        }
        if (!isValidValue(value)) {
            throw new IllegalArgumentException("首部 " + name + " 的值里有不允许的字符");
        }
        fields.add(new Field(name, value.trim()));
        return this;
    }

    /** 去掉同名的所有条，再加一条 */
    public HttpHeaders set(String name, String value) {
        remove(name);
        return add(name, value);
    }

    /** 去掉同名的所有条；返回去掉了几条 */
    public int remove(String name) {
        int n = 0;
        Iterator<Field> it = fields.iterator();
        while (it.hasNext()) {
            if (it.next().name.equalsIgnoreCase(name)) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    /** 第一条同名的值；没有为 null */
    public String get(String name) {
        for (Field f : fields) {
            if (f.name.equalsIgnoreCase(name)) {
                return f.value;
            }
        }
        return null;
    }

    /** 所有同名的值（按顺序） */
    public List<String> getAll(String name) {
        List<String> out = new ArrayList<>();
        for (Field f : fields) {
            if (f.name.equalsIgnoreCase(name)) {
                out.add(f.value);
            }
        }
        return out;
    }

    public boolean contains(String name) {
        return get(name) != null;
    }

    public int size() {
        return fields.size();
    }

    public List<Field> fields() {
        return Collections.unmodifiableList(fields);
    }

    @Override
    public Iterator<Field> iterator() {
        return fields().iterator();
    }

    /** 名字小写（比较用） */
    public static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** token（RFC 9110）：可见 ASCII，除去分隔符 */
    public static boolean isToken(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c >= 0x7F || "\"(),/:;<=>?@[\\]{}".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** 首部值：不能有 CR、LF、NUL 与其他控制字符（制表符可以）；按 ISO-8859-1 发送，超出的字符不收 */
    public static boolean isValidValue(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F || c > 0xFF) {
                return false;
            }
        }
        return true;
    }
}
