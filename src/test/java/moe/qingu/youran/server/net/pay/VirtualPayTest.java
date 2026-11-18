package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PayItem;
import moe.qingu.youran.api.pay.PayRecord;
import moe.qingu.youran.api.pay.PaymentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 虚拟账户付款（服主 11-18）：清用自己 pay.yr 账户的余额付 dado 的收款单。只看账，不动世界；时间是固定的数，随机数固定种子 */
class VirtualPayTest {
    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final PayItem DIAMOND = PayItem.of("minecraft:diamond", 0);
    static final long T0 = 1_763_400_000_000L;
    static final AccountRef QING = AccountRef.virtual("yrqing:qing");
    static final AccountRef DADO = AccountRef.virtual("youranscp:dado");

    @TempDir
    Path dir;

    Ledger open() throws Exception {
        return open(dir);
    }

    static Ledger open(Path d) throws Exception {
        Ledger l = new Ledger(new PayStore(d), new Random(7));
        l.load();
        return l;
    }

    static Ledger.Draft draft(AccountRef to, Amount price, Currency.Unit unit, long expiresAt) {
        Ledger.Draft d = new Ledger.Draft();
        d.receiver = to;
        d.site = "dado.wixsite.com";
        d.price = price;
        d.unit = unit;
        d.description = "SCP-6468-A 4 Alice";
        d.reference = "ref-1";
        d.returnUrl = "http://dado.wixsite.com/thx";
        d.expiresAt = expiresAt;
        d.origin = "mod";
        return d;
    }

    /** 两个虚拟账户（清有显示名）；Alice 付一张清的收款单，清的账户里有 {@code fund} */
    static void setUp(Ledger l, Amount fund) throws Exception {
        l.touchPlayer(ALICE, "Alice");
        l.ensureVirtual(QING.virtual, "清");
        l.ensureVirtual(DADO.virtual, "dado");
        if (!fund.isZero()) {
            Ledger.Order o = l.createOrder(draft(QING, fund, Currency.Unit.YUAN, T0 + 600_000), T0);
            Ledger.Tx tx = l.beginPay(o, ALICE, "Alice", Collections.<PayItem, Long>emptyMap(), Collections.<PayItem, Long>emptyMap(), null, T0);
            l.commit(tx, true, null, T0);
        }
    }

    /** dado 的一张收款单：按绿宝石标价 */
    static Ledger.Order dadoOrder(Ledger l, long emeralds) throws Exception {
        return l.createOrder(draft(DADO, Amount.yuan(emeralds * Currency.YUAN_PER_EMERALD), Currency.Unit.EMERALD, T0 + 600_000), T0);
    }

    static Amount sum(Ledger l) {
        return l.balance(QING).plus(l.balance(DADO)).plus(l.balance(AccountRef.player(ALICE)));
    }

    static PayException fails(String code, org.junit.jupiter.api.function.Executable x) {
        PayException e = assertThrows(PayException.class, x);
        assertEquals(code, e.code, e.getMessage());
        return e;
    }

