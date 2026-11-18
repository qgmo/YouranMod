package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.Currency.Denomination;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.PaySpec;
import moe.qingu.youran.api.pay.PayItem;
import moe.qingu.youran.api.pay.PaymentRequest;
import moe.qingu.youran.api.pay.PaymentRequestHandler;
import moe.qingu.youran.api.pay.PaymentRequestHandler.Reply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 付款入口（{@link PayEntry}，服主 11-18）与付款请求的分发（{@link PayRequests}）：玩家在确认框里同意以后、模组用虚拟账户付，都经同一个入口；
 * 拒绝就不调入口、什么都不动；虚拟账户的请求交给它的模组；没有处理者时什么都不发生，单子照旧开着，之后直接调入口能付。
 * 时间是固定的数，随机数固定种子。
 */
class PayEntryTest {
    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final PayItem EMERALD = Denomination.EMERALD.item;
    static final long T0 = 1_763_400_000_000L;
    static final AccountRef QING = AccountRef.virtual("yrqing:qing");
    static final AccountRef DADO = AccountRef.virtual("youranscp:dado");

    @TempDir
    Path dir;

    Ledger ledger;
    FakeWorld world;
    TxRunner runner;
    PayEntry entry;
    final List<Ledger.Paid> paid = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ledger = new Ledger(new PayStore(dir), new Random(5));
        ledger.load();
        world = new FakeWorld();
        runner = new TxRunner(ledger, world, () -> T0, paid::add);
        entry = new PayEntry(ledger, world, runner, () -> T0, paid::add, null);
        ledger.touchPlayer(ALICE, "Alice");
        ledger.ensureVirtual(QING.virtual, "清");
        ledger.ensureVirtual(DADO.virtual, "dado");
    }

    Ledger.Order dadoOrder(long emeralds) throws Exception {
        return ledger.createOrder(VirtualPayTest.draft(DADO, Amount.yuan(emeralds * Currency.YUAN_PER_EMERALD), Currency.Unit.EMERALD,
                T0 + 600_000), T0);
    }

    FakeWorld.FakePlayer alice(long emeralds) {
        Map<PayItem, Long> inv = new LinkedHashMap<>();
        inv.put(EMERALD, emeralds);
        world.diskInv.put(ALICE, new LinkedHashMap<>(inv));
        return world.join(ALICE, "Alice", 64 * 36);
    }

    /** 清的账户里先有 {@code yuan} 元：Alice 在付款页（确认框同意）付了一张清的收款单 */
    void fundQing(long yuan) throws Exception {
        FakeWorld.FakePlayer a = alice(yuan / 2);
        Ledger.Order o = ledger.createOrder(VirtualPayTest.draft(QING, Amount.yuan(yuan), Currency.Unit.YUAN, T0 + 600_000), T0);
        PayEntry.Plan plan = entry.plan(o, ALICE, CurrencyPlan.Prefer.EMERALD, CurrencyPlan.BalanceUse.NONE);
        assertSame(TxRunner.Result.Status.DONE, entry.pay(new PaySpec(o.id, AccountRef.player(ALICE)), plan).status);
        assertTrue(a.inv.isEmpty());
        paid.clear();
    }

    /**
     * 玩家账户：付款页准备好拿法 → 确认框同意 → 调入口照确认的拿法扣、回调一次；拒绝（或超时）就不调入口，背包、账户、单子都不动，
     * 之后同意的那一次照样能付。
     */
    @Test
    void playerConfirmCallsEntryRejectDoesNothing() throws Exception {
        FakeWorld.FakePlayer a = alice(10);
        Ledger.Order o = dadoOrder(4);
        // 付款页：准备（确认框里写的就是这个拿法）
        PayEntry.Plan shown = entry.plan(ledger.order(o.id), ALICE, CurrencyPlan.Prefer.EMERALD, CurrencyPlan.BalanceUse.NONE);
        assertEquals(Long.valueOf(4), shown.items.take.get(EMERALD));
        // 拒绝或超时：付款页不调入口，只做过上面的准备——准备本身什么都不动
        assertSame(OrderInfo.Status.OPEN, ledger.order(o.id).status(T0));
        assertEquals(Long.valueOf(10), a.inv.get(EMERALD));
        assertEquals(Amount.ZERO, ledger.balance(DADO));
        assertTrue(paid.isEmpty());
        // 同意：调入口
        TxRunner.Result r = entry.pay(new PaySpec(o.id, AccountRef.player(ALICE)), shown);
        assertSame(TxRunner.Result.Status.DONE, r.status, String.valueOf(r.error));
        assertEquals(Long.valueOf(6), a.inv.get(EMERALD));
        assertEquals(Amount.yuan(8), ledger.balance(DADO));
        assertEquals(1, paid.size(), "回调一次");
        assertEquals(ALICE, r.paid.payment.payer);
        assertEquals(AccountRef.player(ALICE), r.paid.payment.info().payerAccount);
        assertEquals("Alice", r.paid.payment.payerName);
        // 再确认一次（另一个确认框）：单子已经付了，入口拒，不扣第二次
        TxRunner.Result twice = entry.pay(new PaySpec(o.id, AccountRef.player(ALICE)), shown);
        assertSame(TxRunner.Result.Status.FAILED, twice.status);
        assertEquals("order_not_open", twice.error.code);
        assertEquals(Long.valueOf(6), a.inv.get(EMERALD));
    }

    /** 确认以后背包变了（东西不够了）：入口照确认的拿法扣不成，作废，什么都不动 */
    @Test
    void playerInventoryChangedAfterConfirm() throws Exception {
        FakeWorld.FakePlayer a = alice(10);
        Ledger.Order o = dadoOrder(4);
        PayEntry.Plan shown = entry.plan(ledger.order(o.id), ALICE, CurrencyPlan.Prefer.EMERALD, CurrencyPlan.BalanceUse.NONE);
        a.inv.put(EMERALD, 1L);
        TxRunner.Result r = entry.pay(new PaySpec(o.id, AccountRef.player(ALICE)), shown);
        assertSame(TxRunner.Result.Status.FAILED, r.status);
        assertEquals("insufficient_items", r.error.code);
        assertSame(OrderInfo.Status.OPEN, ledger.order(o.id).status(T0));
        assertEquals(Long.valueOf(1), a.inv.get(EMERALD));
        assertTrue(ledger.pending().isEmpty());
    }

    /** 模组直接让玩家付（不带确认的拿法）：照选项现算；带请求编号重来只付一次；不在线的拒 */
    @Test
    void playerDirectWithRequestId() throws Exception {
        FakeWorld.FakePlayer a = alice(10);
        Ledger.Order o = dadoOrder(3);
        PaySpec spec = new PaySpec(o.id, AccountRef.player(ALICE)).requestId("mod-1").expect(Amount.yuan(6));
        TxRunner.Result r = entry.pay(spec, null);
        assertSame(TxRunner.Result.Status.DONE, r.status, String.valueOf(r.error));
        assertEquals(Long.valueOf(7), a.inv.get(EMERALD));
        TxRunner.Result again = entry.pay(spec, null);
        assertSame(TxRunner.Result.Status.DONE, again.status);
        assertTrue(again.paid.replayed);
        assertEquals(r.paid.payment.id, again.paid.payment.id);
        assertEquals(Long.valueOf(7), a.inv.get(EMERALD), "没有扣第二次");
        assertEquals(1, paid.size(), "重来不再回调");
        Ledger.Order o2 = dadoOrder(1);
        assertEquals("amount_mismatch", entry.pay(new PaySpec(o2.id, AccountRef.player(ALICE)).expect(Amount.yuan(1)), null).error.code);
        assertEquals("request_id_reused", entry.pay(new PaySpec(o2.id, AccountRef.player(ALICE)).requestId("mod-1"), null).error.code);
        UUID bob = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        assertEquals("offline", entry.pay(new PaySpec(o2.id, AccountRef.player(bob)), null).error.code);
    }

    /** 虚拟账户经同一个入口：付成、回调一次；同一个编号重来返回同一笔、不回调；写不进去时 storage、不扣 */
    @Test
    void virtualThroughEntry() throws Exception {
        fundQing(20);
        Ledger.Order o = dadoOrder(5);
        PaySpec spec = new PaySpec(o.id, QING).requestId("qing-1");
        TxRunner.Result r = entry.pay(spec, null);
        assertSame(TxRunner.Result.Status.DONE, r.status, String.valueOf(r.error));
        assertEquals(1, paid.size());
        assertSame(o.id, paid.get(0).order.id);
        assertNull(r.paid.payment.payer);
        assertEquals(QING, r.paid.payment.payerAcct);
        assertEquals("清", paid.get(0).payment.payerName, "收款方回调里付款人名字是显示名");
        TxRunner.Result again = entry.pay(spec, null);
        assertTrue(again.paid.replayed);
        assertEquals(1, paid.size());
        assertEquals(Amount.yuan(10), ledger.balance(QING));
        assertEquals("invalid_request_id", entry.pay(new PaySpec(o.id, QING).requestId("有空格 不行"), null).error.code);
        assertEquals("invalid_order", entry.pay(new PaySpec("", QING), null).error.code);
    }

    PaymentRequest request(Ledger.Order o, AccountRef account) {
        OrderInfo info = ledger.info(ledger.order(o.id), T0, id -> "http://pay.yr/c/" + id);
        return new PaymentRequest(info, account, ledger.name(account), ALICE, "Alice", Collections.singletonMap("prefer", "emerald"));
    }

    /** 分发：玩家账户给 YouranMod 自己的处理者；虚拟账户按模组 ID 给它的处理者，处理者同意了自己调入口 */
    @Test
    void dispatchByAccountType() throws Exception {
        fundQing(20);
        Ledger.Order o = dadoOrder(2);
        List<String> seen = new ArrayList<>();
        PaymentRequestHandler players = req -> {
            seen.add("player:" + req.account);
            return CompletableFuture.completedFuture(Reply.redirect("https://id.yr/confirm/x"));
        };
        Map<String, PaymentRequestHandler> mods = new HashMap<>();
        mods.put("yrqing", req -> {
            seen.add("yrqing:" + req.account + ":" + req.requestedByName + ":" + req.options.get("prefer"));
            TxRunner.Result r = entry.pay(new PaySpec(req.order.id, req.account), null);
            return CompletableFuture.completedFuture(Reply.paid(r.paid.payment.info()));
        });
        Reply p = PayRequests.dispatch(request(o, AccountRef.player(ALICE)), players, mods::get).join();
        assertSame(Reply.Kind.REDIRECT, p.kind);
        assertSame(OrderInfo.Status.OPEN, ledger.order(o.id).status(T0), "玩家的处理者还没确认：没付");
        Reply q = PayRequests.dispatch(request(o, QING), players, mods::get).join();
        assertSame(Reply.Kind.PAID, q.kind);
        assertEquals(ledger.order(o.id).paymentId, q.paymentId);
        assertEquals(java.util.Arrays.asList("player:player:" + ALICE, "yrqing:virtual:yrqing:qing:Alice:emerald"), seen);
        assertEquals(Amount.yuan(16), ledger.balance(QING));
    }

    /**
     * 没有处理者（清不听请求）：什么都不发生，回“等主人确认”（不是错），单子照旧开着；之后直接调入口（清的管理接口）能付。
     * 处理者出错、不给结果也一样。
     */
    @Test
    void unhandledRequestLeavesOrderOpen() throws Exception {
        fundQing(20);
        Ledger.Order o = dadoOrder(2);
        PaymentRequestHandler players = req -> {
            throw new AssertionError("不是玩家账户");
        };
        Reply r = PayRequests.dispatch(request(o, QING), players, id -> null).join();
        assertSame(Reply.Kind.PENDING, r.kind);
        assertTrue(r.message.contains("“清”的主人确认"), r.message);
        assertSame(OrderInfo.Status.OPEN, ledger.order(o.id).status(T0));
        assertEquals(Amount.yuan(20), ledger.balance(QING));
        assertTrue(paid.isEmpty());
        Map<String, PaymentRequestHandler> broken = new HashMap<>();
        broken.put("yrqing", req -> {
            throw new IllegalStateException("模组出错了");
        });
        assertSame(Reply.Kind.PENDING, PayRequests.dispatch(request(o, QING), players, broken::get).join().kind);
        broken.put("yrqing", req -> null);
        assertSame(Reply.Kind.PENDING, PayRequests.dispatch(request(o, QING), players, broken::get).join().kind);
        assertSame(OrderInfo.Status.OPEN, ledger.order(o.id).status(T0));
        // 单子还能取消、照常过期；这里直接调入口付
        TxRunner.Result later = entry.pay(new PaySpec(o.id, QING).requestId("qing-later"), null);
        assertSame(TxRunner.Result.Status.DONE, later.status, String.valueOf(later.error));
        assertSame(OrderInfo.Status.PAID, ledger.order(o.id).status(T0));
        assertEquals(1, paid.size());
        assertFalse(later.paid.replayed);
    }
}
