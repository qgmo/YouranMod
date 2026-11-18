package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.PaymentInfo;
import moe.qingu.youran.api.pay.PaymentListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static moe.qingu.youran.server.net.pay.VirtualPayTest.ALICE;
import static moe.qingu.youran.server.net.pay.VirtualPayTest.DADO;
import static moe.qingu.youran.server.net.pay.VirtualPayTest.QING;
import static moe.qingu.youran.server.net.pay.VirtualPayTest.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知收款方至少一次（{@link PayNotifier}）：写盘后、通知前崩了，重开后补发；回调抛异常不记“已通知”，过一阵再试；监听器晚登记时补发；
 * 退款同理。只看账本与回调，时间用假时钟（数组里的一个数），随机数固定种子，不 sleep、不靠线程。
 */
class PayNotifierTest {
    @TempDir
    Path dir;

    /** 假时钟 */
    final long[] now = {T0};
    /** 收款账户 → 监听器（测试里随时加减） */
    final Map<AccountRef, List<PaymentListener>> listeners = new HashMap<>();

    /** 记下收到的回调：“paid pay_…”、“refund pay_… ref_… 元数” */
    static final class Recorder implements PaymentListener {
        final List<String> got = new ArrayList<>();
        /** 前几次回调抛异常 */
        int failNext;

        @Override
        public void onPaid(OrderInfo order, PaymentInfo payment) {
            if (failNext > 0) {
                failNext--;
                throw new IllegalStateException("收款方还没准备好（测试）");
            }
            assertNotNull(order, "onPaid 要带收款单");
            assertEquals(payment.orderId, order.id);
            assertSame(OrderInfo.Status.PAID, order.status);
            got.add("paid " + payment.id);
        }

        @Override
        public void onRefunded(PaymentInfo payment, String refundId, Amount refund) {
            if (failNext > 0) {
                failNext--;
                throw new IllegalStateException("收款方还没准备好（测试）");
            }
            got.add("refund " + payment.id + " " + refundId + " " + refund.yuan);
        }
    }

    Ledger open(PayStore store) throws Exception {
        Ledger l = new Ledger(store, new Random(11));
        l.load();
        return l;
    }

    PayNotifier notifier(Ledger l) {
        return new PayNotifier(l, a -> listeners.getOrDefault(a, Collections.<PaymentListener>emptyList()),
                id -> l.orderInfo(id, now[0], x -> "http://pay.yr/c/" + x), () -> now[0]);
    }

    void listen(AccountRef a, PaymentListener l) {
        listeners.computeIfAbsent(a, k -> new ArrayList<>()).add(l);
    }

    /** 清付 dado 一张 5 绿宝石的单（虚拟账户，一条日志）；清的余额由 Alice 先付清 30 元来（玩家付款：开始 → 结束） */
    static Ledger.Paid qingPaysDado(Ledger l) throws Exception {
        VirtualPayTest.setUp(l, Amount.yuan(30));
        Ledger.Order o = VirtualPayTest.dadoOrder(l, 5);
        return l.payFromVirtual(QING, o.id, null, "req-1", T0 + 1000);
    }

    /** 写盘后、通知前崩了（付款在账上、没有“已通知”）：重开后第一次 tick 补发，各一次；补发记下以后再重开不再发。中间写过快照也一样 */
    @Test
    void crashBetweenWriteAndNotifyIsResentAfterRestart() throws Exception {
        for (boolean snapshot : new boolean[]{false, true}) {
            Path d = dir.resolve("snap-" + snapshot);
            Ledger l = open(new PayStore(d));
            Ledger.Paid p = qingPaysDado(l);
            String alicePay = l.paymentsTo(QING, 1).get(0).id;
            // 付款都记好了，回调还没来得及调：进程死了
            if (snapshot) {
                l.snapshotNow(T0 + 2000, PayServer.keepMillis());
            }
            l.close();

            Ledger again = open(new PayStore(d));
            assertEquals(2, again.notices().size(), "两笔都没确认通知过：Alice → 清、清 → dado");
            Recorder dado = new Recorder();
            Recorder qing = new Recorder();
            listen(DADO, dado);
            listen(QING, qing);
            PayNotifier n = notifier(again);
            n.kickAll();
            n.notify(p.payment.id);
            assertEquals(Collections.emptyList(), dado.got, "开服后第一次 tick 以前不回调（别的模组可能还没读好存档）");
            n.tick();
            assertEquals(Collections.singletonList("paid " + p.payment.id), dado.got);
            assertEquals(Collections.singletonList("paid " + alicePay), qing.got);
            assertTrue(again.notices().isEmpty());
            n.tick();
            assertEquals(1, dado.got.size(), "通知过的不再发");
            again.close();

            Ledger third = open(new PayStore(d));
            assertTrue(third.notices().isEmpty(), "“已通知”记在账本里，重开后还在");
            PayNotifier n3 = notifier(third);
            n3.kickAll();
            n3.tick();
            assertEquals(1, dado.got.size());
            assertEquals(1, qing.got.size());
            third.close();
            listeners.clear();
        }
    }