    /** 付成：从清的余额扣、记进 dado；付款人是清的账户（没有玩家 UUID），名字是显示名；双方各一条记录 */
    @Test
    void pays() throws Exception {
        Ledger l = open();
        setUp(l, Amount.yuan(30));
        Ledger.Order o = dadoOrder(l, 5);
        Ledger.Paid p = l.payFromVirtual(QING, o.id, null, null, T0 + 1000);
        assertFalse(p.replayed);
        assertEquals(Amount.yuan(20), l.balance(QING));
        assertEquals(Amount.yuan(10), l.balance(DADO));
        assertSame(OrderInfo.Status.PAID, l.order(o.id).status(T0 + 1000));

        PaymentInfo info = p.payment.info();
        assertNull(info.payer, "不是玩家付的：没有玩家 UUID");
        assertEquals(QING, info.payerAccount);
        assertEquals("清", info.payerName);
        assertEquals(DADO, info.receiver);
        assertEquals(Amount.yuan(10), info.amount);
        assertEquals("ref-1", info.reference, "dado 靠单号知道送给谁");

        OrderInfo oi = l.info(l.order(o.id), T0 + 1000, id -> "http://pay.yr/c/" + id);
        assertNull(oi.payer);
        assertEquals(QING, oi.payerAccount);
        assertEquals("清", oi.payerName);
        assertEquals(info.id, oi.paymentId);

        PayRecord spent = l.records(QING, 1).get(0);
        assertSame(PayRecord.Kind.SPENT, spent.kind);
        assertEquals(-1, spent.sign(), "余额付款是从账户扣");
        assertEquals("dado", spent.counterparty);
        assertEquals(info.id, spent.ref);
        PayRecord got = l.records(DADO, 1).get(0);
        assertSame(PayRecord.Kind.RECEIVED, got.kind);
        assertEquals("清", got.counterparty);
        l.close();
    }

    /** 余额不够：拒，原因写清楚，什么都不扣、日志不多写 */
    @Test
    void insufficientBalance() throws Exception {
        Ledger l = open();
        setUp(l, Amount.yuan(9));
        Ledger.Order o = dadoOrder(l, 5);
        long size = Files.size(dir.resolve(PayStore.JOURNAL));
        PayException e = fails("insufficient_balance", () -> l.payFromVirtual(QING, o.id, null, "r1", T0));
        assertTrue(e.getMessage().contains("还差 1 元"), e.getMessage());
        assertEquals(Amount.yuan(9), l.balance(QING));
        assertEquals(Amount.ZERO, l.balance(DADO));
        assertSame(OrderInfo.Status.OPEN, l.order(o.id).status(T0));
        assertEquals(size, Files.size(dir.resolve(PayStore.JOURNAL)));
        // 没有余额的账户也一样
        Ledger.Order o2 = l.createOrder(draft(QING, Amount.yuan(2), Currency.Unit.YUAN, T0 + 600_000), T0);
        fails("insufficient_balance", () -> l.payFromVirtual(DADO, o2.id, null, null, T0));
        l.close();
    }

    /** 单子要开着：付过、过期、取消、正在由玩家付、没有这张，都拒；只许某个玩家付的、付给自己、金额不对也拒 */
    @Test
    void orderMustBeOpen() throws Exception {
        Ledger l = open();
        setUp(l, Amount.yuan(100));
        Amount before = l.balance(QING);

        Ledger.Order paid = dadoOrder(l, 1);
        Ledger.Tx tx = l.beginPay(paid, ALICE, "Alice", Collections.<PayItem, Long>emptyMap(), Collections.<PayItem, Long>emptyMap(), null, T0);
        fails("order_not_open", () -> l.payFromVirtual(QING, paid.id, null, null, T0));
        l.commit(tx, true, null, T0);
        fails("order_not_open", () -> l.payFromVirtual(QING, paid.id, null, null, T0));

        Ledger.Order expired = dadoOrder(l, 1);
        fails("order_expired", () -> l.payFromVirtual(QING, expired.id, null, null, T0 + 600_000));

        Ledger.Order canceled = dadoOrder(l, 1);
        assertTrue(l.cancel(canceled.id, DADO, T0));
        fails("order_not_open", () -> l.payFromVirtual(QING, canceled.id, null, null, T0));

        fails("no_such_order", () -> l.payFromVirtual(QING, "ord_nosuchorder00000", null, null, T0));

        Ledger.Draft only = draft(DADO, Amount.yuan(2), Currency.Unit.EMERALD, T0 + 600_000);
        only.onlyPayer = ALICE;
        Ledger.Order onlyAlice = l.createOrder(only, T0);
        fails("wrong_payer", () -> l.payFromVirtual(QING, onlyAlice.id, null, null, T0));

        Ledger.Order self = l.createOrder(draft(QING, Amount.yuan(2), Currency.Unit.YUAN, T0 + 600_000), T0);
        fails("self_payment", () -> l.payFromVirtual(QING, self.id, null, null, T0));

        Ledger.Order o = dadoOrder(l, 3);
        fails("amount_mismatch", () -> l.payFromVirtual(QING, o.id, Amount.yuan(5), null, T0));
        fails("amount_mismatch", () -> l.payFromVirtual(QING, o.id, Amount.of(6, Collections.singletonMap(DIAMOND, 1L)), null, T0));
        assertEquals(before, l.balance(QING), "拒了的都没扣");
        l.payFromVirtual(QING, o.id, Amount.yuan(6), null, T0);
        assertEquals(before.minus(Amount.yuan(6)), l.balance(QING), "预期金额一样就付");
        l.close();
    }

