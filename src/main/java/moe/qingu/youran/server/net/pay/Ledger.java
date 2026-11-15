package moe.qingu.youran.server.net.pay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PayItem;
import moe.qingu.youran.api.pay.PayRecord;
import moe.qingu.youran.api.pay.PaymentInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * pay.yr 的账：账户（余额、收支记录、密钥）、收款单、付款、进行中的扣物品 / 发物品（{@link Tx}）。
 * <p>
 * <b>先记账后动</b>：每个改动先写成一条日志（{@link PayStore#append}，fsync 以后才返回），再改内存；写不进去就什么都不改、报错。
 * 动到世界（背包）的操作分两步记：开始（{@code begin}，写明要拿哪些、给哪些）→ 动物品、存盘、核对 → 结束（{@code commit} 或 {@code abort}）。
 * 中间崩了，开服时由 {@link TxRunner#recover} 照存档里的实际情况（玩家存档里有没有这笔的标记、掉落物在不在区块里）补上结束那一条。
 * <p>
 * 所有方法加同一把锁，任意线程可调用；改世界的部分在 {@link TxRunner}（主线程）。
 */
final class Ledger {
    static final Logger LOG = LogManager.getLogger("YouranMod");
    static final int MAX_RECORDS = 200;
    /** 快照之间最多攒多少条日志 */
    static final int SNAPSHOT_EVERY = 2000;

    enum OrderStatus {
        OPEN, PAYING, PAID, CANCELED
    }

    enum TxKind {
        /** 付款：从付款人背包拿（{@code take}），可能找零（{@code give}），价格记进收款方 */
        PAY,
        /** 提现 / 发给玩家：从账户扣（{@code value}），物品给玩家（{@code give}） */
        WITHDRAW
    }

    static final class Account {
        final AccountRef ref;
        String name;
        long yuan;
        final TreeMap<PayItem, Long> items = new TreeMap<>();
        String keyHash;
        String keyPrefix;
        long keyCreated;
        final ArrayDeque<PayRecord> records = new ArrayDeque<>();

        Account(AccountRef ref, String name) {
            this.ref = ref;
            this.name = name;
        }

        Amount balance() {
            return Amount.of(yuan, items);
        }

        void add(Amount a) {
            yuan = Math.addExact(yuan, a.yuan);
            for (Map.Entry<PayItem, Long> e : a.items.entrySet()) {
                items.merge(e.getKey(), e.getValue(), Math::addExact);
            }
        }

        void subtract(Amount a) {
            Amount left = balance().minus(a);
            yuan = left.yuan;
            items.clear();
            items.putAll(left.items);
        }

        void record(PayRecord r) {
            records.addFirst(r);
            while (records.size() > MAX_RECORDS) {
                records.removeLast();
            }
        }
    }

    static final class Order {
        String id;
        AccountRef receiver;
        String site;
        Amount price;
        Currency.Unit unit;
        String description;
        String reference;
        String returnUrl;
        String cancelUrl;
        long createdAt;
        long expiresAt;
        String origin;
        OrderStatus status = OrderStatus.OPEN;
        long paidAt;
        UUID payer;
        String payerName;
        String paymentId;

        OrderInfo.Status status(long now) {
            switch (status) {
                case PAID:
                    return OrderInfo.Status.PAID;
                case CANCELED:
                    return OrderInfo.Status.CANCELED;
                case PAYING:
                    return OrderInfo.Status.PAYING;
                default:
                    return now >= expiresAt ? OrderInfo.Status.EXPIRED : OrderInfo.Status.OPEN;
            }
        }
    }

    static final class Payment {
        String id;
        String orderId;
        UUID payer;
        String payerName;
        AccountRef receiver;
        String site;
        String reference;
        Amount amount;
        Amount refunded = Amount.ZERO;
        long time;

        PaymentInfo info() {
            return new PaymentInfo(id, orderId, payer, payerName, receiver, site, reference, amount, refunded, time);
        }
    }

    /** 一笔进行中的扣物品 / 发物品 */
    static final class Tx {
        String id;
        TxKind kind;
        UUID player;
        String playerName;
        /** 付款：收款方；提现：从哪个账户扣 */
        AccountRef account;
        String orderId;
        String paymentId;
        /** 从背包拿走的（付款） */
        Map<PayItem, Long> take = Collections.emptyMap();
        /** 给玩家的（找零、提现）：先放背包，放不下的掉在脚边（{@link #drops}） */
        Map<PayItem, Long> give = Collections.emptyMap();
        /** 付款：收款方收到的价格；提现：从账户扣的 */
        Amount value = Amount.ZERO;
        String note;
        long at;
        /** 掉在脚边的（放不下背包的）；没有为 null */
        Map<PayItem, Long> drops;
        int dropDim;
        int dropCx;
        int dropCz;

        /** 放进背包的那部分 = 给的 − 掉的 */
        Map<PayItem, Long> inventoryPart() {
            Map<PayItem, Long> m = new LinkedHashMap<>(give);
            if (drops != null) {
                for (Map.Entry<PayItem, Long> e : drops.entrySet()) {
                    long left = m.getOrDefault(e.getKey(), 0L) - e.getValue();
                    if (left > 0) {
                        m.put(e.getKey(), left);
                    } else {
                        m.remove(e.getKey());
                    }
                }
            }
            return m;
        }
    }

    /** 一笔付款完成（给回调用） */
    static final class Paid {
        final Order order;
        final Payment payment;

        Paid(Order order, Payment payment) {
            this.order = order;
            this.payment = payment;
        }
    }

    private final PayStore store;
    private final Random random;
    private final Map<AccountRef, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final Map<String, Payment> payments = new LinkedHashMap<>();
    private final Map<String, Tx> pending = new LinkedHashMap<>();
    private final Map<String, AccountRef> keys = new HashMap<>();
    /** 回放日志时为 true（不发回调、不检查） */
    private boolean replaying;

    Ledger(PayStore store, Random random) {
        this.store = store;
        this.random = random == null ? new SecureRandom() : random;
    }

    // ---------------------------------------------------------------- 读盘

    /** 读快照、重放日志 */
    synchronized void load() throws IOException {
        PayStore.Loaded l = store.open();
        if (l.badBytes > 0) {
            LOG.warn("pay.yr：账本日志最后 {} 字节是坏的（写到一半时停了），已挪到 {} 下的 journal.bad-*，按最后一条好的继续", l.badBytes, store.dir());
        }
        if (l.snapshot != null) {
            readSnapshot(l.snapshot);
        }
        replaying = true;
        try {
            for (JsonObject e : l.entries) {
                try {
                    apply(e);
                } catch (RuntimeException ex) {
                    LOG.error("pay.yr：重放账本日志第 {} 条出错，跳过：{}", e.get("seq"), e, ex);
                }
            }
        } finally {
            replaying = false;
        }
    }

    synchronized void close() {
        store.close();
    }

    // ---------------------------------------------------------------- 查

    synchronized Account account(AccountRef ref) {
        return accounts.get(ref);
    }

    synchronized Amount balance(AccountRef ref) {
        Account a = accounts.get(ref);
        return a == null ? Amount.ZERO : a.balance();
    }

    synchronized List<PayRecord> records(AccountRef ref, int max) {
        Account a = accounts.get(ref);
        if (a == null) {
            return Collections.emptyList();
        }
        List<PayRecord> out = new ArrayList<>();
        for (PayRecord r : a.records) {
            if (out.size() >= max) {
                break;
            }
            out.add(r);
        }
        return out;
    }

    synchronized String name(AccountRef ref) {
        Account a = accounts.get(ref);
        if (a != null && a.name != null) {
            return a.name;
        }
        return ref.isPlayer() ? ref.player.toString() : ref.virtual;
    }

    synchronized Order order(String id) {
        return id == null ? null : orders.get(id);
    }

    synchronized OrderInfo orderInfo(String id, long now, Function<String, String> checkoutUrl) {
        Order o = order(id);
        return o == null ? null : info(o, now, checkoutUrl);
    }

    synchronized OrderInfo info(Order o, long now, Function<String, String> checkoutUrl) {
        Payment p = o.paymentId == null ? null : payments.get(o.paymentId);
        return new OrderInfo(o.id, o.status(now), o.receiver, name(o.receiver), o.site, o.price, o.unit, o.description, o.reference,
                o.returnUrl, o.cancelUrl, o.createdAt, o.expiresAt, o.paidAt, o.payer, o.payerName, o.paymentId,
                p == null ? Amount.ZERO : p.refunded, checkoutUrl.apply(o.id));
    }

    synchronized Payment payment(String id) {
        return id == null ? null : payments.get(id);
    }

    /** 这个账户收到的付款，新的在前 */
    synchronized List<Payment> paymentsTo(AccountRef receiver, int max) {
        List<Payment> out = new ArrayList<>();
        List<Payment> all = new ArrayList<>(payments.values());
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) {
            if (all.get(i).receiver.equals(receiver)) {
                out.add(all.get(i));
            }
        }
        return out;
    }

    synchronized List<Tx> pending() {
        return new ArrayList<>(pending.values());
    }

    synchronized Tx pending(String id) {
        return pending.get(id);
    }

    /** 这个玩家有没有进行中的（还没结束的）扣物品 / 发物品 */
    synchronized boolean hasPending(UUID player) {
        for (Tx t : pending.values()) {
            if (t.player.equals(player)) {
                return true;
            }
        }
        return false;
    }

    synchronized AccountRef accountByKey(String key) {
        return key == null ? null : keys.get(hashKey(key));
    }

    /** 这个收款方现在有多少张还能付的收款单 */
    synchronized int openOrders(AccountRef receiver, long now) {
        int n = 0;
        for (Order o : orders.values()) {
            if (o.receiver.equals(receiver) && o.status(now) == OrderInfo.Status.OPEN) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 改

    /** 玩家账户（没有时建）：记下现在的名字 */
    synchronized Account touchPlayer(UUID player, String name) throws IOException {
        AccountRef ref = AccountRef.player(player);
        Account a = accounts.get(ref);
        if (a == null || (name != null && !name.equals(a.name))) {
            JsonObject e = entry("acct");
            e.addProperty("acct", ref.id());
            PayJson.put(e, "name", name);
            commitEntry(e);
            a = accounts.get(ref);
        }
        return a;
    }

    /** 虚拟账户（没有时建） */
    synchronized Account ensureVirtual(String key) throws IOException {
        AccountRef ref = AccountRef.virtual(key);
        Account a = accounts.get(ref);
        if (a == null) {
            JsonObject e = entry("acct");
            e.addProperty("acct", ref.id());
            e.addProperty("name", key);
            commitEntry(e);
            a = accounts.get(ref);
        }
        return a;
    }

    /** 收款单的草稿（已检查过） */
    static final class Draft {
        AccountRef receiver;
        String site;
        Amount price;
        Currency.Unit unit;
        String description;
        String reference;
        String returnUrl;
        String cancelUrl;
        long expiresAt;
        String origin;
    }

    synchronized Order createOrder(Draft d, long now) throws IOException {
        JsonObject e = entry("order");
        String id = newId("ord_");
        e.addProperty("id", id);
        e.addProperty("recv", d.receiver.id());
        e.addProperty("site", d.site);
        e.add("price", PayJson.amount(d.price));
        e.addProperty("unit", d.unit.code);
        PayJson.put(e, "desc", d.description);
        PayJson.put(e, "ref", d.reference);
        PayJson.put(e, "ret", d.returnUrl);
        PayJson.put(e, "cancel", d.cancelUrl);
        e.addProperty("at", now);
        e.addProperty("exp", d.expiresAt);
        PayJson.put(e, "origin", d.origin);
        commitEntry(e);
        return orders.get(id);
    }

    synchronized boolean cancel(String orderId, AccountRef receiver, long now) throws IOException {
        Order o = orders.get(orderId);
        if (o == null || !o.receiver.equals(receiver) || o.status(now) != OrderInfo.Status.OPEN) {
            return false;
        }
        JsonObject e = entry("cancel");
        e.addProperty("id", orderId);
        e.addProperty("at", now);
        commitEntry(e);
        return true;
    }

    /** 新密钥（旧的作废）；返回明文，只给这一次 */
    synchronized String newKey(AccountRef acct, long now) throws IOException {
        if (accounts.get(acct) == null) {
            throw new IllegalStateException("没有这个账户");
        }
        String key = "yrsk_" + randomText(24);
        JsonObject e = entry("key");
        e.addProperty("acct", acct.id());
        e.addProperty("hash", hashKey(key));
        e.addProperty("prefix", key.substring(0, 9));
        e.addProperty("at", now);
        commitEntry(e);
        return key;
    }

    /** 开始付款：收款单要还能付；记下要拿的、要找的 */
    synchronized Tx beginPay(Order o, UUID payer, String payerName, Map<PayItem, Long> take, Map<PayItem, Long> change, Drops drops, long now)
            throws IOException, PayException {
        if (o.status(now) != OrderInfo.Status.OPEN) {
            throw new PayException(o.status(now) == OrderInfo.Status.EXPIRED ? "order_expired" : "order_not_open",
                    o.status(now) == OrderInfo.Status.EXPIRED ? "收款单已经过期了" : "收款单已经付过或取消了");
        }
        if (hasPending(payer)) {
            throw new PayException("busy", "上一笔还没处理完，请稍后再试");
        }
        JsonObject e = entry("begin");
        e.addProperty("tx", newId("tx_"));
        e.addProperty("kind", TxKind.PAY.name());
        e.addProperty("player", payer.toString());
        PayJson.put(e, "pname", payerName);
        e.addProperty("acct", o.receiver.id());
        e.addProperty("order", o.id);
        e.addProperty("payment", newId("pay_"));
        e.add("take", PayJson.items(take));
        e.add("give", PayJson.items(change));
        e.add("value", PayJson.amount(o.price));
        putDrops(e, drops);
        e.addProperty("at", now);
        commitEntry(e);
        return pending.get(e.get("tx").getAsString());
    }

    /** 开始提现：先从账户扣掉（余额要够），物品之后再给 */
    synchronized Tx beginWithdraw(AccountRef acct, UUID player, String playerName, Amount value, Map<PayItem, Long> give, Drops drops,
                                  String note, long now) throws IOException, PayException {
        Account a = accounts.get(acct);
        if (a == null || !a.balance().covers(value)) {
            throw new PayException("insufficient_balance", "账户余额不够");
        }
        if (value.isZero()) {
            throw new PayException("invalid_amount", "要提多少？");
        }
        if (!Amount.ofItems(give).equals(value)) {
            throw new IllegalArgumentException("给的物品和扣的金额对不上");
        }
        if (hasPending(player)) {
            throw new PayException("busy", "上一笔还没处理完，请稍后再试");
        }
        JsonObject e = entry("begin");
        e.addProperty("tx", newId("tx_"));
        e.addProperty("kind", TxKind.WITHDRAW.name());
        e.addProperty("player", player.toString());
        PayJson.put(e, "pname", playerName);
        e.addProperty("acct", acct.id());
        e.add("give", PayJson.items(give));
        e.add("value", PayJson.amount(value));
        putDrops(e, drops);
        PayJson.put(e, "note", note);
        e.addProperty("at", now);
        commitEntry(e);
        return pending.get(e.get("tx").getAsString());
    }

    /** 放不下背包、要掉在脚边的，掉在哪个区块（开始时就记下，崩溃恢复时去那里找） */
    static final class Drops {
        final Map<PayItem, Long> items;
        final int dim;
        final int cx;
        final int cz;

        Drops(Map<PayItem, Long> items, int dim, int cx, int cz) {
            this.items = items;
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
        }
    }

    private static void putDrops(JsonObject e, Drops d) {
        if (d != null && !d.items.isEmpty()) {
            JsonObject o = new JsonObject();
            o.addProperty("dim", d.dim);
            o.addProperty("cx", d.cx);
            o.addProperty("cz", d.cz);
            o.add("items", PayJson.items(d.items));
            e.add("drop", o);
        }
    }

    /**
     * 结束：{@code inventory} 背包那部分在不在存档里，{@code dropped} 掉落物里实际存下来的。
     * 付款时背包那部分必须在（不在的要 {@link #abort}）；掉落物（找零）少了的记进付款人账户。
     * 提现时没送到的部分退回账户。返回付款完成的信息（付款时）。
     */
    synchronized Paid commit(Tx tx, boolean inventory, Map<PayItem, Long> dropped, long now) throws IOException {
        if (tx.kind == TxKind.PAY && !inventory) {
            throw new IllegalArgumentException("付款的背包那部分没存下来，只能作废");
        }
        // 没送到的（照这一刻的情况算好写进日志，重放时不再推算）：掉落物里没存下来的，提现时背包那部分没存下来的也算
        Map<PayItem, Long> got = dropped == null ? Collections.<PayItem, Long>emptyMap() : dropped;
        Map<PayItem, Long> back = new LinkedHashMap<>();
        if (tx.drops != null) {
            for (Map.Entry<PayItem, Long> d : tx.drops.entrySet()) {
                long lost = d.getValue() - Math.min(d.getValue(), got.getOrDefault(d.getKey(), 0L));
                if (lost > 0) {
                    back.put(d.getKey(), lost);
                }
            }
        }
        if (!inventory) {
            for (Map.Entry<PayItem, Long> i : tx.inventoryPart().entrySet()) {
                back.merge(i.getKey(), i.getValue(), Math::addExact);
            }
        }
        JsonObject e = entry("commit");
        e.addProperty("tx", tx.id);
        e.addProperty("inv", inventory);
        e.add("dropped", PayJson.items(got));
        e.add("back", PayJson.items(back));
        e.addProperty("at", now);
        commitEntry(e);
        if (tx.kind == TxKind.PAY) {
            Order o = orders.get(tx.orderId);
            Payment p = payments.get(tx.paymentId);
            return o == null || p == null ? null : new Paid(o, p);
        }
        return null;
    }

    synchronized void abort(Tx tx, String why, long now) throws IOException {
        JsonObject e = entry("abort");
        e.addProperty("tx", tx.id);
        PayJson.put(e, "why", why);
        e.addProperty("at", now);
        commitEntry(e);
    }

    /** 退款：从收款方账户扣回，记进付款人账户 */
    synchronized Payment refund(String paymentId, Amount amount, String note, long now) throws IOException, PayException {
        Payment p = payments.get(paymentId);
        if (p == null) {
            throw new PayException("no_such_payment", "没有这笔付款");
        }
        if (amount == null || amount.isZero()) {
            throw new PayException("invalid_amount", "要退多少？");
        }
        if (!p.amount.minus(p.refunded).covers(amount)) {
            throw new PayException("exceeds_refundable", "超过了这笔付款还能退的（付了 " + p.amount + "，已退 " + p.refunded + "）");
        }
        Account r = accounts.get(p.receiver);
        if (r == null || !r.balance().covers(amount)) {
            Amount lack = r == null ? amount : r.balance().shortOf(amount);
            throw new PayException("insufficient_balance", "收款账户余额不够退（已经提现了）：还差 " + lack);
        }
        JsonObject e = entry("refund");
        e.addProperty("id", newId("ref_"));
        e.addProperty("payment", paymentId);
        e.add("amount", PayJson.amount(amount));
        PayJson.put(e, "note", note);
        e.addProperty("at", now);
        commitEntry(e);
        return p;
    }

    /** 模组取出 / 作废虚拟账户里的东西 */
    synchronized void remove(AccountRef acct, Amount amount, PayRecord.Kind kind, String note, long now) throws IOException, PayException {
        Account a = accounts.get(acct);
        if (amount == null || amount.isZero()) {
            throw new PayException("invalid_amount", "要取多少？");
        }
        if (a == null || !a.balance().covers(amount)) {
            throw new PayException("insufficient_balance", "账户余额不够");
        }
        JsonObject e = entry("take");
        e.addProperty("acct", acct.id());
        e.add("amount", PayJson.amount(amount));
        e.addProperty("kind", kind.name());
        PayJson.put(e, "note", note);
        e.addProperty("at", now);
        commitEntry(e);
    }

    /** 攒够了就写快照（顺便清掉过期很久的收款单、很早的付款） */
    synchronized void maybeSnapshot(long now, long keepMillis) {
        if (store.entriesSinceSnapshot() >= SNAPSHOT_EVERY) {
            snapshotNow(now, keepMillis);
        }
    }

    synchronized void snapshotNow(long now, long keepMillis) {
        prune(now, keepMillis);
        try {
            store.snapshot(writeSnapshot());
        } catch (IOException e) {
            LOG.error("pay.yr：写账本快照失败（日志还在，不影响记账）", e);
        }
    }

    // ---------------------------------------------------------------- 日志 → 内存

    private JsonObject entry(String type) {
        JsonObject e = new JsonObject();
        e.addProperty("t", type);
        return e;
    }

    /** 先写盘，写成了再改内存 */
    private void commitEntry(JsonObject e) throws IOException {
        store.append(e);
        apply(e);
    }

    void apply(JsonObject e) {
        String t = e.get("t").getAsString();
        switch (t) {
            case "acct": {
                AccountRef ref = AccountRef.parse(e.get("acct").getAsString());
                Account a = accounts.get(ref);
                if (a == null) {
                    accounts.put(ref, new Account(ref, PayJson.str(e, "name")));
                } else if (PayJson.str(e, "name") != null) {
                    a.name = PayJson.str(e, "name");
                }
                break;
            }
            case "key": {
                Account a = need(AccountRef.parse(e.get("acct").getAsString()));
                if (a.keyHash != null) {
                    keys.remove(a.keyHash);
                }
                a.keyHash = e.get("hash").getAsString();
                a.keyPrefix = PayJson.str(e, "prefix");
                a.keyCreated = PayJson.num(e, "at");
                keys.put(a.keyHash, a.ref);
                break;
            }
            case "order": {
                Order o = new Order();
                o.id = e.get("id").getAsString();
                o.receiver = AccountRef.parse(e.get("recv").getAsString());
                o.site = e.get("site").getAsString();
                o.price = PayJson.amount(e.get("price"));
                Currency.Unit u = Currency.Unit.of(PayJson.str(e, "unit"));
                o.unit = u == null ? Currency.Unit.EMERALD : u;
                o.description = PayJson.str(e, "desc");
                o.reference = PayJson.str(e, "ref");
                o.returnUrl = PayJson.str(e, "ret");
                o.cancelUrl = PayJson.str(e, "cancel");
                o.createdAt = PayJson.num(e, "at");
                o.expiresAt = PayJson.num(e, "exp");
                o.origin = PayJson.str(e, "origin");
                orders.put(o.id, o);
                break;
            }
            case "cancel": {
                Order o = orders.get(e.get("id").getAsString());
                if (o != null && o.status == OrderStatus.OPEN) {
                    o.status = OrderStatus.CANCELED;
                }
                break;
            }
            case "begin":
                applyBegin(e);
                break;
            case "commit":
                applyCommit(e);
                break;
            case "abort": {
                Tx tx = pending.remove(e.get("tx").getAsString());
                if (tx == null) {
                    break;
                }
                if (tx.kind == TxKind.PAY) {
                    Order o = orders.get(tx.orderId);
                    if (o != null && o.status == OrderStatus.PAYING) {
                        o.status = OrderStatus.OPEN;
                    }
                } else {
                    need(tx.account).add(tx.value);
                }
                break;
            }
            case "refund": {
                Payment p = payments.get(e.get("payment").getAsString());
                Amount a = PayJson.amount(e.get("amount"));
                long at = PayJson.num(e, "at");
                String note = PayJson.str(e, "note");
                p.refunded = p.refunded.plus(a);
                Account r = need(p.receiver);
                r.subtract(a);
                r.record(new PayRecord(at, PayRecord.Kind.REFUND_OUT, a, p.payerName, p.id, note));
                Account payer = needPlayer(p.payer, p.payerName);
                payer.add(a);
                payer.record(new PayRecord(at, PayRecord.Kind.REFUND_IN, a, r.name, p.id, note));
                break;
            }
            case "take": {
                Account a = need(AccountRef.parse(e.get("acct").getAsString()));
                Amount amt = PayJson.amount(e.get("amount"));
                a.subtract(amt);
                a.record(new PayRecord(PayJson.num(e, "at"), PayRecord.Kind.valueOf(e.get("kind").getAsString()), amt, null, null,
                        PayJson.str(e, "note")));
                break;
            }
            default:
                LOG.warn("pay.yr：账本日志里有不认识的条目 {}，跳过", t);
        }
    }

    private void applyBegin(JsonObject e) {
        Tx tx = new Tx();
        tx.id = e.get("tx").getAsString();
        tx.kind = TxKind.valueOf(e.get("kind").getAsString());
        tx.player = UUID.fromString(e.get("player").getAsString());
        tx.playerName = PayJson.str(e, "pname");
        tx.account = AccountRef.parse(e.get("acct").getAsString());
        tx.orderId = PayJson.str(e, "order");
        tx.paymentId = PayJson.str(e, "payment");
        tx.take = PayJson.items(e.get("take"));
        tx.give = PayJson.items(e.get("give"));
        tx.value = PayJson.amount(e.get("value"));
        tx.note = PayJson.str(e, "note");
        tx.at = PayJson.num(e, "at");
        readDrops(tx, e);
        pending.put(tx.id, tx);
        if (tx.kind == TxKind.PAY) {
            Order o = orders.get(tx.orderId);
            if (o != null) {
                o.status = OrderStatus.PAYING;
            }
        } else {
            need(tx.account).subtract(tx.value);
        }
    }

    private void applyCommit(JsonObject e) {
        Tx tx = pending.remove(e.get("tx").getAsString());
        if (tx == null) {
            return;
        }
        // 没送到、要记回账户的
        Map<PayItem, Long> back = PayJson.items(e.get("back"));
        long at = PayJson.num(e, "at");
        if (tx.kind == TxKind.PAY) {
            Order o = orders.get(tx.orderId);
            Account recv = need(tx.account);
            Payment p = new Payment();
            p.id = tx.paymentId;
            p.orderId = tx.orderId;
            p.payer = tx.player;
            p.payerName = tx.playerName;
            p.receiver = tx.account;
            p.site = o == null ? null : o.site;
            p.reference = o == null ? null : o.reference;
            p.amount = tx.value;
            p.time = at;
            payments.put(p.id, p);
            if (o != null) {
                o.status = OrderStatus.PAID;
                o.paidAt = at;
                o.payer = tx.player;
                o.payerName = tx.playerName;
                o.paymentId = p.id;
            }
            String what = o == null ? null : (o.site + (o.description == null ? "" : " " + o.description));
            recv.add(tx.value);
            recv.record(new PayRecord(at, PayRecord.Kind.RECEIVED, tx.value, tx.playerName, p.id, what));
            Account payer = needPlayer(tx.player, tx.playerName);
            payer.record(new PayRecord(at, PayRecord.Kind.PAID, tx.value, recv.name, p.id, what));
            Amount change = Amount.ofItems(back);
            if (!change.isZero()) {
                payer.add(change);
                payer.record(new PayRecord(at, PayRecord.Kind.CHANGE, change, recv.name, p.id, "找零没送到（掉落物没存下来），记进账户"));
            }
        } else {
            Account a = need(tx.account);
            Amount returned = Amount.ofItems(back);
            a.record(new PayRecord(at, PayRecord.Kind.WITHDRAW, tx.value, tx.playerName, tx.id, tx.note));
            if (!returned.isZero()) {
                a.add(returned);
                a.record(new PayRecord(at, PayRecord.Kind.WITHDRAW_RETURNED, returned, tx.playerName, tx.id, "没送到（掉落物没存下来），退回账户"));
            }
        }
    }

    private static void readDrops(Tx t, JsonObject x) {
        if (x.has("drop")) {
            JsonObject d = x.getAsJsonObject("drop");
            t.dropDim = d.get("dim").getAsInt();
            t.dropCx = d.get("cx").getAsInt();
            t.dropCz = d.get("cz").getAsInt();
            t.drops = PayJson.items(d.get("items"));
        }
    }

    private Account need(AccountRef ref) {
        Account a = accounts.get(ref);
        if (a == null) {
            a = new Account(ref, ref.isPlayer() ? null : ref.virtual);
            accounts.put(ref, a);
        }
        return a;
    }

    private Account needPlayer(UUID player, String name) {
        Account a = need(AccountRef.player(player));
        if (a.name == null) {
            a.name = name;
        }
        return a;
    }

    // ---------------------------------------------------------------- 快照

    private void prune(long now, long keepMillis) {
        Iterator<Order> it = orders.values().iterator();
        while (it.hasNext()) {
            Order o = it.next();
            boolean dead = (o.status == OrderStatus.OPEN || o.status == OrderStatus.CANCELED) && now - o.expiresAt > 24L * 3600 * 1000;
            boolean old = o.status == OrderStatus.PAID && now - o.paidAt > keepMillis;
            if (dead || old) {
                it.remove();
            }
        }
        payments.values().removeIf(p -> now - p.time > keepMillis);
    }

    JsonObject writeSnapshot() {
        JsonObject s = new JsonObject();
        JsonArray accs = new JsonArray();
        for (Account a : accounts.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("acct", a.ref.id());
            PayJson.put(o, "name", a.name);
            o.add("bal", PayJson.amount(a.balance()));
            PayJson.put(o, "hash", a.keyHash);
            PayJson.put(o, "prefix", a.keyPrefix);
            o.addProperty("keyAt", a.keyCreated);
            JsonArray rec = new JsonArray();
            for (PayRecord r : a.records) {
                JsonObject x = new JsonObject();
                x.addProperty("at", r.time);
                x.addProperty("kind", r.kind.name());
                x.add("amount", PayJson.amount(r.amount));
                PayJson.put(x, "who", r.counterparty);
                PayJson.put(x, "ref", r.ref);
                PayJson.put(x, "note", r.note);
                rec.add(x);
            }
            o.add("records", rec);
            accs.add(o);
        }
        s.add("accounts", accs);
        JsonArray os = new JsonArray();
        for (Order o : orders.values()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", o.id);
            x.addProperty("recv", o.receiver.id());
            x.addProperty("site", o.site);
            x.add("price", PayJson.amount(o.price));
            x.addProperty("unit", o.unit.code);
            PayJson.put(x, "desc", o.description);
            PayJson.put(x, "ref", o.reference);
            PayJson.put(x, "ret", o.returnUrl);
            PayJson.put(x, "cancel", o.cancelUrl);
            x.addProperty("at", o.createdAt);
            x.addProperty("exp", o.expiresAt);
            PayJson.put(x, "origin", o.origin);
            x.addProperty("status", o.status.name());
            x.addProperty("paidAt", o.paidAt);
            PayJson.put(x, "payer", o.payer == null ? null : o.payer.toString());
            PayJson.put(x, "payerName", o.payerName);
            PayJson.put(x, "payment", o.paymentId);
            os.add(x);
        }
        s.add("orders", os);
        JsonArray ps = new JsonArray();
        for (Payment p : payments.values()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", p.id);
            x.addProperty("order", p.orderId);
            x.addProperty("payer", p.payer.toString());
            PayJson.put(x, "payerName", p.payerName);
            x.addProperty("recv", p.receiver.id());
            PayJson.put(x, "site", p.site);
            PayJson.put(x, "ref", p.reference);
            x.add("amount", PayJson.amount(p.amount));
            x.add("refunded", PayJson.amount(p.refunded));
            x.addProperty("at", p.time);
            ps.add(x);
        }
        s.add("payments", ps);
        JsonArray txs = new JsonArray();
        for (Tx t : pending.values()) {
            JsonObject x = new JsonObject();
            x.addProperty("tx", t.id);
            x.addProperty("kind", t.kind.name());
            x.addProperty("player", t.player.toString());
            PayJson.put(x, "pname", t.playerName);
            x.addProperty("acct", t.account.id());
            PayJson.put(x, "order", t.orderId);
            PayJson.put(x, "payment", t.paymentId);
            x.add("take", PayJson.items(t.take));
            x.add("give", PayJson.items(t.give));
            x.add("value", PayJson.amount(t.value));
            PayJson.put(x, "note", t.note);
            x.addProperty("at", t.at);
            if (t.drops != null) {
                putDrops(x, new Drops(t.drops, t.dropDim, t.dropCx, t.dropCz));
            }
            txs.add(x);
        }
        s.add("pending", txs);
        return s;
    }

    private void readSnapshot(JsonObject s) {
        for (JsonElement el : s.getAsJsonArray("accounts")) {
            JsonObject o = el.getAsJsonObject();
            AccountRef ref = AccountRef.parse(o.get("acct").getAsString());
            Account a = new Account(ref, PayJson.str(o, "name"));
            a.add(PayJson.amount(o.get("bal")));
            a.keyHash = PayJson.str(o, "hash");
            a.keyPrefix = PayJson.str(o, "prefix");
            a.keyCreated = PayJson.num(o, "keyAt");
            if (a.keyHash != null) {
                keys.put(a.keyHash, ref);
            }
            for (JsonElement r : o.getAsJsonArray("records")) {
                JsonObject x = r.getAsJsonObject();
                a.records.addLast(new PayRecord(PayJson.num(x, "at"), PayRecord.Kind.valueOf(x.get("kind").getAsString()),
                        PayJson.amount(x.get("amount")), PayJson.str(x, "who"), PayJson.str(x, "ref"), PayJson.str(x, "note")));
            }
            accounts.put(ref, a);
        }
        for (JsonElement el : s.getAsJsonArray("orders")) {
            JsonObject x = el.getAsJsonObject();
            Order o = new Order();
            o.id = x.get("id").getAsString();
            o.receiver = AccountRef.parse(x.get("recv").getAsString());
            o.site = x.get("site").getAsString();
            o.price = PayJson.amount(x.get("price"));
            Currency.Unit u = Currency.Unit.of(PayJson.str(x, "unit"));
            o.unit = u == null ? Currency.Unit.EMERALD : u;
            o.description = PayJson.str(x, "desc");
            o.reference = PayJson.str(x, "ref");
            o.returnUrl = PayJson.str(x, "ret");
            o.cancelUrl = PayJson.str(x, "cancel");
            o.createdAt = PayJson.num(x, "at");
            o.expiresAt = PayJson.num(x, "exp");
            o.origin = PayJson.str(x, "origin");
            o.status = OrderStatus.valueOf(x.get("status").getAsString());
            o.paidAt = PayJson.num(x, "paidAt");
            String payer = PayJson.str(x, "payer");
            o.payer = payer == null ? null : UUID.fromString(payer);
            o.payerName = PayJson.str(x, "payerName");
            o.paymentId = PayJson.str(x, "payment");
            orders.put(o.id, o);
        }
        for (JsonElement el : s.getAsJsonArray("payments")) {
            JsonObject x = el.getAsJsonObject();
            Payment p = new Payment();
            p.id = x.get("id").getAsString();
            p.orderId = PayJson.str(x, "order");
            p.payer = UUID.fromString(x.get("payer").getAsString());
            p.payerName = PayJson.str(x, "payerName");
            p.receiver = AccountRef.parse(x.get("recv").getAsString());
            p.site = PayJson.str(x, "site");
            p.reference = PayJson.str(x, "ref");
            p.amount = PayJson.amount(x.get("amount"));
            p.refunded = PayJson.amount(x.get("refunded"));
            p.time = PayJson.num(x, "at");
            payments.put(p.id, p);
        }
        for (JsonElement el : s.getAsJsonArray("pending")) {
            JsonObject x = el.getAsJsonObject();
            Tx t = new Tx();
            t.id = x.get("tx").getAsString();
            t.kind = TxKind.valueOf(x.get("kind").getAsString());
            t.player = UUID.fromString(x.get("player").getAsString());
            t.playerName = PayJson.str(x, "pname");
            t.account = AccountRef.parse(x.get("acct").getAsString());
            t.orderId = PayJson.str(x, "order");
            t.paymentId = PayJson.str(x, "payment");
            t.take = PayJson.items(x.get("take"));
            t.give = PayJson.items(x.get("give"));
            t.value = PayJson.amount(x.get("value"));
            t.note = PayJson.str(x, "note");
            t.at = PayJson.num(x, "at");
            readDrops(t, x);
            pending.put(t.id, t);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static final char[] BASE32 = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    String newId(String prefix) {
        return prefix + randomText(16);
    }

    private String randomText(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(BASE32[random.nextInt(BASE32.length)]);
        }
        return sb.toString();
    }

    static String hashKey(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 测试用 */
    synchronized int orderCount() {
        return orders.size();
    }
}
