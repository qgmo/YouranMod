package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.DepositInfo;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PayItem;
import moe.qingu.youran.api.pay.PayRecord;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 充值（服主 11-18）：模组往虚拟账户余额里加钱和物品。时间是固定的数，随机数固定种子 */
class DepositTest {
    static final long T0 = VirtualPayTest.T0;
    static final AccountRef QING = VirtualPayTest.QING;
    static final AccountRef DADO = VirtualPayTest.DADO;
    static final PayItem DIAMOND = PayItem.of("minecraft:diamond", 0);

    @TempDir
    Path dir;

    @BeforeAll
    static void boot() {
        Bootstrap.register();
    }

    static Ledger open(Path d) throws Exception {
        Ledger l = new Ledger(new PayStore(d), new Random(11));
        l.load();
        return l;
    }

    Ledger open() throws Exception {
        Ledger l = open(dir);
        l.ensureVirtual(QING.virtual, "清");
        l.ensureVirtual(DADO.virtual, "dado");
        return l;
    }

    static PayException fails(String code, org.junit.jupiter.api.function.Executable x) {
        PayException e = assertThrows(PayException.class, x);
        assertEquals(code, e.code, e.getMessage());
        return e;
    }

    /** 充成：余额加上，记一条“充值”（记进账户、编号是 dep_…、说明照写），不发收款通知 */
    @Test
    void deposits() throws Exception {
        Ledger l = open();
        Amount a = Amount.of(10, java.util.Collections.singletonMap(DIAMOND, 3L));
        DepositInfo d = l.deposit(QING, a, "req-1", "充值 d01", T0);
        assertFalse(d.replayed);
        assertTrue(d.id.startsWith("dep_"), d.id);
        assertEquals(QING, d.account);
        assertEquals(a, d.amount);
        assertEquals(a, l.balance(QING));
        assertEquals(Amount.ZERO, l.balance(DADO), "别的账户不动");
        PayRecord r = l.records(QING, 1).get(0);
        assertSame(PayRecord.Kind.DEPOSIT, r.kind);
        assertEquals(1, r.sign(), "充值是记进账户");
        assertEquals(a, r.amount);
        assertEquals(d.id, r.ref);
        assertEquals("充值 d01", r.note);
        assertNull(r.counterparty);
        assertTrue(l.notices().isEmpty(), "充值不通知谁");
        // 再充一笔（不带编号）：加上去
        l.deposit(QING, Amount.yuan(5), null, null, T0 + 1);
        assertEquals(a.plus(Amount.yuan(5)), l.balance(QING));
        fails("invalid_amount", () -> l.deposit(QING, Amount.ZERO, "req-0", null, T0));
        assertThrows(IllegalArgumentException.class, () -> l.deposit(AccountRef.player(VirtualPayTest.ALICE), Amount.yuan(1), null, null, T0),
                "只有虚拟账户能充值");
        // 充进来的余额能付款
        Ledger.Order o = VirtualPayTest.dadoOrder(l, 4);
        l.payFromVirtual(QING, o.id, null, null, T0 + 2);
        assertEquals(Amount.of(7, java.util.Collections.singletonMap(DIAMOND, 3L)), l.balance(QING));
        l.close();
    }

    /** 绿宝石与悠然元（纸币、硬币）按价值折成元记；改过名（带 NBT）的绿宝石、钻石按物品分开记 */
    @Test
    void currencyFolds() throws Exception {
        Ledger l = open();
        Map<PayItem, Long> things = new LinkedHashMap<>();
        things.put(Currency.Denomination.EMERALD.item, 3L);
        things.put(Currency.Denomination.TEN.item, 2L);
        things.put(Currency.Denomination.FIVE.item, 1L);
        things.put(Currency.Denomination.ONE_NOTE.item, 1L);
        things.put(Currency.Denomination.ONE_COIN.item, 2L);
        PayItem named = McWorld.itemOf(new PayItem("minecraft:emerald", 0, "{display:{Name:\"x\"}}", null).toStack(1));
        things.put(named, 1L);
        things.put(DIAMOND, 2L);
        DepositInfo d = l.deposit(QING, PayServer.depositable(Amount.ofItems(things)), null, null, T0);
        // 3×2 + 2×10 + 5 + 1 + 2×1 = 34 元
        assertEquals(34, d.amount.yuan);
        assertEquals(2, d.amount.items.size());
        assertEquals(2L, d.amount.count(DIAMOND));
        assertEquals(1L, d.amount.count(named), "带 NBT 的绿宝石不是钱");
        assertEquals(d.amount, l.balance(QING));
        l.close();
    }

