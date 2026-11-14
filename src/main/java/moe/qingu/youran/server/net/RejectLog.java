package moe.qingu.youran.server.net;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 丢包的日志：同一个来源、同一个原因，每隔 {@code intervalMillis} 最多记一条，中间被丢的只计数，下一条里报出来。
 * 只在网络线程上用。
 */
public final class RejectLog {
    private static final Logger LOG = LogManager.getLogger("YouranMod");
    private final long intervalMillis;
    private final Map<String, Entry> entries = new HashMap<>();
    private long total;

    private static final class Entry {
        long lastLogged;
        int suppressed;
    }

    public RejectLog(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    /** 记一次丢包；返回这次是否真的写了日志 */
    public boolean reject(String source, String reason, long nowMillis) {
        total++;
        String key = source + "\u0000" + reason;
        Entry e = entries.get(key);
        if (e == null) {
            if (entries.size() > 4096) {
                prune(nowMillis);
            }
            e = new Entry();
            e.lastLogged = nowMillis;
            entries.put(key, e);
            LOG.warn("游戏内网络丢掉了 {} 发的包：{}", source, reason);
            return true;
        }
        if (nowMillis - e.lastLogged >= intervalMillis) {
            if (e.suppressed > 0) {
                LOG.warn("游戏内网络丢掉了 {} 发的包：{}（上一条日志以后又丢了 {} 个同样的）", source, reason, e.suppressed);
            } else {
                LOG.warn("游戏内网络丢掉了 {} 发的包：{}", source, reason);
            }
            e.lastLogged = nowMillis;
            e.suppressed = 0;
            return true;
        }
        e.suppressed++;
        return false;
    }

    public long total() {
        return total;
    }

    private void prune(long now) {
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().lastLogged >= intervalMillis) {
                it.remove();
            }
        }
    }
}