    /** 币种：按绿宝石、按元标价都从元余额里出；收进来的绿宝石已经折成元；价格里的物品要余额里有 */
    @Test
    void currencies() throws Exception {
        Ledger l = open();
        l.touchPlayer(ALICE, "Alice");
        l.ensureVirtual(QING.virtual, "清");
        l.ensureVirtual(DADO.virtual, "dado");
        // Alice 用 3 个绿宝石 + 1 颗钻石付了清的一张单：账户里记成 6 元 + 钻石
        Map<PayItem, Long> things = new java.util.LinkedHashMap<>();
        things.put(Currency.Denomination.EMERALD.item, 3L);
        things.put(DIAMOND, 1L);
        Ledger.Order fund = l.createOrder(draft(QING, Amount.ofItems(things), Currency.Unit.EMERALD, T0 + 600_000), T0);
        l.commit(l.beginPay(fund, ALICE, "Alice", things, Collections.<PayItem, Long>emptyMap(), null, T0), true, null, T0);
        assertEquals(Amount.of(6, Collections.singletonMap(DIAMOND, 1L)), l.balance(QING));

        Ledger.Order yuanOrder = l.createOrder(draft(DADO, Amount.yuan(3), Currency.Unit.YUAN, T0 + 600_000), T0);
        l.payFromVirtual(QING, yuanOrder.id, null, null, T0);
        assertEquals(Amount.of(3, Collections.singletonMap(DIAMOND, 1L)), l.balance(QING), "按元标价，从绿宝石折来的元里出");

        Ledger.Order twoDiamonds = l.createOrder(draft(DADO, Amount.item(DIAMOND, 2), Currency.Unit.EMERALD, T0 + 600_000), T0);
        PayException e = fails("insufficient_balance", () -> l.payFromVirtual(QING, twoDiamonds.id, null, null, T0));
        assertTrue(e.getMessage().contains("minecraft:diamond"), e.getMessage());

        Ledger.Order mixed = l.createOrder(draft(DADO, Amount.of(2, Collections.singletonMap(DIAMOND, 1L)), Currency.Unit.EMERALD,
                T0 + 600_000), T0);
        l.payFromVirtual(QING, mixed.id, null, null, T0);
        assertEquals(Amount.yuan(1), l.balance(QING));
        assertEquals(Amount.of(5, Collections.singletonMap(DIAMOND, 1L)), l.balance(DADO));
        l.close();
    }

