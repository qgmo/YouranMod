package moe.qingu.youran.server.net.nav;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 广告排队、计时、到期、下架退款、封禁、举报、存取 */
class AdBookTest {
    static final long DAY = AdRules.DAY_MS;
    final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    final UUID bob = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    int saves;
    final List<Integer> dropped = new ArrayList<>();
    /** 横幅 1 个、方块 2 个、文字 1 个、按钮 1 个；价格 4/2/1/1；每个位置后面排 1 条；最多 10 天 */
    final AdRules rules = new AdRules(1, 2, 1, 1, 4, 2, 1, 1, 1, 10);
    final AdBook book = new AdBook(rules, new AdBook.Listener() {
        @Override
        public void changed(AdBook b) {
            saves++;
        }

        @Override
        public void dropped(AdBook.Ad ad) {
            dropped.add(ad.id);
        }
    });

    AdBook.Ad buy(AdSlot slot, UUID who, int days, long tick, long ms) throws Exception {
        AdBook.Ad a = book.draft(slot, who, who.equals(alice) ? "Alice" : "Bob", days, "广告", "http://shop.yr/", tick, ms);
        book.attachOrder(a, "ord_" + a.id);
        assertTrue(book.paid(a, "pay_" + a.id, tick, ms));
        return a;
    }

    @Test
    void liveImmediatelyThenQueue() throws Exception {
        AdBook.Ad a = buy(AdSlot.BANNER, alice, 2, 1000, 1);
        assertEquals(AdBook.State.LIVE, a.state, "先上后审：付了马上上线");
        assertEquals(1000, a.startTick);
        assertEquals(1000 + 2 * DAY, a.endTick);
        assertEquals(16, a.priceYuan, "4 绿宝石 × 2 天 = 8 绿宝石 = 16 元");
        assertSame(a, book.live(AdSlot.BANNER)[0]);
        // 横幅只有 1 个位置，后面能排 1 条
        AdBook.Ad b = buy(AdSlot.BANNER, bob, 1, 2000, 2);
        assertEquals(AdBook.State.QUEUED, b.state);
        assertEquals(2 * DAY - 1000, book.waitTicks(b, 2000));
        assertEquals(-1, book.nextStart(AdSlot.BANNER, 2000), "排满了");
        AdBook.AdException e = assertThrows(AdBook.AdException.class, () -> book.draft(AdSlot.BANNER, alice, "Alice", 1, "", "http://x.yr/", 2000, 3));
        assertEquals("full", e.code);
        // 到期：a 下线，b 接上
        assertFalse(book.tick(1000 + 2 * DAY - 1, 4));
        assertTrue(book.tick(1000 + 2 * DAY, 5));
        assertEquals(AdBook.State.ENDED, a.state);
        assertEquals(AdBook.State.LIVE, b.state);
        assertEquals(1000 + 2 * DAY, b.startTick);
        assertEquals(1000 + 3 * DAY, b.endTick);
        book.tick(1000 + 3 * DAY, 6);
        assertEquals(AdBook.State.ENDED, b.state);
        assertNull(book.live(AdSlot.BANNER)[0]);
        assertEquals(0, book.nextStart(AdSlot.BANNER, 1000 + 3 * DAY));
    }

    @Test
    void choosesTheEarliestPosition() throws Exception {
        AdBook.Ad a = buy(AdSlot.SQUARE, alice, 5, 0, 1);
        AdBook.Ad b = buy(AdSlot.SQUARE, bob, 1, 0, 2);
        assertEquals(0, a.position);
        assertEquals(1, b.position, "空着的位置先用");
        // 都在播：下一条排到最早空出来的（b 的位置，1 天后）
        assertEquals(DAY, book.nextStart(AdSlot.SQUARE, 0));
        AdBook.Ad c = buy(AdSlot.SQUARE, alice, 1, 0, 3);
        assertEquals(1, c.position);
        assertEquals(AdBook.State.QUEUED, c.state);
        assertEquals(5 * DAY, book.nextStart(AdSlot.SQUARE, 0));
    }

