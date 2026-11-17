package moe.qingu.youran.api.network.ca;

import moe.qingu.youran.api.network.dns.DnsServer;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 能加、删 TXT 记录的办法：给了它，{@link CertificateClient} 就用 DNS 认证（加 {@code _acme-challenge.<名字>} 的 TXT，认证完删掉）。
 * 名字所在的区要由这个办法背后的 DNS 管着，并且已经委派好（经递归解析器查得到）。
 */
public interface TxtRecords {
    /** 给 {@code name} 加一条 TXT（同名的别的 TXT 不动） */
    void add(String name, String value);

    /** 删掉 {@code name} 的这条 TXT */
    void remove(String name, String value);

    /** 用自己跑的默认 DNS 服务（{@link DnsServer}）加删：只动经这里加的 TXT，同名的别的 TXT 会被换掉 */
    static TxtRecords of(DnsServer dns) {
        if (dns == null) {
            throw new IllegalArgumentException("dns 不能为 null");
        }
        Map<String, Set<String>> mine = new HashMap<>();
        return new TxtRecords() {
            @Override
            public void add(String name, String value) {
                synchronized (mine) {
                    Set<String> s = mine.computeIfAbsent(name, n -> new LinkedHashSet<>());
                    s.add(value);
                    dns.setTexts(name, s);
                }
            }

            @Override
            public void remove(String name, String value) {
                synchronized (mine) {
                    Set<String> s = mine.get(name);
                    if (s == null || !s.remove(value)) {
                        return;
                    }
                    if (s.isEmpty()) {
                        mine.remove(name);
                    }
                    dns.setTexts(name, s);
                }
            }
        };
    }
}
