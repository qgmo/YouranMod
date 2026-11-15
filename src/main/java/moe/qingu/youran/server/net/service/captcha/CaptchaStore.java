package moe.qingu.youran.server.net.service.captcha;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * 验证码题库（服主 11-15）：只在内存里，有上限。纯逻辑，时间由调用方给；线程安全（网络线程与本机端口的线程都用）。
 * <ul>
 * <li>题号：16 个随机字节的十六进制（32 位小写），猜不到；</li>
 * <li>答案：{@value #LENGTH} 位，字符表 {@value #ALPHABET}（去掉了易混的 0/O/Q、1/I/L、2/Z、5/S、8/B、6/G、U/V），
 * 核对时不分大小写、去掉首尾空白；</li>
 * <li>每题只核对一次：核对过就作废，不论对错；{@value #TTL_MS} 毫秒（5 分钟）过期；</li>
 * <li>上限：分两个池子，游戏内（{@code captcha.yr}）与本机端口（网页面板后台）各自最多 {@code maxPerPool} 题，满了不再出新题；
 * 游戏内每个来源地址同时最多 {@code maxPerSource} 题，再出新题时它自己最早的那题作废（防一个来源占满池子）。</li>
 * </ul>
 */
public final class CaptchaStore {
    /** 字符表：21 个，去掉了扭曲后容易认错的 0 O Q、1 I L、2 Z、5 S、8 B、6 G（留 6 去 G）、U V（都去） */
    public static final String ALPHABET = "ACDEFHJKMNPRTWXY34679";
    public static final int LENGTH = 5;
    public static final long TTL_MS = 5 * 60_000L;

    public enum Pool {
        /** 游戏内的 captcha.yr */
        GAME,
        /** 只对本机开放的端口（游戏外的网页面板后台） */
        LOCAL
    }

    /** 一道题 */
    public static final class Challenge {
        public final String id;
        public final String answer;
        public final String source;
        public final Pool pool;
        public final long expiresAt;
        /** 画图用的随机种子（同一题每次画出来一样） */
        public final long seed;
        /** 画好的 PNG（第一次有人要图时才画）；没开始画为 null。只在持有题库锁时读写这个字段 */
        CompletableFuture<byte[]> png;

        Challenge(String id, String answer, String source, Pool pool, long expiresAt, long seed) {
            this.id = id;
            this.answer = answer;
            this.source = source;
            this.pool = pool;
            this.expiresAt = expiresAt;
            this.seed = seed;
        }
    }

    /** 出题的结果 */
    public static final class Created {
        /** 题号；没出成为 null */
        public final String id;
        /** 没出成的原因：池子满了 */
        public final boolean full;

        Created(String id, boolean full) {
            this.id = id;
            this.full = full;
        }
    }

    private final Random random;
    private final int maxPerPool;
    private final int maxPerSource;
    /** 按出题顺序（过期时间也是这个顺序，因为有效期一样） */
    private final LinkedHashMap<String, Challenge> challenges = new LinkedHashMap<>();
    private final Map<String, Integer> perSource = new java.util.HashMap<>();
    private final int[] perPool = new int[Pool.values().length];

    public CaptchaStore(Random random, int maxPerPool, int maxPerSource) {
        this.random = random;
        this.maxPerPool = maxPerPool;
        this.maxPerSource = maxPerSource;
    }

    public static CaptchaStore createDefault() {
        return new CaptchaStore(new SecureRandom(), 2048, 8);
    }

    /** 出一道题；{@code source} 是来源（游戏内为 IPv6 地址，本机端口为固定的名字） */
    public synchronized Created create(String source, Pool pool, long now) {
        purge(now);
        if (pool == Pool.GAME) {
            // 同一来源题太多：作废它自己最早的那一题
            while (perSource.getOrDefault(source, 0) >= maxPerSource) {
                Challenge oldest = null;
                for (Challenge c : challenges.values()) {
                    if (c.pool == pool && c.source.equals(source)) {
                        oldest = c;
                        break;
                    }
                }
                if (oldest == null) {
                    break;
                }
                remove(oldest.id);
            }
        }
        if (perPool[pool.ordinal()] >= maxPerPool) {
            return new Created(null, true);
        }
        String id = newId();
        StringBuilder a = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            a.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        Challenge c = new Challenge(id, a.toString(), source, pool, now + TTL_MS, random.nextLong());
        challenges.put(id, c);
        perPool[pool.ordinal()]++;
        if (pool == Pool.GAME) {
            perSource.merge(source, 1, Integer::sum);
        }
        return new Created(id, false);
    }

    /** 要图：题还在、没过期时返回它（不作废）；否则 null */
    public synchronized Challenge peek(String id, long now) {
        purge(now);
        if (!validId(id)) {
            return null;
        }
        Challenge c = challenges.get(id);
        return c == null || c.expiresAt <= now ? null : c;
    }

    /**
     * 取这题的图：已经开始画就给那个 future；没开始画就用 {@code start} 开画（它返回画图的 future）并记下来。题不在、过期返回 null。
     */
    public synchronized CompletableFuture<byte[]> image(String id, long now, java.util.function.Function<Challenge, CompletableFuture<byte[]>> start) {
        Challenge c = peek(id, now);
        if (c == null) {
            return null;
        }
        if (c.png == null) {
            c.png = start.apply(c);
        }
        return c.png;
    }

    /** 画图失败时清掉记下的 future（还是那一个时），下次再要图时重画 */
    public synchronized void forgetImage(String id, CompletableFuture<byte[]> future) {
        Challenge c = challenges.get(id);
        if (c != null && c.png == future) {
            c.png = null;
        }
    }

    /**
     * 核对：题在、没过期、答案对（不分大小写，去掉首尾空白）时 true。不论对错这题都作废。
     */
    public synchronized boolean verify(String id, String answer, long now) {
        purge(now);
        if (!validId(id)) {
            return false;
        }
        Challenge c = challenges.get(id);
        if (c == null) {
            return false;
        }
        remove(id);
        if (c.expiresAt <= now || answer == null) {
            return false;
        }
        String given = answer.trim().toUpperCase(Locale.ROOT);
        return constantTimeEquals(given, c.answer);
    }

    /** 现有多少题（测试与统计用） */
    public synchronized int size() {
        return challenges.size();
    }

    public synchronized int size(Pool pool) {
        return perPool[pool.ordinal()];
    }

    /** 题号的格式：32 位小写十六进制 */
    public static boolean validId(String id) {
        if (id == null || id.length() != 32) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private String newId() {
        while (true) {
            byte[] b = new byte[16];
            random.nextBytes(b);
            StringBuilder sb = new StringBuilder(32);
            for (byte x : b) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            }
            String id = sb.toString();
            if (!challenges.containsKey(id)) {
                return id;
            }
        }
    }

    /** 过期的去掉（按出题顺序就是过期顺序，只看开头） */
    private void purge(long now) {
        Iterator<Map.Entry<String, Challenge>> it = challenges.entrySet().iterator();
        while (it.hasNext()) {
            Challenge c = it.next().getValue();
            if (c.expiresAt > now) {
                break;
            }
            it.remove();
            forget(c);
        }
    }

    private void remove(String id) {
        Challenge c = challenges.remove(id);
        if (c != null) {
            forget(c);
        }
    }

    private void forget(Challenge c) {
        perPool[c.pool.ordinal()]--;
        if (c.pool == Pool.GAME) {
            perSource.computeIfPresent(c.source, (k, v) -> v <= 1 ? null : v - 1);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int d = 0;
        for (int i = 0; i < a.length(); i++) {
            d |= a.charAt(i) ^ b.charAt(i);
        }
        return d == 0;
    }
}