    @Test
    void draftsHoldAPlaceUntilCanceled() throws Exception {
        AdBook.Ad d = book.draft(AdSlot.TEXT, alice, "Alice", 1, "便宜木头", "http://wood.yr/", 0, 1);
        assertEquals(AdBook.State.DRAFT, d.state);
        assertNull(book.live(AdSlot.TEXT)[0], "没付不上线");
        book.draft(AdSlot.TEXT, bob, "Bob", 1, "x", "http://x.yr/", 0, 1);
        // 文字链 1 个位置 + 排 1 条：两条待付款就满了
        assertThrows(AdBook.AdException.class, () -> book.draft(AdSlot.TEXT, bob, "Bob", 1, "y", "http://y.yr/", 0, 1));
        assertTrue(book.cancelDraft(d, 2));
        assertEquals(AdBook.State.CANCELED, d.state);
        book.draft(AdSlot.TEXT, bob, "Bob", 1, "y", "http://y.yr/", 0, 3);
        assertFalse(book.cancelDraft(d, 4), "作废的不再作废");
        // 一个人最多 3 条待付款
        book.draft(AdSlot.SQUARE, alice, "Alice", 1, "", "http://a.yr/", 0, 5);
        book.draft(AdSlot.SQUARE, alice, "Alice", 1, "", "http://a.yr/", 0, 5);
        book.draft(AdSlot.SQUARE, alice, "Alice", 1, "", "http://a.yr/", 0, 5);
        assertEquals("too_many_drafts", assertThrows(AdBook.AdException.class, () -> book.draft(AdSlot.BUTTON, alice, "Alice", 1, "", "http://a.yr/", 0, 5)).code);
        assertEquals("bad_days", assertThrows(AdBook.AdException.class, () -> book.draft(AdSlot.BUTTON, bob, "Bob", 11, "", "http://a.yr/", 0, 5)).code);
    }

    @Test
    void lateOrRepeatedPayment() throws Exception {
        AdBook.Ad d = book.draft(AdSlot.BUTTON, alice, "Alice", 1, "", "http://a.yr/", 0, 1);
        book.attachOrder(d, "ord_1");
        assertSame(d, book.byOrder("ord_1"));
        book.cancelDraft(d, 2);
        // 作废以后才收到付款（回调与对账赛跑）：照样上线，钱不白付
        assertTrue(book.paid(d, "pay_1", 100, 3));
        assertEquals(AdBook.State.LIVE, d.state);
        assertFalse(book.paid(d, "pay_1", 100, 4), "重复的回调不再处理");
    }

    @Test
    void takeDownWithRefunds() throws Exception {
        AdBook.Ad a = buy(AdSlot.BANNER, alice, 4, 0, 1);
        AdBook.Ad b = buy(AdSlot.BANNER, bob, 2, 0, 2);
        // 播了 1 天（共 4 天，32 元）：没播的 24 元
        assertEquals(24, book.unusedYuan(a, DAY));
        assertEquals(23, book.unusedYuan(a, DAY + 1), "往下取整");
        assertEquals(b.priceYuan, book.unusedYuan(b, DAY), "排队的全退");
        assertTrue(book.remove(a, "Admin", "违规", 24, DAY, 5));
        assertEquals(AdBook.State.REMOVED, a.state);
        assertEquals(24, a.refundedYuan);
        assertEquals("违规", a.removeReason);
        assertEquals(AdBook.State.LIVE, b.state, "下架后下一条马上接上");
        assertEquals(DAY, b.startTick);
        assertFalse(book.remove(a, "Admin", "", 0, DAY, 6));
        assertEquals(0, book.unusedYuan(a, DAY));
        // 排队中的也能下架
        AdBook.Ad c = buy(AdSlot.BANNER, alice, 1, DAY, 7);
        assertEquals(AdBook.State.QUEUED, c.state);
        assertTrue(book.remove(c, "Admin", "", 0, DAY, 8));
        assertEquals(AdBook.State.LIVE, b.state);
    }