    /** 正常情况：付成马上回调一次，记“已通知”；重开不再发 */
    @Test
    void normalPaymentIsNotifiedOnce() throws Exception {
        Ledger l = open(new PayStore(dir));
        PayNotifier n = notifier(l);
        n.kickAll();
        n.tick();
        Recorder dado = new Recorder();
        listen(DADO, dado);
        Ledger.Paid p = qingPaysDado(l);
        n.notify(p.payment.id);
        assertEquals(Collections.singletonList("paid " + p.payment.id), dado.got);
        assertFalse(l.unnotified(p.payment.id));
        l.close();
        Ledger again = open(new PayStore(dir));
        assertFalse(again.unnotified(p.payment.id));
        again.close();
    }

    /** 回调返回了，记“已通知”时写不进去 / 写了半行就崩：没记上，下次再发（收款方去重） */
    @Test
    void markNotWrittenMeansResend() throws Exception {
        for (PayStore.Fault f : new PayStore.Fault[]{PayStore.Fault.FAIL_BEFORE_WRITE, PayStore.Fault.CRASH_HALF_WRITTEN}) {
            Path d = dir.resolve("f-" + f);
            PayStore store = new PayStore(d);
            Ledger l = open(store);
            PayNotifier n = notifier(l);
            n.tick();
            Recorder dado = new Recorder();
            listen(DADO, dado);
            Ledger.Paid p = qingPaysDado(l);
            store.fault = f;
            n.notify(p.payment.id);
            assertTrue(n.retrying(p.payment.id), "没记上：等重试");
            assertTrue(l.unnotified(p.payment.id));
            assertEquals(1, dado.got.size(), "回调已经调过一次");
            l.close();

            Ledger again = open(new PayStore(d));
            assertTrue(again.unnotified(p.payment.id), "“已通知”没记上");
            PayNotifier n2 = notifier(again);
            n2.kickAll();
            n2.tick();
            assertEquals(Arrays.asList("paid " + p.payment.id, "paid " + p.payment.id), dado.got, "再发一次：收款方要按付款编号去重");
            assertFalse(again.unnotified(p.payment.id));
            again.close();
            listeners.clear();
        }
    }

    /** 回调抛异常：不记“已通知”；1 分钟后重试，之后间隔加倍、最长每小时一次（不会每秒刷）；成功以后记下、不再发 */
    @Test
    void throwingListenerIsRetriedWithBackoff() throws Exception {
        Ledger l = open(new PayStore(dir));
        PayNotifier n = notifier(l);
        n.tick();
        Recorder dado = new Recorder();
        dado.failNext = 1000;
        listen(DADO, dado);
        Ledger.Paid p = qingPaysDado(l);
        String id = p.payment.id;
        n.notify(id);
        assertTrue(l.unnotified(id), "抛异常：不记");
        assertTrue(n.retrying(id));
        int calls = 1;
        // 每秒 tick 一次，走 10 小时：只在 1、3、7、15、31、63 分钟和之后每小时试
        List<Long> attempts = new ArrayList<>();
        for (int s = 1; s <= 10 * 3600; s++) {
            now[0] = T0 + s * 1000L;
            int before = 1000 - dado.failNext;
            n.tick();
            if (1000 - dado.failNext > before) {
                attempts.add((long) s);
            }
        }
        assertEquals(Arrays.asList(60L, 180L, 420L, 900L, 1860L, 3780L, 7380L, 10980L, 14580L, 18180L, 21780L, 25380L, 28980L, 32580L),
                attempts, "重试间隔：1、2、4、8、16、32 分钟，之后每小时");
        calls += attempts.size();
        assertEquals(1000 - calls, dado.failNext);
        assertTrue(l.unnotified(id));
        assertEquals(Collections.emptyList(), dado.got);

        // 收款方好了：下一次重试送到，记下，以后不再发
        dado.failNext = 0;
        now[0] = T0 + 10 * 3600_000L + 3600_000L;
        n.tick();
        assertEquals(Collections.singletonList("paid " + id), dado.got);
        assertFalse(l.unnotified(id));
        assertFalse(n.retrying(id));
        now[0] += 7200_000L;
        n.tick();
        assertEquals(1, dado.got.size());
        l.close();
    }

