package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.Currency.Denomination;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.PayItem;
import moe.qingu.youran.api.pay.PayRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TxRunnerTest {
    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final PayItem DIAMOND = PayItem.of("minecraft:diamond", 0);
    static final long T0 = 1_763_200_000_000L;
    /** 走一笔付款 / 提现时可能崩的每一步 */
    static final String[] STEPS = {"take", "mark", "add", "drop", "persist", "persist-done", "chunk", "chunk-done", "verify", "unmark", "release"};

    @TempDir
    Path tmp;

    Path dir;
    Ledger ledger;
    FakeWorld world;
    TxRunner runner;
    final List<Ledger.Paid> paid = new ArrayList<>();

    void setUp(String name) throws Exception {
        dir = tmp.resolve(name);
        Files.createDirectories(dir);
        ledger = new Ledger(new PayStore(dir), new Random(3));
        ledger.load();
        world = new FakeWorld();
        paid.clear();
        runner = new TxRunner(ledger, world, () -> T0, paid::add);
        ledger.touchPlayer(BOB, "Bob");
    }

    /** 开一张收款单：价格 {@code yuan} 元 + {@code diamonds} 颗钻石 */
    Ledger.Order order(long yuan, long diamonds) throws Exception {
        Ledger.Draft d = LedgerTest.draft(AccountRef.player(BOB), yuan, T0 + 600_000);
        d.price = Amount.of(yuan, diamonds == 0 ? Collections.<PayItem, Long>emptyMap() : Collections.singletonMap(DIAMOND, diamonds));
        return ledger.createOrder(d, T0);
    }

    /** Alice 上线，背包里有这些（也写进存档） */
    FakeWorld.FakePlayer alice(Map<PayItem, Long> inv, int capacity) {
        world.diskInv.put(ALICE, new LinkedHashMap<>(inv));
        return world.join(ALICE, "Alice", capacity);
    }

    static Map<PayItem, Long> inv(Object... kv) {
        Map<PayItem, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((PayItem) kv[i], ((Number) kv[i + 1]).longValue());
        }
        return m;
    }

    TxRunner.Result pay(Ledger.Order o, FakeWorld.FakePlayer p, CurrencyPlan.Prefer prefer) {
        CurrencyPlan.PayPlan plan = CurrencyPlan.planPayment(o.price, p.counts(), prefer);
        assertTrue(plan.ok());
        return runner.pay(ledger.order(o.id), p, plan.take, plan.change);
    }

    /** 付款：背包扣对（混着付、找零放回背包），收款方记进价格，回调一次 */
    @Test
    void payMixedWithChange() throws Exception {
        setUp("pay");
        Ledger.Order o = order(23, 2);
        FakeWorld.FakePlayer a = alice(inv(Denomination.EMERALD.item, 5, Denomination.TEN.item, 2, DIAMOND, 3), 1000);
        TxRunner.Result r = pay(o, a, CurrencyPlan.Prefer.EMERALD);
        assertSame(TxRunner.Result.Status.DONE, r.status);
        assertSame(OrderInfo.Status.PAID, ledger.order(o.id).status(T0));
        assertEquals(Amount.of(23, Collections.singletonMap(DIAMOND, 2L)), ledger.balance(AccountRef.player(BOB)));
        // 背包：原来 10 + 20 = 30 元 + 3 钻石，付 23 元 + 2 钻石，剩 7 元价值 + 1 钻石
        assertEquals(Amount.of(7, Collections.singletonMap(DIAMOND, 1L)), Amount.ofItems(a.inv));
        assertEquals(a.inv, world.diskInv.get(ALICE), "已经存盘");
        assertTrue(a.markers.isEmpty(), "结束后去掉标记");
        assertEquals(1, paid.size());
        assertTrue(ledger.pending().isEmpty());
    }

    /** 找零放不下背包：掉在脚边（带标记、存盘核对过才放开） */
    @Test
    void changeOverflowDrops() throws Exception {
        setUp("drop");
        Ledger.Order o = order(3, 0);
        // 背包只能放 2 个：一张十元付掉，找 7 元（五元 + 两个一元，3 个东西）只放得下 2 个
        FakeWorld.FakePlayer a = alice(inv(Denomination.TEN.item, 1, DIAMOND, 1), 2);
        TxRunner.Result r = pay(o, a, CurrencyPlan.Prefer.YUAN);
        assertSame(TxRunner.Result.Status.DONE, r.status);
        assertEquals(Amount.of(7, Collections.singletonMap(DIAMOND, 1L)), Amount.ofItems(a.inv).plus(Amount.ofItems(world.allDiskDrops())));
        assertFalse(world.allDiskDrops().isEmpty());
        assertTrue(world.released.contains(r.txId));
        assertTrue(ledger.balance(AccountRef.player(ALICE)).isZero());
    }

    /** 掉落物生不出来（保护插件拦了）：找零记进付款人的 pay.yr 账户 */
    @Test
    void changeNotSpawnedGoesToAccount() throws Exception {
        setUp("nospawn");
        Ledger.Order o = order(3, 0);
        FakeWorld.FakePlayer a = alice(inv(Denomination.TEN.item, 1, DIAMOND, 1), 2);
        world.spawnFails = true;
        TxRunner.Result r = pay(o, a, CurrencyPlan.Prefer.YUAN);
        assertSame(TxRunner.Result.Status.DONE, r.status);
        Amount acct = ledger.balance(AccountRef.player(ALICE));
        assertEquals(7, Amount.ofItems(a.inv).plus(acct).yuan);
        assertFalse(acct.isZero());
        assertEquals(PayRecord.Kind.CHANGE, ledger.records(AccountRef.player(ALICE), 1).get(0).kind);
    }

    /** 确认以后背包里的东西变了（被拿走了）：作废，什么都不扣，收款单还能付 */
    @Test
    void itemsGoneAborts() throws Exception {
        setUp("gone");
        Ledger.Order o = order(4, 0);
        FakeWorld.FakePlayer a = alice(inv(Denomination.EMERALD.item, 2), 100);
        CurrencyPlan.PayPlan plan = CurrencyPlan.planPayment(o.price, a.counts(), CurrencyPlan.Prefer.EMERALD);
        a.inv.clear();
        TxRunner.Result r = runner.pay(ledger.order(o.id), a, plan.take, plan.change);
        assertSame(TxRunner.Result.Status.FAILED, r.status);
        assertEquals("insufficient_items", r.error.code);
        assertSame(OrderInfo.Status.OPEN, ledger.order(o.id).status(T0));
        assertTrue(ledger.pending().isEmpty());
    }

    /** 提现：要绿宝石 / 要元；放不下的掉在脚边；余额对 */
    @Test
    void withdraw() throws Exception {
        setUp("withdraw");
        Ledger.Order o = order(41, 0);
        FakeWorld.FakePlayer a = alice(inv(Denomination.TEN.item, 5), 1000);
        pay(o, a, CurrencyPlan.Prefer.YUAN);
        FakeWorld.FakePlayer b = world.join(BOB, "Bob", 1000);
        TxRunner.Result r = runner.withdraw(AccountRef.player(BOB), b, Amount.yuan(41), CurrencyPlan.split(41, Currency.Unit.EMERALD), "测试");
        assertSame(TxRunner.Result.Status.DONE, r.status);
        assertEquals(Long.valueOf(20), b.inv.get(Denomination.EMERALD.item));
        assertEquals(Long.valueOf(1), b.inv.get(Denomination.ONE_NOTE.item));
        assertTrue(ledger.balance(AccountRef.player(BOB)).isZero());
        assertEquals(PayRecord.Kind.WITHDRAW, ledger.records(AccountRef.player(BOB), 1).get(0).kind);
        // 余额不够
        TxRunner.Result bad = runner.withdraw(AccountRef.player(BOB), b, Amount.yuan(1), CurrencyPlan.split(1, Currency.Unit.YUAN), null);
        assertSame(TxRunner.Result.Status.FAILED, bad.status);
        assertEquals("insufficient_balance", bad.error.code);
    }

    /** 存盘核对不上（写盘失败）：不记结束，算“处理中”；好了以后重试补上 */
    @Test
    void persistFailureRetries() throws Exception {
        setUp("retry");
        Ledger.Order o = order(4, 0);
        FakeWorld.FakePlayer a = alice(inv(Denomination.EMERALD.item, 2), 100);
        world.persistFails = true;
        TxRunner.Result r = pay(o, a, CurrencyPlan.Prefer.EMERALD);
        assertSame(TxRunner.Result.Status.PROCESSING, r.status);
        assertTrue(runner.processing(r.txId));
        assertSame(OrderInfo.Status.PAYING, ledger.order(o.id).status(T0));
        assertTrue(ledger.balance(AccountRef.player(BOB)).isZero(), "没确认存盘前不记进收款方");
        runner.tick();
        assertTrue(runner.processing(r.txId));
        world.persistFails = false;
        runner.tick();
        assertFalse(runner.processing(r.txId));
        assertSame(OrderInfo.Status.PAID, ledger.order(o.id).status(T0));
        assertEquals(Amount.yuan(4), ledger.balance(AccountRef.player(BOB)));
        assertEquals(1, paid.size());
    }

    /** 区块写不进去：掉落物收回、找零记进账户 */
    @Test
    void chunkFailureRetries() throws Exception {
        setUp("chunkfail");
        Ledger.Order o = order(3, 0);
        FakeWorld.FakePlayer a = alice(inv(Denomination.TEN.item, 1, DIAMOND, 1), 2);
        world.chunkPersistFails = true;
        TxRunner.Result r = pay(o, a, CurrencyPlan.Prefer.YUAN);
        assertSame(TxRunner.Result.Status.PROCESSING, r.status);
        world.chunkPersistFails = false;
        runner.tick();
        assertFalse(runner.processing(r.txId));
        assertEquals(7, Amount.ofItems(a.inv).plus(Amount.ofItems(world.allDiskDrops())).plus(ledger.balance(AccountRef.player(ALICE))).yuan);
    }

    // ---------------------------------------------------------------- 崩溃恢复

    /** Alice 的东西总数（存档里的背包 + 存档里的掉落物 + pay.yr 账户）+ Bob 账户，崩了重开以后还是原来那么多 */
    private Amount total(FakeWorld w, Ledger l) {
        return Amount.ofItems(w.diskInv.getOrDefault(ALICE, Collections.<PayItem, Long>emptyMap()))
                .plus(Amount.ofItems(w.allDiskDrops()))
                .plus(l.balance(AccountRef.player(ALICE)))
                .plus(l.balance(AccountRef.player(BOB)));
    }

    /** 付款在每一步崩：重开后按存档补上，不多不少；收款单要么付了（收款方记进价格）要么还能付 */
    @Test
    void payCrashAtEveryStep() throws Exception {
        for (String step : STEPS) {
            setUp("paycrash-" + step);
            Ledger.Order o = order(3, 1);
            Map<PayItem, Long> start = inv(Denomination.TEN.item, 1, DIAMOND, 2);
            Amount before = Amount.ofItems(start);
            FakeWorld.FakePlayer a = alice(start, 3);
            world.crashAt = step;
            CurrencyPlan.PayPlan plan = CurrencyPlan.planPayment(o.price, a.counts(), CurrencyPlan.Prefer.YUAN);
            boolean crashed = false;
            try {
                runner.pay(ledger.order(o.id), a, plan.take, plan.change);
            } catch (FakeWorld.Crash c) {
                crashed = true;
            }
            ledger.close();
            FakeWorld w2 = world.restart();
            Ledger l2 = new Ledger(new PayStore(dir), new Random(4));
            l2.load();
            new TxRunner(l2, w2, () -> T0, null).recover();
            assertTrue(l2.pending().isEmpty(), step + "：恢复后没有没结束的");
            assertEquals(before, total(w2, l2), step + "：东西不多不少");
            OrderInfo.Status st = l2.order(o.id).status(T0);
            if (st == OrderInfo.Status.PAID) {
                assertEquals(o.price, l2.balance(AccountRef.player(BOB)), step);
                assertEquals(1, Amount.ofItems(w2.diskInv.get(ALICE)).plus(Amount.ofItems(w2.allDiskDrops())).count(DIAMOND), step);
            } else {
                assertSame(OrderInfo.Status.OPEN, st, step);
                assertEquals(before, Amount.ofItems(w2.diskInv.get(ALICE)), step + "：没付成时背包原样");
                assertTrue(w2.allDiskDrops().isEmpty(), step + "：没付成时找零的掉落物删掉");
            }
            assertTrue(crashed, step + " 应该走到（这笔的找零有一部分要掉在脚边）");
            l2.close();
        }
    }

    /** 提现在每一步崩：送到的不退、没送到的退回账户，不多不少 */
    @Test
    void withdrawCrashAtEveryStep() throws Exception {
        for (String step : STEPS) {
            setUp("wdcrash-" + step);
            Ledger.Order o = order(37, 0);
            FakeWorld.FakePlayer a = alice(inv(Denomination.TEN.item, 4), 100);
            pay(o, a, CurrencyPlan.Prefer.YUAN);
            Amount bobBefore = ledger.balance(AccountRef.player(BOB));
            world.diskInv.put(BOB, new LinkedHashMap<PayItem, Long>());
            FakeWorld.FakePlayer b = world.join(BOB, "Bob", 4);
            world.crashAt = step;
            try {
                runner.withdraw(AccountRef.player(BOB), b, Amount.yuan(37), CurrencyPlan.split(37, Currency.Unit.YUAN), null);
            } catch (FakeWorld.Crash c) {
                // 崩了
            }
            ledger.close();
            FakeWorld w2 = world.restart();
            Ledger l2 = new Ledger(new PayStore(dir), new Random(5));
            l2.load();
            new TxRunner(l2, w2, () -> T0, null).recover();
            assertTrue(l2.pending().isEmpty(), step);
            Amount bobNow = Amount.ofItems(w2.diskInv.get(BOB)).plus(Amount.ofItems(w2.allDiskDrops())).plus(l2.balance(AccountRef.player(BOB)));
            assertEquals(bobBefore, bobNow, step + "：提现的东西不多不少");
            l2.close();
        }
    }

    /** 恢复读不了存档时留着，不乱结束 */
    @Test
    void recoverKeepsWhenUnreadable() throws Exception {
        setUp("unreadable");
        Ledger.Order o = order(4, 0);
        FakeWorld.FakePlayer a = alice(inv(Denomination.EMERALD.item, 2), 100);
        world.crashAt = "persist";
        assertThrows(FakeWorld.Crash.class, () -> pay(o, a, CurrencyPlan.Prefer.EMERALD));
        ledger.close();
        FakeWorld w2 = world.restart();
        w2.crashAt = "never";
        Ledger l2 = new Ledger(new PayStore(dir), new Random(6));
        l2.load();
        FakeWorld broken = new FakeWorld() {
            @Override
            public boolean markerOnDisk(UUID player, String tx) throws java.io.IOException {
                throw new java.io.IOException("读不了");
            }
        };
        assertEquals(0, new TxRunner(l2, broken, () -> T0, null).recover());
        assertEquals(1, l2.pending().size());
        assertSame(OrderInfo.Status.PAYING, l2.order(o.id).status(T0));
        assertEquals(1, new TxRunner(l2, w2, () -> T0, null).recover());
        assertSame(OrderInfo.Status.OPEN, l2.order(o.id).status(T0));
        assertNotNull(l2.order(o.id));
        l2.close();
    }
}