    /** 物品规则同付款：黑名单里的、装着东西的容器、没有的物品拒，什么都不记；黑名单不管钱 */
    @Test
    void itemRules() throws Exception {
        String[] saved = PayConfig.blacklist;
        PayConfig.blacklist = new String[]{"minecraft:written_book", "minecraft:wool@14", "minecraft:emerald"};
        try {
            Ledger l = open();
            long size = Files.size(dir.resolve(PayStore.JOURNAL));
            PayException e = fails("item_not_allowed", () -> PayServer.depositable(Amount.item(PayItem.of("minecraft:written_book", 0), 1)));
            assertTrue(e.getMessage().contains("不能充值"), e.getMessage());
            fails("item_not_allowed", () -> PayServer.depositable(Amount.of(4, java.util.Collections.singletonMap(PayItem.of("minecraft:wool", 14), 1L))));
            // 装着东西的潜影盒拒，空的收
            PayItem full = new PayItem("minecraft:purple_shulker_box", 0, "{BlockEntityTag:{Items:[{Count:1b,Slot:0b,Damage:0s,id:\"minecraft:dirt\"}]}}", null);
            fails("item_not_allowed", () -> PayServer.depositable(Amount.item(full, 1)));
            fails("invalid_item", () -> PayServer.depositable(Amount.item(PayItem.of("minecraft:no_such_thing", 0), 1)));
            fails("invalid_amount", () -> PayServer.depositable(Amount.ZERO));
            assertEquals(size, Files.size(dir.resolve(PayStore.JOURNAL)), "拒了的没写账本");
            assertEquals(Amount.ZERO, l.balance(QING));

            Map<PayItem, Long> ok = new LinkedHashMap<>();
            ok.put(Currency.Denomination.EMERALD.item, 5L);
            ok.put(PayItem.of("minecraft:wool", 13), 2L);
            ok.put(PayItem.of("minecraft:purple_shulker_box", 0), 1L);
            Amount checked = PayServer.depositable(Amount.ofItems(ok));
            assertEquals(10, checked.yuan, "绿宝石在黑名单里也照样是钱");
            assertEquals(2L, checked.count(PayItem.of("minecraft:wool", 13)));
            assertEquals(1L, checked.count(PayItem.of("minecraft:purple_shulker_box", 0)), "空的潜影盒照收");
            l.deposit(QING, checked, null, null, T0);
            assertEquals(checked, l.balance(QING));
            l.close();
        } finally {
            PayConfig.blacklist = saved;
        }
    }

