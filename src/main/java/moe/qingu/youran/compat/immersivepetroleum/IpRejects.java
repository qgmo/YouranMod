package moe.qingu.youran.compat.immersivepetroleum;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * 沉浸原油的网络包被拒时记日志：同一个玩家、同一个原因每分钟最多记一条，中间被拒的只计数，下一条里报出来
 * （改过的客户端每 tick 都能发，不能每个都记）。只在服务端主线程上用。
 */
public final class IpRejects {
    private static final Logger LOG = LogManager.getLogger("YouranMod");
    private static final long INTERVAL_MILLIS = 60_000L;
    private static final int MAX_KEYS = 1024;
    private static final Map<String, long[]> LAST = new HashMap<>();

    private IpRejects() {
    }

    /** 记一次被拒：{@code who} 是玩家名，{@code what} 是哪种包，{@code detail} 是这次的值 */
    public static void reject(String who, String what, String detail) {
        long now = System.currentTimeMillis();
        String key = who + "\u0000" + what;
        long[] e = LAST.get(key);
        if (e == null) {
            if (LAST.size() >= MAX_KEYS) {
                LAST.clear();
            }
            LAST.put(key, new long[]{now, 0});
            LOG.warn("拒绝了 {} 发的沉浸原油{}：{}", who, what, detail);
            return;
        }
        if (now - e[0] >= INTERVAL_MILLIS) {
            if (e[1] > 0) {
                LOG.warn("拒绝了 {} 发的沉浸原油{}：{}（上一条日志以后又拒绝了 {} 个）", who, what, detail, e[1]);
            } else {
                LOG.warn("拒绝了 {} 发的沉浸原油{}：{}", who, what, detail);
            }
            e[0] = now;
            e[1] = 0;
            return;
        }
        e[1]++;
    }
}