    @Test
    void bansAndReports() throws Exception {
        AdBook.Ad a = buy(AdSlot.SQUARE, bob, 1, 0, 1);
        book.ban(bob, "Bob", "骗人", "Admin", 2);
        assertTrue(book.isBanned(bob));
        assertEquals("banned", assertThrows(AdBook.AdException.class, () -> book.draft(AdSlot.SQUARE, bob, "Bob", 1, "", "http://a.yr/", 0, 3)).code);
        assertEquals(AdBook.State.LIVE, a.state, "封禁不自动下架");
        assertTrue(book.unban(bob));
        assertFalse(book.unban(bob));
        book.draft(AdSlot.SQUARE, bob, "Bob", 1, "", "http://a.yr/", 0, 4);
        for (int i = 0; i < AdBook.MAX_REPORTS; i++) {
            assertTrue(book.report(a, "骗人", "X", i));
        }
        assertFalse(book.report(a, "骗人", "X", 99), "举报满了");
    }

    @Test
    void saveAndLoad() throws Exception {
        AdBook.Ad a = buy(AdSlot.SQUARE, alice, 3, 500, 10);
        AdBook.Ad b = buy(AdSlot.TEXT, bob, 1, 500, 11);
        book.report(a, "其他：太丑", "Bob @fd00::1", 12);
        book.ban(bob, "Bob", "刷屏", "Admin", 13);
        book.remove(b, "Admin", "刷屏", 2, 600, 14);
        AdBook.Ad d = book.draft(AdSlot.BUTTON, alice, "Alice", 2, "", "http://a.yr/", 600, 15);
        JsonObject j = book.toJson();
        AdBook copy = new AdBook(rules, x -> { });
        List<String> problems = new ArrayList<>();
        copy.load(j, problems);
        assertEquals(new ArrayList<String>(), problems);
        AdBook.Ad a2 = copy.get(a.id);
        assertEquals(AdBook.State.LIVE, a2.state);
        assertEquals(500 + 3 * AdRules.DAY_MS, a2.endTick);
        assertEquals("pay_" + a.id, a2.paymentId);
        assertEquals(1, a2.reports.size());
        assertEquals("刷屏", copy.get(b.id).removeReason);
        assertEquals(AdBook.State.DRAFT, copy.get(d.id).state);
        assertTrue(copy.isBanned(bob));
        assertEquals(j.toString(), copy.toJson().toString());
        // 新编号接着来
        assertTrue(copy.draft(AdSlot.BUTTON, alice, "Alice", 1, "", "http://a.yr/", 0, 16).id > d.id);
    }

    @Test
    void fewerPositionsAfterConfigChange() throws Exception {
        AdBook.Ad a = buy(AdSlot.SQUARE, alice, 1, 0, 1);
        AdBook.Ad b = buy(AdSlot.SQUARE, bob, 1, 0, 2);
        assertEquals(1, b.position);
        AdBook smaller = new AdBook(new AdRules(1, 1, 1, 1, 4, 2, 1, 1, 1, 10), x -> { });
        List<String> problems = new ArrayList<>();
        smaller.load(book.toJson(), problems);
        assertEquals(1, problems.size());
        AdBook.Ad b2 = smaller.get(b.id);
        assertEquals(0, b2.position);
        assertEquals(AdBook.State.QUEUED, b2.state, "挪了位置重新排，不丢");
        smaller.tick(DAY, 3);
        assertEquals(AdBook.State.LIVE, b2.state);
    }

    @Test
    void pruneOldFinished() throws Exception {
        AdBook.Ad a = buy(AdSlot.BUTTON, alice, 1, 0, 1);
        book.tick(DAY, 1000);
        assertEquals(AdBook.State.ENDED, a.state);
        book.tick(DAY + 20, 1000 + AdBook.KEEP_FINISHED_MS + 1);
        assertNull(book.get(a.id));
        assertArrayEquals(new Object[]{a.id}, dropped.toArray());
    }
}