    /** 同一个请求编号：只付一次，返回第一次的付款；用到另一张单上拒；关了再开、写了快照以后照样认得 */
    @Test
    void requestIdReplay() throws Exception {
        Ledger l = open();
        setUp(l, Amount.yuan(30));
        Ledger.Order o = dadoOrder(l, 5);
        Ledger.Paid first = l.payFromVirtual(QING, o.id, null, "qing-pay-1", T0);
        Ledger.Paid again = l.payFromVirtual(QING, o.id, null, "qing-pay-1", T0 + 5000);
        assertTrue(again.replayed);
        assertEquals(first.payment.id, again.payment.id);
        assertEquals(Amount.yuan(20), l.balance(QING), "只扣一次");
        Ledger.Order o2 = dadoOrder(l, 1);
        fails("request_id_reused", () -> l.payFromVirtual(QING, o2.id, null, "qing-pay-1", T0));
        // 别的账户用同一个编号不算重发（编号按付款账户分）：dado 付清的单
        Ledger.Order toQing = l.createOrder(draft(QING, Amount.yuan(2), Currency.Unit.YUAN, T0 + 600_000), T0);
        assertFalse(l.payFromVirtual(DADO, toQing.id, null, "qing-pay-1", T0).replayed);
        // 不带编号重来：单子已经付了，拒，不会付第二次
        fails("order_not_open", () -> l.payFromVirtual(QING, o.id, null, null, T0));
        l.close();

        Ledger r = open();
        assertTrue(r.payFromVirtual(QING, o.id, null, "qing-pay-1", T0).replayed, "重开以后（照日志）还认得");
        r.snapshotNow(T0, Long.MAX_VALUE / 4);
        r.close();
        Ledger s = open();
        Ledger.Paid p = s.payFromVirtual(QING, o.id, null, "qing-pay-1", T0);
        assertTrue(p.replayed, "写快照以后还认得");
        assertEquals(first.payment.id, p.payment.id);
        assertEquals(QING, p.payment.info().payerAccount);
        assertEquals("清", p.payment.payerName);
        assertSame(OrderInfo.Status.PAID, s.order(o.id).status(T0));
        assertEquals(QING, s.info(s.order(o.id), T0, id -> id).payerAccount);
        assertEquals(Amount.yuan(22), s.balance(QING));
        s.close();
    }

    /**
     * 在每一步崩：写不进去（磁盘满）、写了半行就死了、写盘以后改内存以前死了、改完内存以后（回调前）死了。
     * 重开以后：钱不多不少，要么付了一次要么没付；没付的可以接着付，付了的用同一个编号重发拿到同一笔、不再扣。
     */
    @Test
    void crashAtEveryStep() throws Exception {
        PayStore.Fault[] faults = {PayStore.Fault.FAIL_BEFORE_WRITE, PayStore.Fault.CRASH_HALF_WRITTEN, PayStore.Fault.CRASH_AFTER_FSYNC,
                PayStore.Fault.NONE};
        for (PayStore.Fault f : faults) {
            Path d = dir.resolve("crash-" + f);
            PayStore store = new PayStore(d);
            Ledger l = new Ledger(store, new Random(3));
            l.load();
            setUp(l, Amount.yuan(30));
            Ledger.Order o = dadoOrder(l, 5);
            Amount total = sum(l);
            store.fault = f;
            boolean crashed = false;
            try {
                l.payFromVirtual(QING, o.id, null, "req-" + f, T0);
            } catch (PayStore.Crash c) {
                crashed = true;
            } catch (java.io.IOException e) {
                assertEquals(PayStore.Fault.FAIL_BEFORE_WRITE, f, "写不进去：报错，PayServer 把它变成 storage");
            }
            if (f == PayStore.Fault.FAIL_BEFORE_WRITE) {
                // 进程还活着：内存里也没改，可以接着付
                assertFalse(crashed);
                assertEquals(Amount.yuan(30), l.balance(QING), f + "：写不进去时什么都没扣");
                assertSame(OrderInfo.Status.OPEN, l.order(o.id).status(T0));
            }
            // 崩了（或接着关服）：关掉文件，不写快照，按盘上的重开
            l.close();
            Ledger r = open(d);
            assertTrue(r.pending().isEmpty(), f + "：没有要补的");
            assertEquals(total, sum(r), f + "：钱不多不少");
            boolean paid = r.order(o.id).status(T0) == OrderInfo.Status.PAID;
            boolean expectPaid = f == PayStore.Fault.CRASH_AFTER_FSYNC || f == PayStore.Fault.NONE;
            assertEquals(expectPaid, paid, f.toString());
            assertEquals(paid ? Amount.yuan(20) : Amount.yuan(30), r.balance(QING), f.toString());
            Ledger.Paid retry = r.payFromVirtual(QING, o.id, null, "req-" + f, T0 + 1000);
            assertEquals(paid, retry.replayed, f + "：重发同一个编号");
            assertEquals(Amount.yuan(20), r.balance(QING), f + "：重发以后总共只扣一次");
            assertEquals(Amount.yuan(10), r.balance(DADO), f.toString());
            fails("order_not_open", () -> r.payFromVirtual(QING, o.id, null, "req-other-" + f, T0 + 2000));
            r.close();
            Ledger s = open(d);
            assertEquals(Amount.yuan(20), s.balance(QING), f + "：再开一次还是一样");
            s.close();
        }
    }

