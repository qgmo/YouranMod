package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 系统确认框的生命周期：同意、拒绝、超时、换人、下线、重复、伪造回应、关服 */
class ConfirmPromptsTest {
    long now = 1_000_000;
    final Set<UUID> online = new HashSet<>();
    final List<String> sent = new ArrayList<>();
    final List<Long> shownIds = new ArrayList<>();
    final ConfirmPrompts prompts = new ConfirmPrompts(new ConfirmPrompts.Env() {
        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public boolean isOnline(UUID player) {
            return online.contains(player);
        }

        @Override
        public void show(UUID player, long id, ConfirmRequest request, int secondsLeft) {
            sent.add("show " + player + " " + request.site + " " + secondsLeft);
            shownIds.add(id);
        }

        @Override
        public void close(UUID player, long id) {
            sent.add("close " + player + " " + id);
        }
    }, new Random(7));

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    /** 设备现在的操作者 */
    final AtomicReference<UUID> operator = new AtomicReference<>();
    final ConfirmRequest req = new ConfirmRequest("用游戏角色登录", "shop.yr", Arrays.asList("UUID 与名字", "精确坐标"));

    ConfirmPromptsTest() {
        online.add(alice);
        online.add(bob);
        operator.set(alice);
    }

    CompletableFuture<ConfirmResult> ask() {
        return prompts.open(alice, "Alice", operator::get, req);
    }

    @Test
    void approveAndDeny() {
        CompletableFuture<ConfirmResult> f = ask();
        assertFalse(f.isDone());
        assertEquals("show " + alice + " shop.yr 60", sent.get(0));
        long id = shownIds.get(0);
        assertTrue(prompts.answer(alice, id, true));
        assertEquals(ConfirmResult.Outcome.APPROVED, f.join().outcome);
        assertEquals(alice, f.join().player);
        assertEquals("Alice", f.join().playerName);
        assertNull(prompts.pending(alice));
        // 同一题再回应一次：对不上
        assertFalse(prompts.answer(alice, id, true));

        CompletableFuture<ConfirmResult> g = ask();
        assertTrue(prompts.answer(alice, shownIds.get(1), false));
        assertEquals(ConfirmResult.Outcome.DENIED, g.join().outcome);
    }

    @Test
    void timeout() {
        CompletableFuture<ConfirmResult> f = ask();
        now += 59_999;
        prompts.tick();
        assertFalse(f.isDone());
        now += 1;
        prompts.tick();
        assertEquals(ConfirmResult.Outcome.TIMEOUT, f.join().outcome);
        assertTrue(sent.get(1).startsWith("close " + alice), "叫客户端关框");
        // 超时以后回应来了：对不上
        assertFalse(prompts.answer(alice, shownIds.get(0), true));
    }

    @Test
    void answerAfterDeadlineBeforeTickIsTimeout() {
        CompletableFuture<ConfirmResult> f = ask();
        now += 60_000;
        assertTrue(prompts.answer(alice, shownIds.get(0), true));
        assertEquals(ConfirmResult.Outcome.TIMEOUT, f.join().outcome);
    }

    @Test
    void operatorChangedOrLeft() {
        CompletableFuture<ConfirmResult> f = ask();
        operator.set(bob);
        prompts.tick();
        assertEquals(ConfirmResult.Outcome.OPERATOR_CHANGED, f.join().outcome, "换人");

        operator.set(alice);
        CompletableFuture<ConfirmResult> g = ask();
        operator.set(null);
        // 还没 tick 就收到回应：也按作废算，不能算同意
        assertTrue(prompts.answer(alice, shownIds.get(1), true));
        assertEquals(ConfirmResult.Outcome.OPERATOR_CHANGED, g.join().outcome, "离开电脑");

        operator.set(alice);
        CompletableFuture<ConfirmResult> h = ask();
        online.remove(alice);
        prompts.playerLeft(alice);
        assertEquals(ConfirmResult.Outcome.OPERATOR_CHANGED, h.join().outcome, "下线");
    }

