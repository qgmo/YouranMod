package moe.qingu.youran.api.network.location;

import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.net.LocationMessages;
import moe.qingu.youran.net.YouranNetwork;

import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端：给“我现在操作的这台设备”请位置证明（家具电脑浏览器这类协议栈在操作者客户端上的程序用）。服务端按这个玩家现在接着的设备签：
 * 设备要装了定位模块；签之前操作者会看到系统确认框（在电脑屏幕之外，网页点不到）。坐标由服务端按设备表填，这里只交网站域名和随机数。
 * <p>
 * 任意线程都可以调用；future 在 Netty 线程上完成（要改界面时自己转到客户端主线程）。服务器 {@value #TIMEOUT_MILLIS} 毫秒没回、
 * 断开服务器时以 {@link LocationResult.Status#UNAVAILABLE} 结束。同一时间最多 {@value #MAX_PENDING} 个请求。
 */
public final class ClientLocation {
    /** 比确认框的最长时限（120 秒）再多一点 */
    public static final long TIMEOUT_MILLIS = 130_000;
    public static final int MAX_PENDING = 4;
    private static final AtomicInteger ids = new AtomicInteger();
    private static final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private static Timer timer;

    private static final class Pending {
        final CompletableFuture<LocationResult> future = new CompletableFuture<>();
        final long deadline;

        Pending(long deadline) {
            this.deadline = deadline;
        }
    }

    private ClientLocation() {
    }

    /**
     * 请位置证明。{@code site} 是要位置的网站的域名（收证明、核对的那个网站；Unicode 写法会转成 A-label），
     * {@code nonce} 是网站给的一次性随机数（{@link LocationProof#NONCE}）。格式不对时马上以 {@link LocationResult.Status#BAD_REQUEST} 结束。
     */
    public static CompletableFuture<LocationResult> request(String site, String nonce) {
        String s = site == null ? null : DomainNames.tryToAscii(site.trim().toLowerCase(java.util.Locale.ROOT));
        if (s == null || !LocationProof.SITE.matcher(s).matches() || nonce == null || !LocationProof.NONCE.matcher(nonce).matches()) {
            return CompletableFuture.completedFuture(LocationResult.of(LocationResult.Status.BAD_REQUEST));
        }
        sweep();
        if (pending.size() >= MAX_PENDING) {
            return CompletableFuture.completedFuture(LocationResult.of(LocationResult.Status.BUSY));
        }
        int id = ids.incrementAndGet();
        Pending p = new Pending(System.currentTimeMillis() + TIMEOUT_MILLIS);
        pending.put(id, p);
        ensureTimer();
        YouranNetwork.CHANNEL.sendToServer(new LocationMessages.Request(id, s, nonce));
        return p.future;
    }

    /** 服务端的回复（Netty 线程） */
    public static void onReply(int requestId, String status, String proof) {
        Pending p = pending.remove(requestId);
        if (p != null) {
            LocationResult.Status st = LocationResult.Status.byCode(status);
            p.future.complete(st == LocationResult.Status.OK && (proof == null || proof.isEmpty())
                    ? LocationResult.of(LocationResult.Status.UNAVAILABLE) : new LocationResult(st, proof));
        }
    }

    /** 断开服务器：没回的都按不可用结束 */
    public static void onDisconnected() {
        for (Integer id : pending.keySet()) {
            Pending p = pending.remove(id);
            if (p != null) {
                p.future.complete(LocationResult.of(LocationResult.Status.UNAVAILABLE));
            }
        }
    }

    private static void sweep() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Pending> e = it.next();
            if (now >= e.getValue().deadline) {
                it.remove();
                e.getValue().future.complete(LocationResult.of(LocationResult.Status.UNAVAILABLE));
            }
        }
    }

    private static synchronized void ensureTimer() {
        if (timer == null) {
            timer = new Timer("YouranMod 位置请求超时", true);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    sweep();
                }
            }, 5000, 5000);
        }
    }
}
