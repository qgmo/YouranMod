package moe.qingu.youran.api.pay;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmountTest {
    static final PayItem DIAMOND = PayItem.of("minecraft:diamond", 0);
    static final PayItem NAMED_EMERALD = new PayItem("minecraft:emerald", 0, "{\"display\":{\"Name\":\"假钱\"}}", null);

    /** 绿宝石与悠然元按价值合并成“钱”（1 绿宝石 = 2 元）；别的物品单独记 */
    @Test
    void currencyMerges() {
        Map<PayItem, Long> m = new LinkedHashMap<>();
        m.put(Currency.Denomination.EMERALD.item, 3L);
        m.put(Currency.Denomination.TEN.item, 1L);
        m.put(Currency.Denomination.FIVE.item, 1L);
        m.put(Currency.Denomination.ONE_COIN.item, 2L);
        m.put(Currency.Denomination.ONE_NOTE.item, 1L);
        m.put(DIAMOND, 4L);
        Amount a = Amount.ofItems(m);
        assertEquals(6 + 10 + 5 + 2 + 1, a.yuan);
        assertEquals(1, a.items.size());
        assertEquals(4, a.count(DIAMOND));
    }

    /** 改过名（有 NBT）、数据值不是 0 的绿宝石不是钱，是普通物品 */
    @Test
    void onlyPlainCurrency() {
        assertNull(Currency.Denomination.of(NAMED_EMERALD));
        assertNull(Currency.Denomination.of(PayItem.of("minecraft:emerald", 1)));
        assertSame(Currency.Denomination.EMERALD, Currency.Denomination.of(PayItem.of("minecraft:emerald", 0)));
        Amount a = Amount.item(NAMED_EMERALD, 2);
        assertEquals(0, a.yuan);
        assertEquals(2, a.count(NAMED_EMERALD));
    }

    @Test
    void arithmetic() {
        Amount a = Amount.of(10, java.util.Collections.singletonMap(DIAMOND, 3L));
        Amount b = Amount.of(4, java.util.Collections.singletonMap(DIAMOND, 1L));
        assertEquals(Amount.of(14, java.util.Collections.singletonMap(DIAMOND, 4L)), a.plus(b));
        assertEquals(Amount.of(6, java.util.Collections.singletonMap(DIAMOND, 2L)), a.minus(b));
        assertTrue(a.covers(b));
        assertFalse(b.covers(a));
        assertThrows(IllegalArgumentException.class, () -> b.minus(a));
        assertEquals(Amount.of(6, java.util.Collections.singletonMap(DIAMOND, 2L)), b.shortOf(a));
        assertTrue(a.minus(a).isZero());
        assertSame(Amount.ZERO, a.minus(a));
        assertThrows(IllegalArgumentException.class, () -> Amount.yuan(-1));
    }

    @Test
    void formatting() {
        assertEquals("20 绿宝石（40 元）", Currency.formatBoth(40, Currency.Unit.EMERALD));
        assertEquals("15 元（7.5 绿宝石）", Currency.formatBoth(15, Currency.Unit.YUAN));
        assertEquals(40, Currency.toYuan(20, Currency.Unit.EMERALD));
    }

    /** 收款单只能标一种价；绿宝石、悠然元当物品加进来也合进钱里 */
    @Test
    void orderSpec() {
        OrderSpec s = new OrderSpec(AccountRef.virtual("cfm:minebay"), "minebay.yr").emeralds(20);
        assertEquals(40, s.price().yuan);
        assertSame(Currency.Unit.EMERALD, s.unit());
        assertThrows(IllegalArgumentException.class, () -> s.yuan(3));
        s.item(Currency.Denomination.FIVE.item, 2).item(DIAMOND, 1);
        assertEquals(50, s.price().yuan);
        assertEquals(1, s.price().count(DIAMOND));
        OrderSpec y = new OrderSpec(AccountRef.virtual("cfm:minebay"), "minebay.yr").item(Currency.Denomination.TEN.item, 1);
        assertSame(Currency.Unit.YUAN, y.unit());
        assertThrows(IllegalArgumentException.class, () -> y.item(DIAMOND, 0));
    }

    @Test
    void accountRefs() {
        java.util.UUID u = java.util.UUID.randomUUID();
        assertEquals(AccountRef.player(u), AccountRef.parse("player:" + u));
        assertEquals(AccountRef.virtual("youranscp:dado"), AccountRef.parse("virtual:youranscp:dado"));
        assertThrows(IllegalArgumentException.class, () -> AccountRef.virtual("NoColon"));
        assertThrows(IllegalArgumentException.class, () -> AccountRef.parse("bank:1"));
    }
}