    @Test
    void oneAtATimePerPlayer() {
        CompletableFuture<ConfirmResult> f = ask();
        CompletableFuture<ConfirmResult> second = ask();
        assertEquals(ConfirmResult.Outcome.BUSY, second.join().outcome);
        assertEquals(1, shownIds.size(), "第二个没有弹出来");
        // 别的玩家可以同时有自己的
        AtomicReference<UUID> bobDevice = new AtomicReference<>(bob);
        CompletableFuture<ConfirmResult> b = prompts.open(bob, "Bob", bobDevice::get, req);
        assertFalse(b.isDone());
        assertTrue(prompts.answer(alice, shownIds.get(0), true));
        assertTrue(f.join().approved());
        assertFalse(b.isDone());
    }

    /** 调用方撤回（id.yr 的“重新弹出”）：客户端的框关掉，同一个人马上能再弹；撤回的框再回应不算 */
    @Test
    void cancelledByCaller() {
        CompletableFuture<ConfirmResult> f = ask();
        long first = shownIds.get(0);
        f.cancel(false);
        CompletableFuture<ConfirmResult> again = ask();
        assertFalse(again.isDone(), "撤回的不再占着这个玩家");
        assertEquals(2, shownIds.size());
        assertTrue(sent.contains("close " + alice + " " + first), "叫客户端关掉撤回的框：" + sent);
        assertFalse(prompts.answer(alice, first, true), "撤回的框再回应不算");
        assertTrue(prompts.answer(alice, shownIds.get(1), true));
        assertTrue(again.join().approved());
        // 没人再弹时，下一 tick 也会关掉撤回的框
        CompletableFuture<ConfirmResult> third = ask();
        third.cancel(false);
        prompts.tick();
        assertNull(prompts.pending(alice));
        assertTrue(sent.contains("close " + alice + " " + shownIds.get(2)));
    }

    @Test
    void forgedAnswersAreIgnored() {
        CompletableFuture<ConfirmResult> f = ask();
        long id = shownIds.get(0);
        assertFalse(prompts.answer(bob, id, true), "别人替他回应");
        assertFalse(prompts.answer(alice, id + 1, true), "题号不对");
        assertFalse(prompts.answer(alice, 0, true));
        assertFalse(prompts.answer(null, id, true));
        assertFalse(f.isDone());
        assertTrue(prompts.answer(alice, id, false));
        assertEquals(ConfirmResult.Outcome.DENIED, f.join().outcome);
    }

    @Test
    void noOperatorAndShutdown() {
        operator.set(null);
        assertEquals(ConfirmResult.Outcome.NO_OPERATOR, ask().join().outcome, "设备现在没人操作");
        operator.set(bob);
        assertEquals(ConfirmResult.Outcome.NO_OPERATOR, ask().join().outcome, "问的不是现在的操作者");
        operator.set(alice);
        online.remove(alice);
        assertEquals(ConfirmResult.Outcome.NO_OPERATOR, ask().join().outcome, "不在线");
        online.add(alice);
        CompletableFuture<ConfirmResult> f = ask();
        prompts.clear();
        assertEquals(ConfirmResult.Outcome.UNAVAILABLE, f.join().outcome);
    }

    @Test
    void requestLimits() {
        assertThrows(IllegalArgumentException.class, () -> new ConfirmRequest("", "a.yr", null));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmRequest("标题", "bad site", null));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmRequest("标题§c", "a.yr", null), "颜色代码");
        assertThrows(IllegalArgumentException.class, () -> new ConfirmRequest("标题", "a.yr", Arrays.asList("x\ny")));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmRequest("标题", "a.yr", Arrays.asList("‮abc")), "文字方向控制");
        assertThrows(IllegalArgumentException.class, () -> new ConfirmRequest("标题", "a.yr", null, 3));
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= ConfirmRequest.MAX_ITEMS; i++) {
            many.add("条目" + i);
        }
        assertThrows(IllegalArgumentException.class, () -> new ConfirmRequest("标题", "a.yr", many));
        assertEquals("xn--ddu228a.yr", new ConfirmRequest("标题", "悠然.yr", null).site, "国际化域名转成 A-label");
    }
}
