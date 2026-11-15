package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.Currency.Denomination;
import moe.qingu.youran.api.pay.PayItem;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyPlanTest {
    static final PayItem DIAMOND = PayItem.of("minecraft:diamond", 0);

    static Map<Denomination, Long> avail(long emerald, long ten, long five, long coin, long note) {
        Map<Denomination, Long> m = new EnumMap<>(Denomination.class);
        m.put(Denomination.EMERALD, emerald);
        m.put(Denomination.TEN, ten);
        m.put(Denomination.FIVE, five);
        m.put(Denomination.ONE_COIN, coin);
        m.put(Denomination.ONE_NOTE, note);
        return m;
    }

    static long n(CurrencyPlan.Pick p, Denomination d) {
        Long v = p.take.get(d);
        return v == null ? 0 : v;
    }

    /** 15 元可以用十元 + 五元（服主 11-15 的例子） */
    @Test
    void fifteenWithTenAndFive() {
        CurrencyPlan.Pick p = CurrencyPlan.pick(avail(0, 1, 1, 0, 0), 15, CurrencyPlan.Prefer.YUAN);
        assertEquals(1, n(p, Denomination.TEN));
        assertEquals(1, n(p, Denomination.FIVE));
        assertEquals(0, p.change);
    }

    /** 20 绿宝石的价（40 元）用绿宝石付正好；先用悠然元时用悠然元 */
    @Test
    void preferDecidesWhichKind() {
        Map<Denomination, Long> a = avail(30, 4, 0, 0, 0);
        CurrencyPlan.Pick e = CurrencyPlan.pick(a, 40, CurrencyPlan.Prefer.EMERALD);
        assertEquals(20, n(e, Denomination.EMERALD));
        assertEquals(0, n(e, Denomination.TEN));
        CurrencyPlan.Pick y = CurrencyPlan.pick(a, 40, CurrencyPlan.Prefer.YUAN);
        assertEquals(4, n(y, Denomination.TEN));
        assertEquals(0, n(y, Denomination.EMERALD));
    }

    /** 混着付：绿宝石不够时用悠然元补上 */
    @Test
    void mixed() {
        CurrencyPlan.Pick p = CurrencyPlan.pick(avail(5, 0, 1, 2, 0), 17, CurrencyPlan.Prefer.EMERALD);
        assertEquals(17, p.value());
        assertEquals(0, p.change);
        assertEquals(5, n(p, Denomination.EMERALD));
        assertEquals(1, n(p, Denomination.FIVE));
        assertEquals(2, n(p, Denomination.ONE_COIN));
    }

    /** 凑不出正好时找零最少：只有十元付 3 元，找 7 元（五元 + 两个一元） */
    @Test
    void changeIsMinimal() {
        CurrencyPlan.Pick p = CurrencyPlan.pick(avail(0, 2, 0, 0, 0), 3, CurrencyPlan.Prefer.YUAN);
        assertEquals(1, n(p, Denomination.TEN));
        assertEquals(7, p.change);
        Map<PayItem, Long> change = CurrencyPlan.change(p.change);
        assertEquals(Long.valueOf(1), change.get(Denomination.FIVE.item));
        assertEquals(Long.valueOf(2), change.get(Denomination.ONE_NOTE.item));
        // 奇数价只有绿宝石：多付 1 元，找一元
        CurrencyPlan.Pick q = CurrencyPlan.pick(avail(10, 0, 0, 0, 0), 7, CurrencyPlan.Prefer.EMERALD);
        assertEquals(4, n(q, Denomination.EMERALD));
        assertEquals(1, q.change);
        // 有一元时就不用找零
        CurrencyPlan.Pick r = CurrencyPlan.pick(avail(10, 0, 0, 1, 0), 7, CurrencyPlan.Prefer.EMERALD);
        assertEquals(3, n(r, Denomination.EMERALD));
        assertEquals(1, n(r, Denomination.ONE_COIN));
        assertEquals(0, r.change);
    }

    /** 一元先拿硬币再拿纸币；钱不够返回 null */
    @Test
    void onesAndShortage() {
        CurrencyPlan.Pick p = CurrencyPlan.pick(avail(0, 0, 0, 2, 5), 4, CurrencyPlan.Prefer.YUAN);
        assertEquals(2, n(p, Denomination.ONE_COIN));
        assertEquals(2, n(p, Denomination.ONE_NOTE));
        assertNull(CurrencyPlan.pick(avail(1, 1, 0, 0, 0), 13, CurrencyPlan.Prefer.YUAN));
        assertEquals(0, CurrencyPlan.pick(avail(0, 0, 0, 0, 0), 0, CurrencyPlan.Prefer.YUAN).value());
    }

    /** 各种组合都凑够、找零最少（和暴力枚举对比） */
    @Test
    void matchesBruteForce() {
        java.util.Random rnd = new java.util.Random(7);
        for (int round = 0; round < 400; round++) {
            long e = rnd.nextInt(6), t = rnd.nextInt(4), f = rnd.nextInt(4), c = rnd.nextInt(4), o = rnd.nextInt(3);
            long total = e * 2 + t * 10 + f * 5 + c + o;
            long target = 1 + rnd.nextInt(45);
            CurrencyPlan.Pick p = CurrencyPlan.pick(avail(e, t, f, c, o), target, rnd.nextBoolean() ? CurrencyPlan.Prefer.YUAN : CurrencyPlan.Prefer.EMERALD);
            if (total < target) {
                assertNull(p);
                continue;
            }
            long best = Long.MAX_VALUE;
            for (int a = 0; a <= e; a++) for (int b = 0; b <= t; b++) for (int d = 0; d <= f; d++) for (int g = 0; g <= c + o; g++) {
                long v = a * 2 + b * 10 + d * 5 + g;
                if (v >= target) {
                    best = Math.min(best, v - target);
                }
            }
            assertEquals(best, p.change, "第 " + round + " 组");
            assertEquals(target + p.change, p.value());
            assertTrue(n(p, Denomination.EMERALD) <= e && n(p, Denomination.TEN) <= t && n(p, Denomination.FIVE) <= f
                    && n(p, Denomination.ONE_COIN) <= c && n(p, Denomination.ONE_NOTE) <= o);
        }
    }

    /** 提现分面额：要绿宝石时零头给一元；要元时从大到小 */
    @Test
    void withdrawSplit() {
        Map<PayItem, Long> em = CurrencyPlan.split(41, Currency.Unit.EMERALD);
        assertEquals(Long.valueOf(20), em.get(Denomination.EMERALD.item));
        assertEquals(Long.valueOf(1), em.get(Denomination.ONE_NOTE.item));
        assertEquals(2, em.size());
        Map<PayItem, Long> yu = CurrencyPlan.split(38, Currency.Unit.YUAN);
        assertEquals(Long.valueOf(3), yu.get(Denomination.TEN.item));
        assertEquals(Long.valueOf(1), yu.get(Denomination.FIVE.item));
        assertEquals(Long.valueOf(3), yu.get(Denomination.ONE_NOTE.item));
        assertEquals(Amount.yuan(38), Amount.ofItems(yu));
        assertEquals(Amount.yuan(41), Amount.ofItems(em));
        assertTrue(CurrencyPlan.split(0, Currency.Unit.YUAN).isEmpty());
    }

    /** 付款计划：别的物品照单拿，钱凑好；不够时说还差多少 */
    @Test
    void paymentPlan() {
        Map<PayItem, Long> inv = new LinkedHashMap<>();
        inv.put(DIAMOND, 5L);
        inv.put(Denomination.EMERALD.item, 3L);
        inv.put(Denomination.TEN.item, 1L);
        Amount price = Amount.of(15, Collections.singletonMap(DIAMOND, 2L));
        CurrencyPlan.PayPlan p = CurrencyPlan.planPayment(price, inv, CurrencyPlan.Prefer.EMERALD);
        assertTrue(p.ok());
        assertEquals(Long.valueOf(2), p.take.get(DIAMOND));
        assertEquals(Amount.of(15 + p.changeYuan, Collections.singletonMap(DIAMOND, 2L)), Amount.ofItems(p.take));
        assertEquals(p.changeYuan, Amount.ofItems(p.change).yuan);
        CurrencyPlan.PayPlan bad = CurrencyPlan.planPayment(Amount.of(30, Collections.singletonMap(DIAMOND, 7L)), inv, CurrencyPlan.Prefer.YUAN);
        assertFalse(bad.ok());
        assertEquals(14, bad.lack.yuan);
        assertEquals(2, bad.lack.count(DIAMOND));
    }
}
