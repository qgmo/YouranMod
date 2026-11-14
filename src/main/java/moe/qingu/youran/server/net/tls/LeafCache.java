package moe.qingu.youran.server.net.tls;

import java.security.GeneralSecurityException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 签好的叶子证书按域名缓存（只在内存，开服后第一次有人用 https 访问这个名字时签）。
 * 剩下不到 {@value #RENEW_DAYS} 天就重签（下一次握手时）；中间 CA 自己快到期时叶子跟着它的到期时间走。
 */
public final class LeafCache {
    static final int RENEW_DAYS = 30;
    /** 缓存最多这么多个名字（防止客户端乱填 SNI 时堆积；只给网站拥有的名字签，正常远到不了） */
    static final int MAX_NAMES = 4096;

    private final IntermediateCa ca;
    private final LongSupplier clock;
    private final Map<String, IntermediateCa.Issued> issued = new HashMap<>();
    private int issuedCount;

    public LeafCache(IntermediateCa ca, LongSupplier clock) {
        this.ca = ca;
        this.clock = clock;
    }

    public IntermediateCa ca() {
        return ca;
    }

    /** 这个名字现在用的证书（没签过、或快到期时现签） */
    public synchronized IntermediateCa.Issued get(String name) throws GeneralSecurityException {
        long now = clock.getAsLong();
        IntermediateCa.Issued i = issued.get(name);
        if (i != null && !needsRenewal(i, now)) {
            return i;
        }
        if (i == null && issued.size() >= MAX_NAMES) {
            issued.clear();
        }
        i = ca.issue(name, new Date(now));
        issued.put(name, i);
        issuedCount++;
        return i;
    }

    /** 已签的（不续签）；没有为 null */
    public synchronized IntermediateCa.Issued peek(String name) {
        return issued.get(name);
    }

    boolean needsRenewal(IntermediateCa.Issued i, long now) {
        long left = i.leaf().getNotAfter().getTime() - now;
        // 叶子跟着中间 CA 一起快到期时没法续得更长，只在真过期前一天重签
        long caLeft = ca.certificate().getNotAfter().getTime() - now;
        long threshold = caLeft <= RENEW_DAYS * IntermediateCa.DAY_MS ? IntermediateCa.DAY_MS : RENEW_DAYS * IntermediateCa.DAY_MS;
        return left < threshold;
    }

    /** 一共签过几张（测试、统计用） */
    public synchronized int issuedCount() {
        return issuedCount;
    }
}
