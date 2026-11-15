package moe.qingu.youran.server.net.service.captcha;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按来源的令牌桶：每个来源每秒补 {@code perSecond} 个，最多攒 {@code burst} 个。来源最多记 {@code maxSources} 个，
 * 多了先丢掉已经攒满（等于没有限制状态）的，再不够丢最久没来的。纯逻辑，时间由调用方给；线程安全。
 */
public final class SourceLimiter {
    private final double perMs;
    private final double burst;
    private final int maxSources;
    /** 按最近访问排序 */
    private final LinkedHashMap<String, double[]> buckets = new LinkedHashMap<>(16, 0.75f, true);

    public SourceLimiter(double perSecond, int burst, int maxSources) {
        this.perMs = perSecond / 1000.0;
        this.burst = burst;
        this.maxSources = maxSources;
    }

    /** 能放行就扣一个并返回 true */
    public synchronized boolean tryAcquire(String source, long now) {
        double[] b = buckets.get(source);
        if (b == null) {
            if (buckets.size() >= maxSources) {
                prune(now);
            }
            b = new double[]{burst, now};
            buckets.put(source, b);
        } else if (now > b[1]) {
            b[0] = Math.min(burst, b[0] + (now - b[1]) * perMs);
            b[1] = now;
        }
        if (b[0] < 1) {
            return false;
        }
        b[0] -= 1;
        return true;
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, double[]>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            double[] b = it.next().getValue();
            if (b[0] + (now - b[1]) * perMs >= burst) {
                it.remove();
            }
        }
        it = buckets.entrySet().iterator();
        while (buckets.size() >= maxSources && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    synchronized int sources() {
        return buckets.size();
    }
}