    /** 同一个请求编号只入账一次：返回第一次的；金额不一样拒；别的账户的编号分开算；关了再开、写快照以后照样认得 */
    @Test
    void requestIdReplay() throws Exception {
        Ledger l = open();
        Amount a = Amount.of(8, java.util.Collections.singletonMap(DIAMOND, 1L));
        DepositInfo first = l.deposit(QING, a, "yrqing-dep-d1", "充值 d1", T0);
        DepositInfo again = l.deposit(QING, a, "yrqing-dep-d1", "充值 d1", T0 + 5000);
        assertTrue(again.replayed);
        assertEquals(first.id, again.id);
        assertEquals(T0, again.time, "第一次的那笔");
        assertEquals(a, l.balance(QING), "只入账一次");
        assertEquals(1, countDeposits(l), "只有一条充值记录");
        fails("request_id_reused", () -> l.deposit(QING, Amount.yuan(8), "yrqing-dep-d1", null, T0));
        assertEquals(first.id, l.deposited(QING, "yrqing-dep-d1").id);
        assertNull(l.deposited(QING, "yrqing-dep-d2"));
        assertNull(l.deposited(DADO, "yrqing-dep-d1"), "编号按账户分");
        assertFalse(l.deposit(DADO, a, "yrqing-dep-d1", null, T0).replayed);
        l.close();

        Ledger r = open(dir);
        assertTrue(r.deposit(QING, a, "yrqing-dep-d1", null, T0).replayed, "重开以后（照日志）还认得");
        assertEquals(a, r.balance(QING));
        r.snapshotNow(T0 + 1000, Long.MAX_VALUE / 4);
        r.close();
        Ledger s = open(dir);
        DepositInfo d = s.deposited(QING, "yrqing-dep-d1");
        assertNotNull(d, "写快照以后还认得");
        assertEquals(first.id, d.id);
        assertEquals(a, d.amount);
        assertEquals("充值 d1", d.note);
        assertTrue(s.deposit(QING, a, "yrqing-dep-d1", null, T0).replayed);
        assertEquals(a, s.balance(QING));
        assertSame(PayRecord.Kind.DEPOSIT, s.records(QING, 1).get(0).kind, "收支记录也在快照里");
        // 保留期过了的编号随快照清掉（同付款记录），余额不动
        s.snapshotNow(T0 + 10_000, 5_000);
        assertNull(s.deposited(QING, "yrqing-dep-d1"));
        assertEquals(a, s.balance(QING));
        s.close();
    }

    /**
     * 在每一步崩：写不进去（磁盘满）、写了半行就死了、写盘以后改内存以前死了、正常写完。重开以后：要么充了一次要么没充，
     * {@code deposited} 查到的就是实情；同一个编号重发总共只入账一次。
     */
    @Test
    void crashAtEveryStep() throws Exception {
        PayStore.Fault[] faults = {PayStore.Fault.FAIL_BEFORE_WRITE, PayStore.Fault.CRASH_HALF_WRITTEN, PayStore.Fault.CRASH_AFTER_FSYNC,
                PayStore.Fault.NONE};
        Amount a = Amount.yuan(12);
        for (PayStore.Fault f : faults) {
            Path d = dir.resolve("crash-" + f);
            PayStore store = new PayStore(d);
            Ledger l = new Ledger(store, new Random(3));
            l.load();
            l.ensureVirtual(QING.virtual, "清");
            l.deposit(QING, Amount.yuan(1), null, null, T0);
            store.fault = f;
            try {
                l.deposit(QING, a, "req-" + f, null, T0 + 1);
            } catch (PayStore.Crash c) {
                assertTrue(f == PayStore.Fault.CRASH_HALF_WRITTEN || f == PayStore.Fault.CRASH_AFTER_FSYNC, f.toString());
            } catch (java.io.IOException e) {
                assertEquals(PayStore.Fault.FAIL_BEFORE_WRITE, f);
                assertEquals(Amount.yuan(1), l.balance(QING), "写不进去：内存里也没加");
                assertNull(l.deposited(QING, "req-" + f));
            }
            l.close();
            Ledger r = open(d);
            boolean in = f == PayStore.Fault.CRASH_AFTER_FSYNC || f == PayStore.Fault.NONE;
            assertEquals(in, r.deposited(QING, "req-" + f) != null, f.toString());
            assertEquals(in ? Amount.yuan(13) : Amount.yuan(1), r.balance(QING), f.toString());
            DepositInfo retry = r.deposit(QING, a, "req-" + f, null, T0 + 2);
            assertEquals(in, retry.replayed, f + "：重发同一个编号");
            assertEquals(Amount.yuan(13), r.balance(QING), f + "：总共只入账一次");
            r.close();
            Ledger s = open(d);
            assertEquals(Amount.yuan(13), s.balance(QING), f + "：再开一次还是一样");
            s.close();
        }
    }

    static int countDeposits(Ledger l) {
        int n = 0;
        for (PayRecord r : l.records(QING, 200)) {
            if (r.kind == PayRecord.Kind.DEPOSIT) {
                n++;
            }
        }
        return n;
    }
}
