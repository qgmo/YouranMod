package moe.qingu.youran.server.net.nav;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 广告账本（纯逻辑，不碰 Minecraft；所有方法 synchronized，网络线程与主线程都会调）：
 * <ul>
 * <li>下单：选一个还能排的位置（正在播的 + 排队的 + 还没付的不超过 1 + {@link AdRules#queue}），先占着，记成“待付款”；</li>
 * <li>付了：变成“排队中”，那个位置空着就马上上线（先上后审）；</li>
 * <li>计时按现实时间（毫秒，服主 11-16；关服时也照走；字段名里的 tick 指这个时钟），到期下线，同一位置下一条接上；</li>
 * <li>管理员下架（退多少由调用方先在 pay.yr 退好）、封禁投放者（封了不能再买）；任何人可以举报。</li>
 * </ul>
 * 改动后调 {@link Listener#changed}（存盘）。
 */
final class AdBook {
    /** 每条广告最多留几条举报 */
    static final int MAX_REPORTS = 50;
    /** 结束（到期、下架、作废）的广告留多久（毫秒）、最多留几条 */
    static final long KEEP_FINISHED_MS = 30L * 24 * 3600 * 1000;
    static final int MAX_FINISHED = 500;
    /** 一个人同时最多几条待付款的 */
    static final int MAX_DRAFTS_PER_OWNER = 3;

    enum State {
        DRAFT("draft", "待付款"),
        QUEUED("queued", "排队中"),
        LIVE("live", "在播"),
        ENDED("ended", "已结束"),
        REMOVED("removed", "已下架"),
        CANCELED("canceled", "已作废");

        final String code;
        final String label;

        State(String code, String label) {
            this.code = code;
            this.label = label;
        }

        boolean finished() {
            return this == ENDED || this == REMOVED || this == CANCELED;
        }

        static State of(String c) {
            for (State s : values()) {
                if (s.code.equals(c)) {
                    return s;
                }
            }
            return null;
        }
    }

    static final class Report {
        final long time;
        final String reason;
        /** 举报人（来源地址认出的操作者名字，认不出时为来源地址） */
        final String by;

        Report(long time, String reason, String by) {
            this.time = time;
            this.reason = reason;
            this.by = by;
        }
    }

    /** 一条广告（字段只在 AdBook 的锁里改） */
    static final class Ad {
        final int id;
        final AdSlot slot;
        int position;
        State state;
        final UUID owner;
        final String ownerName;
        final int days;
        /** 一共付了多少元（标价是绿宝石，按 1 绿宝石 = 2 元记） */
        final long priceYuan;
        /** 文字链的文字；图片广告的说明（悬停文字） */
        final String text;
        final String link;
        String orderId;
        String paymentId;
        final long createdMs;
        long paidMs;
        long startTick = -1;
        long endTick = -1;
        long finishedMs;
        String removedBy;
        String removeReason;
        long refundedYuan;
        final List<Report> reports = new ArrayList<>();

        Ad(int id, AdSlot slot, int position, State state, UUID owner, String ownerName, int days, long priceYuan, String text, String link, long createdMs) {
            this.id = id;
            this.slot = slot;
            this.position = position;
            this.state = state;
            this.owner = owner;
            this.ownerName = ownerName;
            this.days = days;
            this.priceYuan = priceYuan;
            this.text = text;
            this.link = link;
            this.createdMs = createdMs;
        }

        long durationTicks() {
            return days * AdRules.DAY_MS;
        }

        /** 还剩多少 tick（在播的按现在算，排队的是全部，结束的为 0） */
        long remainingTicks(long tick) {
            if (state == State.LIVE) {
                return Math.max(0, endTick - tick);
            }
            return state == State.QUEUED || state == State.DRAFT ? durationTicks() : 0;
        }
    }

    /** 不能做（代号 + 给人看的说明） */
    static final class AdException extends Exception {
        final String code;

        AdException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    static final class Ban {
        final UUID player;
        final String name;
        final String reason;
        final String by;
        final long time;

        Ban(UUID player, String name, String reason, String by, long time) {
            this.player = player;
            this.name = name;
            this.reason = reason;
            this.by = by;
            this.time = time;
        }
    }

    interface Listener {
        /** 账本变了（存盘）；在改动的线程上、锁里调用 */
        void changed(AdBook book);

        /** 广告的记录删掉了（图片也删） */
        default void dropped(Ad ad) {
        }
    }

    private final AdRules rules;
    private final Listener listener;
    private final Map<Integer, Ad> ads = new LinkedHashMap<>();
    private final Map<UUID, Ban> bans = new LinkedHashMap<>();
    /** 帐号设置“操作前必须验证身份”打开了的玩家（服主 11-15；默认关闭） */
    private final java.util.Set<UUID> verify = new java.util.LinkedHashSet<>();
    private int nextId = 1;

    AdBook(AdRules rules, Listener listener) {
        this.rules = rules;
        this.listener = listener;
    }

    AdRules rules() {
        return rules;
    }

    // ---------------------------------------------------------------- 查

    synchronized Ad get(int id) {
        return ads.get(id);
    }

    synchronized Ad byOrder(String orderId) {
        for (Ad a : ads.values()) {
            if (orderId != null && orderId.equals(a.orderId)) {
                return a;
            }
        }
        return null;
    }

    synchronized List<Ad> all() {
        return new ArrayList<>(ads.values());
    }

    synchronized List<Ad> byOwner(UUID owner) {
        List<Ad> out = new ArrayList<>();
        for (Ad a : ads.values()) {
            if (a.owner.equals(owner) && a.state != State.CANCELED) {
                out.add(a);
            }
        }
        out.sort(Comparator.comparingInt((Ad a) -> -a.id));
        return out;
    }

    /** 每个位置正在播的（没有为 null），按位置排 */
    synchronized Ad[] live(AdSlot slot) {
        Ad[] out = new Ad[rules.positions(slot)];
        for (Ad a : ads.values()) {
            if (a.slot == slot && a.state == State.LIVE && a.position < out.length) {
                out[a.position] = a;
            }
        }
        return out;
    }

    /** 排在这条前面的还要多少 tick 播完（在播、已经结束的为 0） */
    synchronized long waitTicks(Ad ad, long tick) {
        if (ad.state != State.QUEUED && ad.state != State.DRAFT) {
            return 0;
        }
        long w = 0;
        for (Ad a : ads.values()) {
            if (a == ad || a.slot != ad.slot || a.position != ad.position) {
                continue;
            }
            if (a.state == State.LIVE) {
                w += a.remainingTicks(tick);
            } else if (a.state == State.QUEUED && ad.state == State.QUEUED && before(a, ad)) {
                w += a.durationTicks();
            } else if (a.state == State.QUEUED && ad.state == State.DRAFT) {
                w += a.durationTicks();
            }
        }
        return w;
    }

    private static boolean before(Ad a, Ad b) {
        return a.paidMs != b.paidMs ? a.paidMs < b.paidMs : a.id < b.id;
    }

    /** 现在下单的话最早多少 tick 后能上；排满了为 -1 */
    synchronized long nextStart(AdSlot slot, long tick) {
        int p = choosePosition(slot, tick);
        return p < 0 ? -1 : load(slot, p, tick);
    }

    synchronized boolean isBanned(UUID player) {
        return bans.containsKey(player);
    }

    synchronized Ban ban(UUID player) {
        return bans.get(player);
    }

    synchronized List<Ban> bans() {
        return new ArrayList<>(bans.values());
    }

    // ---------------------------------------------------------------- 下单、付款

    /** 记一条待付款的广告并占住一个位置 */
    synchronized Ad draft(AdSlot slot, UUID owner, String ownerName, int days, String text, String link, long tick, long nowMs) throws AdException {
        if (bans.containsKey(owner)) {
            throw new AdException("banned", "你已被禁止投放广告");
        }
        if (days < 1 || days > rules.maxDays) {
            throw new AdException("bad_days", "天数要在 1–" + rules.maxDays + " 之间");
        }
        int drafts = 0;
        for (Ad a : ads.values()) {
            if (a.owner.equals(owner) && a.state == State.DRAFT) {
                drafts++;
            }
        }
        if (drafts >= MAX_DRAFTS_PER_OWNER) {
            throw new AdException("too_many_drafts", "你有 " + drafts + " 条还没付款，先付掉或等它作废");
        }
        int p = choosePosition(slot, tick);
        if (p < 0) {
            throw new AdException("full", slot.label + "排满了，过一阵再来");
        }
        long yuan = rules.emeralds(slot, days) * 2;
        Ad a = new Ad(nextId++, slot, p, State.DRAFT, owner, ownerName, days, yuan, text, link, nowMs);
        ads.put(a.id, a);
        listener.changed(this);
        return a;
    }

    synchronized void attachOrder(Ad ad, String orderId) {
        ad.orderId = orderId;
        listener.changed(this);
    }

    /** 收款单没开成、过期、取消：作废，让出位置 */
    synchronized boolean cancelDraft(Ad ad, long nowMs) {
        if (ad.state != State.DRAFT) {
            return false;
        }
        ad.state = State.CANCELED;
        ad.finishedMs = nowMs;
        listener.changed(this);
        return true;
    }

    /** 付了：排队，位置空着就马上上线。已经处理过的返回 false */
    synchronized boolean paid(Ad ad, String paymentId, long tick, long nowMs) {
        if (ad.state != State.DRAFT && ad.state != State.CANCELED) {
            return false;
        }
        ad.state = State.QUEUED;
        ad.paymentId = paymentId;
        ad.paidMs = nowMs;
        ad.finishedMs = 0;
        if (ad.position >= rules.positions(ad.slot)) {
            ad.position = Math.max(0, choosePositionIgnoringCapacity(ad.slot, tick));
        }
        schedule(tick, nowMs);
        listener.changed(this);
        return true;
    }

    /** 到期下线、接上下一条；有变化时返回 true */
    synchronized boolean tick(long tick, long nowMs) {
        boolean changed = schedule(tick, nowMs);
        changed |= prune(nowMs);
        if (changed) {
            listener.changed(this);
        }
        return changed;
    }

    private boolean schedule(long tick, long nowMs) {
        boolean changed = false;
        for (Ad a : ads.values()) {
            if (a.state == State.LIVE && tick >= a.endTick) {
                a.state = State.ENDED;
                a.finishedMs = nowMs;
                changed = true;
            }
        }
        for (AdSlot s : AdSlot.values()) {
            for (int p = 0; p < rules.positions(s); p++) {
                Ad live = null;
                Ad next = null;
                for (Ad a : ads.values()) {
                    if (a.slot != s || a.position != p) {
                        continue;
                    }
                    if (a.state == State.LIVE) {
                        live = a;
                    } else if (a.state == State.QUEUED && (next == null || before(a, next))) {
                        next = a;
                    }
                }
                if (live == null && next != null) {
                    next.state = State.LIVE;
                    next.startTick = tick;
                    next.endTick = tick + next.durationTicks();
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** 清掉结束很久的（图片一起删） */
    private boolean prune(long nowMs) {
        List<Ad> finished = new ArrayList<>();
        for (Ad a : ads.values()) {
            if (a.state.finished()) {
                finished.add(a);
            }
        }
        finished.sort(Comparator.comparingLong((Ad a) -> a.finishedMs));
        boolean changed = false;
        int over = finished.size() - MAX_FINISHED;
        for (Ad a : finished) {
            if (over > 0 || nowMs - a.finishedMs > KEEP_FINISHED_MS) {
                ads.remove(a.id);
                listener.dropped(a);
                over--;
                changed = true;
            }
        }
        return changed;
    }

    private int choosePosition(AdSlot slot, long tick) {
        int best = -1;
        long bestLoad = Long.MAX_VALUE;
        for (int p = 0; p < rules.positions(slot); p++) {
            if (count(slot, p) >= 1 + rules.queue) {
                continue;
            }
            long l = load(slot, p, tick);
            if (l < bestLoad) {
                best = p;
                bestLoad = l;
            }
        }
        return best;
    }

    private int choosePositionIgnoringCapacity(AdSlot slot, long tick) {
        int best = -1;
        long bestLoad = Long.MAX_VALUE;
        for (int p = 0; p < rules.positions(slot); p++) {
            long l = load(slot, p, tick);
            if (l < bestLoad) {
                best = p;
                bestLoad = l;
            }
        }
        return best;
    }

    private int count(AdSlot slot, int p) {
        int n = 0;
        for (Ad a : ads.values()) {
            if (a.slot == slot && a.position == p && (a.state == State.LIVE || a.state == State.QUEUED || a.state == State.DRAFT)) {
                n++;
            }
        }
        return n;
    }

    /** 这个位置上还要播多久（在播的剩余 + 排队的 + 待付款的） */
    private long load(AdSlot slot, int p, long tick) {
        long l = 0;
        for (Ad a : ads.values()) {
            if (a.slot == slot && a.position == p) {
                l += a.remainingTicks(tick);
            }
        }
        return l;
    }

    // ---------------------------------------------------------------- 管理

    /** 没播的部分值多少元（在播的按剩余 tick 折算、往下取整；排队的全部；结束的 0） */
    synchronized long unusedYuan(Ad ad, long tick) {
        if (ad.state == State.QUEUED) {
            return ad.priceYuan;
        }
        if (ad.state != State.LIVE) {
            return 0;
        }
        return ad.priceYuan * ad.remainingTicks(tick) / ad.durationTicks();
    }

    /** 下架（退款由调用方先在 pay.yr 退好，{@code refundedYuan} 是退了多少） */
    synchronized boolean remove(Ad ad, String by, String reason, long refundedYuan, long tick, long nowMs) {
        if (ad.state != State.LIVE && ad.state != State.QUEUED) {
            return false;
        }
        ad.state = State.REMOVED;
        ad.finishedMs = nowMs;
        ad.removedBy = by;
        ad.removeReason = reason;
        ad.refundedYuan = refundedYuan;
        if (ad.endTick < 0 || ad.endTick > tick) {
            ad.endTick = tick;
        }
        schedule(tick, nowMs);
        listener.changed(this);
        return true;
    }

    synchronized boolean verifyRequired(UUID player) {
        return verify.contains(player);
    }

    synchronized void setVerifyRequired(UUID player, boolean on) {
        if (on ? verify.add(player) : verify.remove(player)) {
            listener.changed(this);
        }
    }

    synchronized void ban(UUID player, String name, String reason, String by, long nowMs) {
        bans.put(player, new Ban(player, name, reason, by, nowMs));
        listener.changed(this);
    }

    synchronized boolean unban(UUID player) {
        boolean r = bans.remove(player) != null;
        if (r) {
            listener.changed(this);
        }
        return r;
    }

    /** 记一条举报；这条广告举报满了返回 false */
    synchronized boolean report(Ad ad, String reason, String by, long nowMs) {
        if (ad.reports.size() >= MAX_REPORTS) {
            return false;
        }
        ad.reports.add(new Report(nowMs, reason, by));
        listener.changed(this);
        return true;
    }

    // ---------------------------------------------------------------- 存取

    synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("next_id", nextId);
        JsonArray list = new JsonArray();
        for (Ad a : ads.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", a.id);
            j.addProperty("slot", a.slot.code);
            j.addProperty("position", a.position);
            j.addProperty("state", a.state.code);
            j.addProperty("owner", a.owner.toString());
            j.addProperty("owner_name", a.ownerName);
            j.addProperty("days", a.days);
            j.addProperty("price_yuan", a.priceYuan);
            j.addProperty("text", a.text);
            j.addProperty("link", a.link);
            if (a.orderId != null) {
                j.addProperty("order", a.orderId);
            }
            if (a.paymentId != null) {
                j.addProperty("payment", a.paymentId);
            }
            j.addProperty("created", a.createdMs);
            j.addProperty("paid", a.paidMs);
            j.addProperty("start_tick", a.startTick);
            j.addProperty("end_tick", a.endTick);
            j.addProperty("finished", a.finishedMs);
            if (a.removedBy != null) {
                j.addProperty("removed_by", a.removedBy);
                j.addProperty("remove_reason", a.removeReason);
                j.addProperty("refunded_yuan", a.refundedYuan);
            }
            JsonArray rs = new JsonArray();
            for (Report r : a.reports) {
                JsonObject x = new JsonObject();
                x.addProperty("time", r.time);
                x.addProperty("reason", r.reason);
                x.addProperty("by", r.by);
                rs.add(x);
            }
            j.add("reports", rs);
            list.add(j);
        }
        o.add("ads", list);
        JsonArray bl = new JsonArray();
        for (Ban b : bans.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("player", b.player.toString());
            j.addProperty("name", b.name);
            j.addProperty("reason", b.reason);
            j.addProperty("by", b.by);
            j.addProperty("time", b.time);
            bl.add(j);
        }
        o.add("bans", bl);
        JsonArray vl = new JsonArray();
        for (UUID u : verify) {
            vl.add(u.toString());
        }
        o.add("verify", vl);
        return o;
    }

    /** 读存档（读之前的内容清掉）；坏的条目跳过并写进 {@code problems} */
    synchronized void load(JsonObject o, List<String> problems) {
        ads.clear();
        bans.clear();
        verify.clear();
        if (o.has("verify")) {
            for (JsonElement e : o.getAsJsonArray("verify")) {
                try {
                    verify.add(UUID.fromString(e.getAsString()));
                } catch (RuntimeException ex) {
                    problems.add("跳过一条读不了的帐号设置：" + ex);
                }
            }
        }
        nextId = o.has("next_id") ? o.get("next_id").getAsInt() : 1;
        if (o.has("ads")) {
            for (JsonElement e : o.getAsJsonArray("ads")) {
                try {
                    JsonObject j = e.getAsJsonObject();
                    AdSlot slot = AdSlot.of(j.get("slot").getAsString());
                    State st = State.of(j.get("state").getAsString());
                    if (slot == null || st == null) {
                        throw new IllegalArgumentException("广告位或状态不认识");
                    }
                    Ad a = new Ad(j.get("id").getAsInt(), slot, j.get("position").getAsInt(), st, UUID.fromString(j.get("owner").getAsString()),
                            j.get("owner_name").getAsString(), j.get("days").getAsInt(), j.get("price_yuan").getAsLong(), j.get("text").getAsString(),
                            j.get("link").getAsString(), j.get("created").getAsLong());
                    a.orderId = j.has("order") ? j.get("order").getAsString() : null;
                    a.paymentId = j.has("payment") ? j.get("payment").getAsString() : null;
                    a.paidMs = j.get("paid").getAsLong();
                    a.startTick = j.get("start_tick").getAsLong();
                    a.endTick = j.get("end_tick").getAsLong();
                    a.finishedMs = j.get("finished").getAsLong();
                    if (j.has("removed_by")) {
                        a.removedBy = j.get("removed_by").getAsString();
                        a.removeReason = j.get("remove_reason").getAsString();
                        a.refundedYuan = j.get("refunded_yuan").getAsLong();
                    }
                    if (j.has("reports")) {
                        for (JsonElement r : j.getAsJsonArray("reports")) {
                            JsonObject x = r.getAsJsonObject();
                            a.reports.add(new Report(x.get("time").getAsLong(), x.get("reason").getAsString(), x.get("by").getAsString()));
                        }
                    }
                    ads.put(a.id, a);
                    nextId = Math.max(nextId, a.id + 1);
                } catch (RuntimeException ex) {
                    problems.add("跳过一条读不了的广告：" + ex);
                }
            }
        }
        if (o.has("bans")) {
            for (JsonElement e : o.getAsJsonArray("bans")) {
                try {
                    JsonObject j = e.getAsJsonObject();
                    Ban b = new Ban(UUID.fromString(j.get("player").getAsString()), j.get("name").getAsString(), j.get("reason").getAsString(),
                            j.get("by").getAsString(), j.get("time").getAsLong());
                    bans.put(b.player, b);
                } catch (RuntimeException ex) {
                    problems.add("跳过一条读不了的封禁：" + ex);
                }
            }
        }
        // 配置改了位置数：多出来的位置上的广告挪到别的位置（排队，不丢）
        for (Ad a : ads.values()) {
            if ((a.state == State.LIVE || a.state == State.QUEUED || a.state == State.DRAFT) && a.position >= rules.positions(a.slot)) {
                if (a.state == State.LIVE) {
                    // 换了位置就重新排（整条重播，不少给）
                    a.state = State.QUEUED;
                }
                a.position = 0;
                int best = choosePositionIgnoringCapacity(a.slot, 0);
                a.position = Math.max(0, best);
                problems.add("广告 #" + a.id + " 所在的位置没了（配置改了位置数），挪到 " + a.slot.label + " " + (a.position + 1) + " 号");
            }
        }
    }
}