    /** 同一个账户两个监听器，一个抛异常：不记；重试时两个都再收到一次 */
    @Test
    void oneOfTwoListenersThrows() throws Exception {
        Ledger l = open(new PayStore(dir));
        PayNotifier n = notifier(l);
        n.tick();
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        b.failNext = 1;
        listen(DADO, a);
        listen(DADO, b);
        Ledger.Paid p = qingPaysDado(l);
        n.notify(p.payment.id);
        assertEquals(1, a.got.size());
        assertEquals(0, b.got.size());
        assertTrue(l.unnotified(p.payment.id));
        now[0] += PayNotifier.FIRST_RETRY_MILLIS;
        n.tick();
        assertEquals(2, a.got.size(), "a 再收到一次（要去重）");
        assertEquals(1, b.got.size());
        assertFalse(l.unnotified(p.payment.id));
        l.close();
    }

    /** 收款方还没登记监听器：不算通知过；登记（{@link PayNotifier#kick}）以后下一次 tick 补发，登记以前付的也补；重开后照样 */
    @Test
    void lateListenerGetsTheBacklog() throws Exception {
        Ledger l = open(new PayStore(dir));
        PayNotifier n = notifier(l);
        n.kickAll();
        n.tick();
        Ledger.Paid p = qingPaysDado(l);
        n.notify(p.payment.id);
        now[0] += 10 * 3600_000L;
        n.tick();
        assertTrue(l.unnotified(p.payment.id), "没人听：不记“已通知”");
        assertFalse(n.retrying(p.payment.id), "没人听不算失败，不定时重试");
        l.close();

        // 重开：开服补发时还是没人听；dado 开服以后才登记
        Ledger again = open(new PayStore(dir));
        PayNotifier n2 = notifier(again);
        n2.kickAll();
        n2.tick();
        assertTrue(again.unnotified(p.payment.id));
        Recorder dado = new Recorder();
        listen(DADO, dado);
        n2.kick(DADO);
        assertEquals(Collections.emptyList(), dado.got, "登记时不在那个线程里回调，等下一次 tick");
        n2.tick();
        assertEquals(Collections.singletonList("paid " + p.payment.id), dado.got);
        assertFalse(again.unnotified(p.payment.id));
        // 别的账户登记不影响
        n2.kick(QING);
        n2.tick();
        assertEquals(1, dado.got.size());
        again.close();
    }

