package moe.qingu.youran.api.network.location;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网站发一次性随机数用的小账本（线程安全）：{@link #issue} 发一个，{@link #consume} 核对并作废（只能用一次），过期的自动丢掉，
 * 最多存 {@code capacity} 个（满了丢最早的）。时间由调用方给（毫秒）。
 */
public final class NonceBook {
    private final SecureRandom random = new SecureRandom();
    private final long ttlMillis;
    private final int capacity;
    private final LinkedHashMap<String, Long> issued = new LinkedHashMap<>();

    public NonceBook(long ttlMillis, int capacity) {
        this.ttlMillis = ttlMillis;
        this.capacity = capacity;
    }

    /** 发一个新的（22 个 base64url 字，128 位） */
    public synchronized String issue(long nowMillis) {
        prune(nowMillis);
        byte[] b = new byte[16];
        random.nextBytes(b);
        String n = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        while (issued.size() >= capacity) {
            Iterator<String> it = issued.keySet().iterator();
            it.next();
            it.remove();
        }
        issued.put(n, nowMillis + ttlMillis);
        return n;
    }

    /** 是自己发的、没过期、没用过时返回 true，并作废它 */
    public synchronized boolean consume(String nonce, long nowMillis) {
        Long exp = nonce == null ? null : issued.remove(nonce);
        return exp != null && nowMillis < exp;
    }

    public synchronized int size() {
        return issued.size();
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Long>> it = issued.entrySet().iterator();
        while (it.hasNext()) {
            if (now >= it.next().getValue()) {
                it.remove();
            }
        }
    }
}
