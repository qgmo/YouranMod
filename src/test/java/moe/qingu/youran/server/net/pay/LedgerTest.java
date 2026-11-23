package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PayItem;
import moe.qingu.youran.api.pay.PayRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerTest {
    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final PayItem DIAMOND = PayItem.of("minecraft:diamond", 0);
    static final long T0 = 1_763_200_000_000L;

    @TempDir
    Path dir;

    Ledger open() throws Exception {
        Ledger l = new Ledger(new PayStore(dir), new Random(1));
        l.load();
        return l;
    }

    static Ledger.Draft draft(AccountRef to, long yuan, long expiresAt) {
        Ledger.Draft d = new Ledger.Draft();
        d.receiver = to;
        d.site = "shop.yr";
        d.price = Amount.of(yuan, Collections.singletonMap(DIAMOND, 1L));
        d.unit = Currency.Unit.EMERALD;
        d.description = "钻石剑";
        d.reference = "A-1";
        d.returnUrl = "https://shop.yr/done";
        d.expiresAt = expiresAt;
        d.origin = "api";
        return d;
    }

    /** 付一笔：开始 → 结束（不动世界，只看账） */
    static Ledger.Paid pay(Ledger l, Ledger.Order o, UUID payer, long now) throws Exception {
        Ledger.Tx tx = l.beginPay(o, payer, "Alice", Collections.singletonMap(DIAMOND, 1L), Collections.<PayItem, Long>emptyMap(), null, now);
        return l.commit(tx, true, null, now);
    }

    /** 收款单：只能付一次、过期不能付、取消、金额记对 */
    @Test
    void orderLifecycle() throws Exception {
        Ledger l = open();
        l.touchPlayer(BOB, "Bob");
        AccountRef bob = AccountRef.player(BOB);
        Ledger.Order o = l.createOrder(draft(bob, 40, T0 + 60_000), T0);
        assertSame(OrderInfo.Status.OPEN, o.status(T0));
        assertSame(OrderInfo.Status.EXPIRED, o.status(T0 + 60_000));
        assertEquals(1, l.openOrders(bob, T0));
        Ledger.Paid p = pay(l, o, ALICE, T0 + 1000);
        assertNotNull(p);
        assertSame(OrderInfo.Status.PAID, l.order(o.id).status(T0 + 999_999));
        assertEquals(Amount.of(40, Collections.singletonMap(DIAMOND, 1L)), l.balance(bob));
        assertEquals(o.price, l.payment(o.paymentId).amount);
        assertEquals(ALICE, l.payment(o.paymentId).payer);
        PayException again = assertThrows(PayException.class, () -> pay(l, l.order(o.id), ALICE, T0 + 2000));
        assertEquals("order_not_open", again.code);
        assertFalse(l.cancel(o.id, bob, T0 + 3000), "付过的不能取消");

        Ledger.Order late = l.createOrder(draft(bob, 10, T0 + 5000), T0);
        PayException expired = assertThrows(PayException.class, () -> pay(l, late, ALICE, T0 + 5000));
        assertEquals("order_expired", expired.code);

        Ledger.Order c = l.createOrder(draft(bob, 10, T0 + 60_000), T0);
        assertFalse(l.cancel(c.id, AccountRef.player(ALICE), T0), "只有收款人能取消");
        assertTrue(l.cancel(c.id, bob, T0));
        assertSame(OrderInfo.Status.CANCELED, l.order(c.id).status(T0));
        assertThrows(PayException.class, () -> pay(l, l.order(c.id), ALICE, T0 + 10));

        // 付款人那边记一笔“付款（从背包）”，余额不变
        List<PayRecord> ar = l.records(AccountRef.player(ALICE), 10);
        assertEquals(PayRecord.Kind.PAID, ar.get(0).kind);
        assertTrue(l.balance(AccountRef.player(ALICE)).isZero());
        assertEquals(PayRecord.Kind.RECEIVED, l.records(bob, 10).get(0).kind);
    }

    /** 中途作废：收款单回到可以付 */
    @Test
    void abortReopens() throws Exception {
        Ledger l = open();
        l.touchPlayer(BOB, "Bob");
        Ledger.Order o = l.createOrder(draft(AccountRef.player(BOB), 4, T0 + 60_000), T0);
        Ledger.Tx tx = l.beginPay(o, ALICE, "Alice", Collections.singletonMap(DIAMOND, 1L), Collections.<PayItem, Long>emptyMap(), null, T0);
        assertSame(OrderInfo.Status.PAYING, l.order(o.id).status(T0));
        PayException busy = assertThrows(PayException.class,
                () -> l.beginPay(l.createOrder(draft(AccountRef.player(BOB), 4, T0 + 60_000), T0), ALICE, "Alice",
                        Collections.<PayItem, Long>emptyMap(), Collections.<PayItem, Long>emptyMap(), null, T0));
        assertEquals("busy", busy.code, "同一个人上一笔没结束不能开新的");
        l.abort(tx, "测试", T0);
        assertSame(OrderInfo.Status.OPEN, l.order(o.id).status(T0));
        assertTrue(l.balance(AccountRef.player(BOB)).isZero());
    }

    /** 退款：部分、全部；超过还能退的、收款方余额不够（提现了）都退不了 */
    @Test
    void refunds() throws Exception {
        Ledger l = open();
        l.touchPlayer(BOB, "Bob");
        AccountRef bob = AccountRef.player(BOB);
        AccountRef alice = AccountRef.player(ALICE);
        Ledger.Order o = l.createOrder(draft(bob, 40, T0 + 60_000), T0);
        pay(l, o, ALICE, T0);
        String pid = l.order(o.id).paymentId;
        l.refund(pid, Amount.yuan(10), "部分", T0 + 1);
        assertEquals(Amount.of(30, Collections.singletonMap(DIAMOND, 1L)), l.balance(bob));
        assertEquals(Amount.yuan(10), l.balance(alice));
        assertEquals(Amount.yuan(10), l.payment(pid).refunded);
        PayException over = assertThrows(PayException.class, () -> l.refund(pid, Amount.yuan(31), null, T0 + 2));
        assertEquals("exceeds_refundable", over.code);
        // Bob 把钱提走了 25（模拟：开始提现并结束）
        Ledger.Tx w = l.beginWithdraw(bob, BOB, "Bob", Amount.yuan(25), CurrencyPlan.split(25, Currency.Unit.YUAN), null, null, T0 + 3);
        l.commit(w, true, null, T0 + 3);
        PayException lack = assertThrows(PayException.class, () -> l.refund(pid, Amount.yuan(30), null, T0 + 4));
        assertEquals("insufficient_balance", lack.code);
        assertTrue(lack.getMessage().contains("25"), lack.getMessage());
        l.refund(pid, Amount.of(5, Collections.singletonMap(DIAMOND, 1L)), null, T0 + 5);
        assertTrue(l.balance(bob).isZero());
        assertEquals(Amount.of(15, Collections.singletonMap(DIAMOND, 1L)), l.balance(alice));
        assertEquals(PayRecord.Kind.REFUND_IN, l.records(alice, 1).get(0).kind);
        assertThrows(PayException.class, () -> l.refund("pay_nope", Amount.yuan(1), null, T0));
    }

    /** 提现：余额不够不能开始；开始时就扣，作废退回 */
    @Test
    void withdrawReserves() throws Exception {
        Ledger l = open();
        AccountRef v = AccountRef.virtual("test:shop");
        l.ensureVirtual("test:shop");
        PayException none = assertThrows(PayException.class,
                () -> l.beginWithdraw(v, BOB, "Bob", Amount.yuan(1), CurrencyPlan.split(1, Currency.Unit.YUAN), null, null, T0));
        assertEquals("insufficient_balance", none.code);
    }

    /** 密钥：只存哈希；重置后旧的作废 */
    @Test
    void keys() throws Exception {
        Ledger l = open();
        l.touchPlayer(BOB, "Bob");
        AccountRef bob = AccountRef.player(BOB);
        String k1 = l.newKey(bob, T0);
        assertTrue(k1.startsWith("yrsk_") && k1.length() == 29, k1);
        assertEquals(bob, l.accountByKey(k1));
        assertNull(l.accountByKey(k1 + "x"));
        assertNull(l.accountByKey("yrsk_aaaaaaaaaaaaaaaaaaaaaaaa"));
        String k2 = l.newKey(bob, T0 + 1);
        assertNull(l.accountByKey(k1), "重置后旧密钥作废");
        assertEquals(bob, l.accountByKey(k2));
        l.close();
        String journal = new String(Files.readAllBytes(dir.resolve(PayStore.JOURNAL)), StandardCharsets.UTF_8);
        assertFalse(journal.contains(k2), "日志里不存明文");
        assertTrue(journal.contains(Ledger.hashKey(k2)));
        Ledger again = open();
        assertEquals(bob, again.accountByKey(k2));
        assertNull(again.accountByKey(k1));
    }

    /** 账户设置“操作前必须验证身份”：默认关；改了记进日志，关了再开、写快照以后都在 */
    @Test
    void verifySetting() throws Exception {
        Ledger l = open();
        l.touchPlayer(BOB, "Bob");
        AccountRef bob = AccountRef.player(BOB);
        assertFalse(l.account(bob).verifyBeforeOps);
        l.setVerify(bob, true);
        assertTrue(l.account(bob).verifyBeforeOps);
        assertThrows(IllegalStateException.class, () -> l.setVerify(AccountRef.player(ALICE), true), "没有账户");
        l.close();
        Ledger r = open();
        assertTrue(r.account(bob).verifyBeforeOps, "日志重放");
        r.snapshotNow(T0, Long.MAX_VALUE / 4);
        r.close();
        Ledger s = open();
        assertTrue(s.account(bob).verifyBeforeOps, "快照");
        s.setVerify(bob, false);
        s.close();
        assertFalse(open().account(bob).verifyBeforeOps);
    }

    /** 关了再开：照日志重放；写快照以后照快照 + 之后的日志 */
    @Test
    void persistence() throws Exception {
        Ledger l = open();
        l.touchPlayer(BOB, "Bob");
        AccountRef bob = AccountRef.player(BOB);
        Ledger.Order o = l.createOrder(draft(bob, 40, T0 + 60_000), T0);
        pay(l, o, ALICE, T0);
        l.refund(l.order(o.id).paymentId, Amount.yuan(4), null, T0);
        Amount bobBal = l.balance(bob);
        l.close();
        Ledger r = open();
        assertEquals(bobBal, r.balance(bob));
        assertEquals(Amount.yuan(4), r.balance(AccountRef.player(ALICE)));
        assertSame(OrderInfo.Status.PAID, r.order(o.id).status(T0));
        assertEquals(l.records(bob, 50).size(), r.records(bob, 50).size());
        r.snapshotNow(T0, Long.MAX_VALUE / 4);
        assertEquals(0, Files.size(dir.resolve(PayStore.JOURNAL)), "快照后日志清空");
        Ledger.Order o2 = r.createOrder(draft(bob, 2, T0 + 60_000), T0);
        r.close();
        Ledger s = open();
        assertEquals(bobBal, s.balance(bob));
        assertNotNull(s.order(o2.id));
        assertEquals(Amount.yuan(4), s.payment(l.order(o.id).paymentId).refunded);
        s.close();
    }

    /** 日志最后一行写到一半（崩了）：丢掉那半行，前面的照旧，之后接着写 */
    @Test
    void tornTail() throws Exception {
        Ledger l = open();
        l.touchPlayer(BOB, "Bob");
        Ledger.Order o = l.createOrder(draft(AccountRef.player(BOB), 40, T0 + 60_000), T0);
        l.close();
        Files.write(dir.resolve(PayStore.JOURNAL), "99 deadbeef {\"t\":\"cancel\",\"id\":\"".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Ledger r = open();
        assertSame(OrderInfo.Status.OPEN, r.order(o.id).status(T0));
        Ledger.Order o2 = r.createOrder(draft(AccountRef.player(BOB), 2, T0 + 60_000), T0);
        r.close();
        Ledger s = open();
        assertNotNull(s.order(o2.id));
        assertTrue(Files.list(dir).anyMatch(p -> p.getFileName().toString().startsWith("journal.bad-")), "坏尾巴另存");
        s.close();
    }

    /** 改了一个字（CRC 对不上）的那条和后面的都不认 */
    @Test
    void corruptLine() throws Exception {
        Ledger l = open();
        l.touchPlayer(BOB, "Bob");
        Ledger.Order o = l.createOrder(draft(AccountRef.player(BOB), 40, T0 + 60_000), T0);
        l.close();
        Path j = dir.resolve(PayStore.JOURNAL);
        String text = new String(Files.readAllBytes(j), StandardCharsets.UTF_8).replace("\"yuan\":40", "\"yuan\":90");
        Files.write(j, text.getBytes(StandardCharsets.UTF_8));
        Ledger r = open();
        assertNull(r.order(o.id), "被改过的那条不算");
        assertNotNull(r.account(AccountRef.player(BOB)));
        r.close();
    }

    /** 模组取出 / 作废：余额不够不扣 */
    @Test
    void virtualRemove() throws Exception {
        Ledger l = open();
        AccountRef v = AccountRef.virtual("test:shop");
        l.ensureVirtual("test:shop");
        Ledger.Order o = l.createOrder(draft(v, 10, T0 + 60_000), T0);
        pay(l, o, ALICE, T0);
        assertThrows(PayException.class, () -> l.remove(v, Amount.yuan(11), PayRecord.Kind.TAKEN, null, T0));
        l.remove(v, Amount.yuan(4), PayRecord.Kind.TAKEN, "测试", T0);
        l.remove(v, Amount.item(DIAMOND, 1), PayRecord.Kind.DISCARDED, null, T0);
        assertEquals(Amount.yuan(6), l.balance(v));
        assertEquals(PayRecord.Kind.DISCARDED, l.records(v, 1).get(0).kind);
    }

    /** 快照时清掉过期一天以上没付的收款单 */
    @Test
    void prune() throws Exception {
        Ledger l = open();
        l.touchPlayer(BOB, "Bob");
        Ledger.Order o = l.createOrder(draft(AccountRef.player(BOB), 40, T0 + 60_000), T0);
        l.snapshotNow(T0 + 2 * 24 * 3600_000L, Ledger.MAX_RECORDS * 1000L * 3600 * 24);
        assertNull(l.order(o.id));
    }

    /** 虚拟账户的显示名：建时带上、改名、重开账本还在；收款单与付款人的记录里用它，不用登记名 */
    @Test
    void virtualDisplayName() throws Exception {
        Ledger l = open();
        AccountRef v = AccountRef.virtual("test:shop");
        l.ensureVirtual("test:shop");
        assertEquals("test:shop", l.name(v), "没起名时显示登记名");
        l.ensureVirtual("test:shop", "小店");
        assertEquals("小店", l.name(v));
        l.ensureVirtual("test:shop");
        assertEquals("小店", l.name(v), "不给名字时不改回登记名");
        Ledger.Order o = l.createOrder(draft(v, 10, T0 + 60_000), T0);
        assertEquals("小店", l.info(o, T0, id -> "/c/" + id).receiverName);
        pay(l, o, ALICE, T0 + 1);
        assertEquals("小店", l.records(AccountRef.player(ALICE), 1).get(0).counterparty);
        l.ensureVirtual("test:shop", "大店");
        l.close();
        Ledger again = open();
        assertEquals("大店", again.name(v), "改名记进账本");
        assertEquals("小店", again.records(AccountRef.player(ALICE), 1).get(0).counterparty, "已经记下的不改");
        again.ensureVirtual("test:new", "新店");
        assertEquals("新店", again.name(AccountRef.virtual("test:new")), "建时带上");
    }
}
