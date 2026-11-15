package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PayItem;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 动背包的付款、提现（主线程）。每一笔：
 * <ol>
 * <li>账本记“开始”（要拿的、要给的；提现、用余额付款的那部分这时就从账户扣掉）；</li>
 * <li>改背包（只改内存）并在玩家数据里加这笔的标记；放不下的先在账本记下掉在哪个区块，再掉在脚边（带标记、暂时不能捡）；</li>
 * <li>把玩家数据写盘、把掉落物所在的区块写盘；</li>
 * <li><b>读文件核对</b>：玩家存档里有这笔的标记（背包的改动存下来了）、区块存档里的掉落物和掉出来的一样；</li>
 * <li>账本记“结束”（付款：记进收款方账户），去掉标记，掉落物放开可以捡。</li>
 * </ol>
 * 第 4 步对不上（写盘失败、读不了）时不记“结束”，这笔算“处理中”，每秒重试；关服前还没好的，下次开服由 {@link #recover} 照存档里的样子补。
 * 所以账本与存档总是一致的：崩在第 1–3 步之间，存档里没有标记，按没发生（付款作废、提现退回）；崩在第 3–5 步之间，存档里有标记，按发生了。
 */
final class TxRunner {
    /** 一笔的结果 */
    static final class Result {
        enum Status {
            DONE, FAILED, PROCESSING
        }

        final Status status;
        final String txId;
        final Ledger.Paid paid;
        final PayException error;

        Result(Status status, String txId, Ledger.Paid paid, PayException error) {
            this.status = status;
            this.txId = txId;
            this.paid = paid;
            this.error = error;
        }

        static Result failed(PayException e) {
            return new Result(Status.FAILED, null, null, e);
        }
    }

    /** 还在“处理中”的一笔（运行时） */
    private static final class Live {
        final Ledger.Tx tx;
        /** 实际掉出来的 */
        Map<PayItem, Long> spawned = Collections.emptyMap();
        int attempts;

        Live(Ledger.Tx tx) {
            this.tx = tx;
        }
    }

    private final Ledger ledger;
    private final WorldPort world;
    private final LongSupplier clock;
    private final Consumer<Ledger.Paid> onPaid;
    private final Map<String, Live> live = new LinkedHashMap<>();

    TxRunner(Ledger ledger, WorldPort world, LongSupplier clock, Consumer<Ledger.Paid> onPaid) {
        this.ledger = ledger;
        this.world = world;
        this.clock = clock;
        this.onPaid = onPaid;
    }

    /** 付款：从 {@code payer} 背包拿 {@code take}、找 {@code change}，价格记进收款方 */
    Result pay(Ledger.Order order, WorldPort.PlayerPort payer, Map<PayItem, Long> take, Map<PayItem, Long> change) {
        return pay(order, payer, take, change, Amount.ZERO);
    }

    /**
     * 付款，其中 {@code fromBalance} 从付款人的 pay.yr 账户余额里出（“开始”时从账户扣掉；作废、崩溃恢复时按没付退回账户），
     * 其余从背包拿。只用余额付（背包什么都不拿）时也走同样的标记、存盘、核对，崩溃恢复照同一套规则。
     */
    Result pay(Ledger.Order order, WorldPort.PlayerPort payer, Map<PayItem, Long> take, Map<PayItem, Long> change, Amount fromBalance) {
        Ledger.Drops drops = drops(payer, take, change);
        Ledger.Tx tx;
        try {
            tx = ledger.beginPay(order, payer.id(), payer.name(), take, change, fromBalance, drops, clock.getAsLong());
        } catch (PayException e) {
            return Result.failed(e);
        } catch (IOException e) {
            Ledger.LOG.error("pay.yr：写账本失败，付款没做", e);
            return Result.failed(new PayException("storage", "pay.yr 记账失败，没有扣东西，请稍后再试"));
        }
        if (!payer.take(take)) {
            abortQuietly(tx, "背包里的东西不够了");
            return Result.failed(new PayException("insufficient_items", "背包里的东西变了，不够付了"));
        }
        return deliver(tx, payer, change);
    }

    /** 提现：从账户扣 {@code value}，物品 {@code give} 给 {@code player} */
    Result withdraw(AccountRef account, WorldPort.PlayerPort player, Amount value, Map<PayItem, Long> give, String note) {
        Ledger.Drops drops = drops(player, Collections.<PayItem, Long>emptyMap(), give);
        Ledger.Tx tx;
        try {
            tx = ledger.beginWithdraw(account, player.id(), player.name(), value, give, drops, note, clock.getAsLong());
        } catch (PayException e) {
            return Result.failed(e);
        } catch (IOException e) {
            Ledger.LOG.error("pay.yr：写账本失败，提现没做", e);
            return Result.failed(new PayException("storage", "pay.yr 记账失败，没有扣账户，请稍后再试"));
        }
        return deliver(tx, player, give);
    }

    /** 放不下背包的先算好，和在哪个区块一起写进“开始” */
    private static Ledger.Drops drops(WorldPort.PlayerPort p, Map<PayItem, Long> take, Map<PayItem, Long> give) {
        if (give.isEmpty()) {
            return null;
        }
        Map<PayItem, Long> over = p.overflow(take, give);
        return over.isEmpty() ? null : new Ledger.Drops(over, p.dimension(), p.chunkX(), p.chunkZ());
    }

    private Result deliver(Ledger.Tx tx, WorldPort.PlayerPort p, Map<PayItem, Long> give) {
        Live l = new Live(tx);
        live.put(tx.id, l);
        p.mark(tx.id);
        // 背包那部分放进去；按计划放得下（同一 tick 里算的），万一还有放不下的，和计划掉的一起掉
        Map<PayItem, Long> rest = tx.inventoryPart().isEmpty() ? Collections.<PayItem, Long>emptyMap() : p.add(tx.inventoryPart());
        Map<PayItem, Long> toDrop = new LinkedHashMap<>(rest);
        if (tx.drops != null) {
            for (Map.Entry<PayItem, Long> e : tx.drops.entrySet()) {
                toDrop.merge(e.getKey(), e.getValue(), Math::addExact);
            }
        }
        if (!toDrop.isEmpty()) {
            if (tx.drops == null) {
                // 计划里没有掉落：只能当场决定掉在这里（极少见），记在内存里
                tx.drops = new LinkedHashMap<>();
                tx.dropDim = p.dimension();
                tx.dropCx = p.chunkX();
                tx.dropCz = p.chunkZ();
            }
            l.spawned = p.drop(toDrop, tx.id);
        }
        p.persist();
        return finish(l, p);
    }

    /** 写盘、核对、记结束；对不上时算“处理中” */
    private Result finish(Live l, WorldPort.PlayerPort online) {
        Ledger.Tx tx = l.tx;
        l.attempts++;
        try {
            if (online != null && l.attempts > 1) {
                online.persist();
            }
            if (!world.markerOnDisk(tx.player, tx.id)) {
                return processing(l, "玩家存档里还没有这笔的标记");
            }
            Map<PayItem, Long> dropped = Collections.emptyMap();
            if (tx.drops != null) {
                world.persistChunk(tx.dropDim, tx.dropCx, tx.dropCz);
                dropped = world.dropsOnDisk(tx.dropDim, tx.dropCx, tx.dropCz, tx.id);
                if (!covers(dropped, l.spawned)) {
                    // 存下来的和掉出来的不一样：都收回，按没送到（结束时记回账户）
                    world.killDrops(tx.dropDim, tx.dropCx, tx.dropCz, tx.id);
                    world.persistChunk(tx.dropDim, tx.dropCx, tx.dropCz);
                    if (!world.dropsOnDisk(tx.dropDim, tx.dropCx, tx.dropCz, tx.id).isEmpty()) {
                        return processing(l, "区块存档里的掉落物收不回来");
                    }
                    l.spawned = Collections.emptyMap();
                    dropped = Collections.emptyMap();
                }
            }
            Ledger.Paid paid = ledger.commit(tx, true, dropped, clock.getAsLong());
            live.remove(tx.id);
            WorldPort.PlayerPort p = online != null ? online : world.player(tx.player);
            if (p != null) {
                p.unmark(tx.id);
            }
            if (tx.drops != null && !dropped.isEmpty()) {
                world.releaseDrops(tx.dropDim, tx.dropCx, tx.dropCz, tx.id);
            }
            if (paid != null && onPaid != null) {
                onPaid.accept(paid);
            }
            return new Result(Result.Status.DONE, tx.id, paid, null);
        } catch (IOException | RuntimeException e) {
            Ledger.LOG.warn("pay.yr：{} 第 {} 次核对存盘失败，稍后重试：{}", tx.id, l.attempts, e.toString());
            return processing(l, e.toString());
        }
    }

    /** {@code disk} 里每样都不少于 {@code spawned}（多出来的不算：同一个区块里不会有别的带这笔标记的） */
    private static boolean covers(Map<PayItem, Long> disk, Map<PayItem, Long> spawned) {
        if (disk.size() != spawned.size()) {
            return false;
        }
        for (Map.Entry<PayItem, Long> e : spawned.entrySet()) {
            if (!e.getValue().equals(disk.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private Result processing(Live l, String why) {
        if (l.attempts == 1 || l.attempts % 60 == 0) {
            Ledger.LOG.warn("pay.yr：{}（{}）还没确认存盘：{}；每秒重试，关服后下次开服按存档补上", l.tx.id, l.tx.kind, why);
        }
        return new Result(Result.Status.PROCESSING, l.tx.id, null, null);
    }

    /** 主线程，每秒：重试还在“处理中”的 */
    void tick() {
        for (Live l : new java.util.ArrayList<>(live.values())) {
            finish(l, world.player(l.tx.player));
        }
    }

    /** 这一笔还在“处理中” */
    boolean processing(String txId) {
        return live.containsKey(txId);
    }

    /** 关服：运行时的“处理中”交给下次开服的 {@link #recover} */
    void clear() {
        live.clear();
    }

    private void abortQuietly(Ledger.Tx tx, String why) {
        try {
            ledger.abort(tx, why, clock.getAsLong());
        } catch (IOException e) {
            // 留在账本里“进行中”，下次开服按存档（没有标记）作废
            Ledger.LOG.error("pay.yr：写账本失败，{} 留到下次开服再作废", tx.id, e);
        }
    }

    /**
     * 开服时（还没有玩家上线、世界还没走过 tick）：账本里还没结束的，照存档补上结束。
     * 付款：玩家存档里有标记 → 算付了（找零的掉落物少了的记进付款人账户）；没有 → 作废（掉出来的找零删掉）。
     * 提现：背包那部分、掉落物各自照存档，没送到的退回账户。读不了存档的留着，下次开服再看（期间收款单保持“正在付”、提现的钱保持扣着）。
     */
    int recover() {
        int n = 0;
        for (Ledger.Tx tx : ledger.pending()) {
            try {
                boolean inv = world.markerOnDisk(tx.player, tx.id);
                Map<PayItem, Long> found = Collections.emptyMap();
                if (tx.drops != null) {
                    found = world.dropsOnDisk(tx.dropDim, tx.dropCx, tx.dropCz, tx.id);
                }
                if (tx.kind == Ledger.TxKind.PAY && !inv) {
                    if (!found.isEmpty()) {
                        world.killDrops(tx.dropDim, tx.dropCx, tx.dropCz, tx.id);
                        world.persistChunk(tx.dropDim, tx.dropCx, tx.dropCz);
                        if (!world.dropsOnDisk(tx.dropDim, tx.dropCx, tx.dropCz, tx.id).isEmpty()) {
                            Ledger.LOG.error("pay.yr：恢复 {} 时删不掉找零的掉落物，留到下次开服", tx.id);
                            continue;
                        }
                    }
                    ledger.abort(tx, "崩溃恢复：玩家存档里没有这笔（没扣成）", clock.getAsLong());
                    Ledger.LOG.warn("pay.yr：崩溃恢复：{}（{} 付 {}）没扣成，作废，收款单可以再付", tx.id, tx.playerName, tx.orderId);
                } else {
                    Ledger.Paid paid = ledger.commit(tx, inv, found, clock.getAsLong());
                    if (!found.isEmpty()) {
                        world.releaseDrops(tx.dropDim, tx.dropCx, tx.dropCz, tx.id);
                    }
                    Ledger.LOG.warn("pay.yr：崩溃恢复：{}（{}，{}）照存档补上：背包那部分{}存下来，掉落物存下来 {}", tx.id, tx.kind, tx.playerName,
                            inv ? "" : "没有", found);
                    if (paid != null && onPaid != null) {
                        onPaid.accept(paid);
                    }
                }
                n++;
            } catch (IOException | RuntimeException e) {
                Ledger.LOG.error("pay.yr：恢复 {} 时读不了存档，留到下次开服再看", tx.id, e);
            }
        }
        return n;
    }
}