    /** {@code PayApi.addListener} 在 pay.yr 开着时告诉它这个账户有监听器登记了（{@link PayServer#listenerAdded} → {@link PayNotifier#kick}） */
    @Test
    void addListenerTellsPayYr() {
        List<AccountRef> told = new ArrayList<>();
        moe.qingu.youran.network.PayBackend fake = (moe.qingu.youran.network.PayBackend) java.lang.reflect.Proxy.newProxyInstance(
                moe.qingu.youran.network.PayBackend.class.getClassLoader(), new Class<?>[]{moe.qingu.youran.network.PayBackend.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("listenerAdded")) {
                        told.add((AccountRef) args[0]);
                    }
                    return null;
                });
        moe.qingu.youran.network.PayBackend old = moe.qingu.youran.network.PayHooks.backend();
        Recorder r = new Recorder();
        moe.qingu.youran.network.PayHooks.setBackend(fake);
        try {
            moe.qingu.youran.api.pay.PayApi.addListener(DADO, r);
            assertEquals(Collections.singletonList(DADO), told);
        } finally {
            moe.qingu.youran.network.PayHooks.setBackend(old);
            moe.qingu.youran.api.pay.PayApi.removeListener(DADO, r);
        }
    }

    /** 退款也通知至少一次：带退款编号，在 onPaid 之后；崩在写盘后、通知前的，重开后补发；写快照以后也在 */
    @Test
    void refundsAreNotifiedAtLeastOnce() throws Exception {
        Ledger l = open(new PayStore(dir));
        PayNotifier n = notifier(l);
        n.tick();
        Recorder dado = new Recorder();
        listen(DADO, dado);
        Ledger.Paid p = qingPaysDado(l);
        String id = p.payment.id;
        n.notify(id);
        l.refund(id, Amount.yuan(4), "退一部分", T0 + 3000);
        n.notify(id);
        assertEquals(2, dado.got.size());
        String first = dado.got.get(1);
        assertTrue(first.startsWith("refund " + id + " ref_") && first.endsWith(" 4"), first);
        assertFalse(l.unnotified(id));

        // 再退一次，记下了还没通知就崩了（写过快照）
        l.refund(id, Amount.yuan(6), "再退", T0 + 4000);
        l.snapshotNow(T0 + 5000, PayServer.keepMillis());
        l.close();
        listeners.clear();
        Ledger again = open(new PayStore(dir));
        Ledger.Notice notice = null;
        for (Ledger.Notice x : again.notices()) {
            if (x.paymentId.equals(id)) {
                notice = x;
            }
        }
        assertNotNull(notice);
        assertFalse(notice.paid, "付款通知过了，只差退款");
        assertEquals(1, notice.refunds.size());
        Recorder dado2 = new Recorder();
        listen(DADO, dado2);
        PayNotifier n2 = notifier(again);
        n2.kickAll();
        n2.tick();
        assertEquals(1, dado2.got.size());
        String second = dado2.got.get(0);
        assertTrue(second.startsWith("refund " + id + " ref_") && second.endsWith(" 6"), second);
        assertFalse(second.split(" ")[2].equals(first.split(" ")[2]), "两次退款编号不同");
        assertFalse(again.unnotified(id));
        again.close();
    }

    /** 付款还没通知成（收款方一直抛异常）时退了款：退款排在付款后面，付款通知成以前不发退款 */
    @Test
    void refundWaitsForPaidNotice() throws Exception {
        Ledger l = open(new PayStore(dir));
        PayNotifier n = notifier(l);
        n.tick();
        Recorder dado = new Recorder();
        dado.failNext = 1;
        listen(DADO, dado);
        Ledger.Paid p = qingPaysDado(l);
        n.notify(p.payment.id);
        l.refund(p.payment.id, Amount.yuan(10), "全退", T0 + 3000);
        dado.failNext = 1;
        n.notify(p.payment.id);
        assertEquals(Collections.emptyList(), dado.got);
        now[0] += PayNotifier.FIRST_RETRY_MILLIS * 2;
        n.tick();
        assertEquals(2, dado.got.size());
        assertEquals("paid " + p.payment.id, dado.got.get(0), "先付款");
        assertTrue(dado.got.get(1).startsWith("refund " + p.payment.id), "后退款");
        l.close();
    }

    /** 收款方在 onPaid 里就退款（MineBay 找不到单时原样退回）：不嵌套回调，onPaid 一次，回调完再发退款一次 */
    @Test
    void refundInsideOnPaidIsNotNested() throws Exception {
        Ledger l = open(new PayStore(dir));
        PayNotifier n = notifier(l);
        n.tick();
        List<String> got = new ArrayList<>();
        listen(DADO, new PaymentListener() {
            @Override
            public void onPaid(OrderInfo order, PaymentInfo payment) {
                got.add("paid " + payment.id);
                try {
                    // 同 PayServer.doRefund：记账，再通知
                    l.refund(payment.id, payment.refundable(), "找不到这张单，原样退回", now[0]);
                } catch (Exception e) {
                    got.add("refund failed " + e.getMessage());
                }
                n.notify(payment.id);
            }

            @Override
            public void onRefunded(PaymentInfo payment, String refundId, Amount refund) {
                got.add("refund " + refund.yuan);
            }
        });
        Ledger.Paid p = qingPaysDado(l);
        n.notify(p.payment.id);
        assertEquals(Arrays.asList("paid " + p.payment.id, "refund 10"), got);
        assertFalse(l.unnotified(p.payment.id));
        l.close();
    }

    /** 没有“已通知”这一项的老快照：按通知过了读（不补发很久以前的） */
    @Test
    void oldSnapshotCountsAsNotified() throws Exception {
        Ledger l = open(new PayStore(dir));
        Ledger.Paid p = qingPaysDado(l);
        com.google.gson.JsonObject s = l.writeSnapshot();
        for (com.google.gson.JsonElement e : s.getAsJsonArray("payments")) {
            e.getAsJsonObject().remove("ntf");
        }
        l.close();
        Path old = dir.resolve("old");
        PayStore store = new PayStore(old);
        store.open();
        store.snapshot(s);
        store.close();
        Ledger again = open(new PayStore(old));
        assertNotNull(again.payment(p.payment.id));
        assertFalse(again.unnotified(p.payment.id));
        again.close();
    }
}