    /** 退款：退回付款的虚拟账户（不是哪个玩家）；关了再开、写了快照以后还在 */
    @Test
    void refundGoesBackToVirtualAccount() throws Exception {
        Ledger l = open();
        setUp(l, Amount.yuan(30));
        Ledger.Order o = dadoOrder(l, 5);
        Ledger.Paid p = l.payFromVirtual(QING, o.id, null, null, T0);
        Amount alice = l.balance(AccountRef.player(ALICE));
        l.refund(p.payment.id, Amount.yuan(4), "缺货", T0 + 1000);
        assertEquals(Amount.yuan(24), l.balance(QING));
        assertEquals(Amount.yuan(6), l.balance(DADO));
        assertEquals(alice, l.balance(AccountRef.player(ALICE)), "不退给别人");
        PayRecord in = l.records(QING, 1).get(0);
        assertSame(PayRecord.Kind.REFUND_IN, in.kind);
        assertEquals("dado", in.counterparty);
        assertEquals(Amount.yuan(4), l.payment(p.payment.id).refunded);
        l.close();
        Ledger r = open();
        assertEquals(Amount.yuan(24), r.balance(QING), "重放日志");
        r.refund(p.payment.id, Amount.yuan(6), null, T0 + 2000);
        assertEquals(Amount.yuan(30), r.balance(QING));
        fails("exceeds_refundable", () -> r.refund(p.payment.id, Amount.yuan(1), null, T0 + 3000));
        r.snapshotNow(T0, Long.MAX_VALUE / 4);
        r.close();
        Ledger s = open();
        assertEquals(Amount.yuan(30), s.balance(QING), "快照");
        assertEquals(Amount.ZERO, s.balance(DADO));
        assertNotNull(s.payment(p.payment.id));
        assertEquals(Amount.yuan(10), s.payment(p.payment.id).refunded);
        s.close();
    }

    /** 玩家付的款照旧：付款账户是他的玩家账户，快照前后一样（老快照里没有 payerAcct 也认得） */
    @Test
    void playerPaymentsUnchanged() throws Exception {
        Ledger l = open();
        setUp(l, Amount.yuan(8));
        Ledger.Payment pay = l.paymentsTo(QING, 1).get(0);
        assertEquals(ALICE, pay.payer);
        assertEquals(AccountRef.player(ALICE), pay.payerAcct);
        assertEquals(AccountRef.player(ALICE), pay.info().payerAccount);
        l.snapshotNow(T0, Long.MAX_VALUE / 4);
        l.close();
        Ledger r = open();
        Ledger.Payment p2 = r.payment(pay.id);
        assertEquals(ALICE, p2.payer);
        assertEquals(AccountRef.player(ALICE), p2.payerAcct);
        r.refund(pay.id, Amount.yuan(8), null, T0);
        assertEquals(Amount.yuan(8), r.balance(AccountRef.player(ALICE)), "玩家付的退给玩家");
        r.close();
    }
}
