package moe.qingu.youran.server.net.tls;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 证书里的域名约束（RFC 5280 第 4.2.1.10 节），只看 DNS 名字那一类：签发前核对申请的名字在约束之内，
 * 免得签出浏览器不认的证书。几张证书（中间 CA 和它的上级）的约束要同时满足。
 * <p>
 * 匹配同 OpenSSL：约束 {@code yr} 管 {@code yr} 本身和它下面的所有名字；以点开头的 {@code .yr} 只管下面的名字。
 */
public final class NameConstraints {
    static final String OID = "2.5.29.30";

    /** 每张证书的允许、禁止清单（小写）；某张证书没有 DNS 允许清单时它不限制允许 */
    private final List<List<String>> permitted = new ArrayList<>();
    private final List<List<String>> excluded = new ArrayList<>();

    private NameConstraints() {
    }

    /** 读这几张证书的域名约束；格式不对时抛出 {@link IllegalArgumentException} */
    public static NameConstraints of(List<X509Certificate> certs) {
        NameConstraints n = new NameConstraints();
        for (X509Certificate c : certs) {
            byte[] ext = c.getExtensionValue(OID);
            if (ext == null) {
                continue;
            }
            Reader outer = new Reader(ext);
            byte[] value = outer.expect(0x04);
            Reader seq = new Reader(new Reader(value).expect(0x30));
            List<String> p = new ArrayList<>();
            List<String> x = new ArrayList<>();
            while (seq.more()) {
                int tag = seq.peekTag();
                byte[] subtrees = seq.any();
                List<String> into = tag == 0xA0 ? p : tag == 0xA1 ? x : null;
                if (into == null) {
                    continue;
                }
                Reader st = new Reader(subtrees);
                while (st.more()) {
                    Reader sub = new Reader(st.expect(0x30));
                    if (sub.more() && sub.peekTag() == 0x82) {
                        into.add(new String(sub.any(), StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT));
                    }
                }
            }
            if (!p.isEmpty()) {
                n.permitted.add(Collections.unmodifiableList(p));
            }
            if (!x.isEmpty()) {
                n.excluded.add(Collections.unmodifiableList(x));
            }
        }
        return n;
    }

    /** 名字（小写 A-label）在约束之内 */
    public boolean permits(String name) {
        for (List<String> ex : excluded) {
            for (String c : ex) {
                if (matches(c, name)) {
                    return false;
                }
            }
        }
        for (List<String> p : permitted) {
            boolean any = false;
            for (String c : p) {
                if (matches(c, name)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** 给人看的约束（如“只许 yr”）；没有约束时为“不限” */
    public String describe() {
        if (permitted.isEmpty() && excluded.isEmpty()) {
            return "不限";
        }
        StringBuilder sb = new StringBuilder();
        for (List<String> p : permitted) {
            sb.append(sb.length() == 0 ? "" : "；").append("只许 ").append(String.join("、", p));
        }
        for (List<String> x : excluded) {
            sb.append(sb.length() == 0 ? "" : "；").append("不许 ").append(String.join("、", x));
        }
        return sb.toString();
    }

    static boolean matches(String constraint, String name) {
        if (constraint.isEmpty()) {
            return true;
        }
        if (constraint.startsWith(".")) {
            return name.endsWith(constraint) && name.length() > constraint.length();
        }
        return name.equals(constraint) || name.endsWith("." + constraint);
    }

    /** 最简单的 DER 读法（只要标签、长度） */
    private static final class Reader {
        private final byte[] b;
        private int pos;

        Reader(byte[] b) {
            this.b = b;
        }

        boolean more() {
            return pos < b.length;
        }

        int peekTag() {
            return b[pos] & 0xFF;
        }

        byte[] expect(int tag) {
            if (!more() || peekTag() != tag) {
                throw new IllegalArgumentException("域名约束的格式不对");
            }
            return any();
        }

        byte[] any() {
            if (pos + 2 > b.length) {
                throw new IllegalArgumentException("域名约束的格式不对");
            }
            pos++;
            int len = b[pos++] & 0xFF;
            if (len >= 0x80) {
                int n = len & 0x7F;
                if (n == 0 || n > 3 || pos + n > b.length) {
                    throw new IllegalArgumentException("域名约束的格式不对");
                }
                len = 0;
                for (int i = 0; i < n; i++) {
                    len = (len << 8) | (b[pos++] & 0xFF);
                }
            }
            if (pos + len > b.length) {
                throw new IllegalArgumentException("域名约束的格式不对");
            }
            byte[] out = new byte[len];
            System.arraycopy(b, pos, out, 0, len);
            pos += len;
            return out;
        }
    }
}
