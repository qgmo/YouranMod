package moe.qingu.youran.api.pay;

import moe.qingu.youran.network.PayHooks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 虚拟账户的显示名：检查与加载阶段登记 */
class DisplayNameTest {
    @Test
    void checks() {
        assertEquals("MineBay", PayApi.checkDisplayName("  MineBay "));
        assertEquals("180 广告", PayApi.checkDisplayName("180 广告"));
        assertEquals("一二三四五六七八九十一二三四五六七八九十一二三四", PayApi.checkDisplayName("一二三四五六七八九十一二三四五六七八九十一二三四"));
        assertThrows(IllegalArgumentException.class, () -> PayApi.checkDisplayName(null));
        assertThrows(IllegalArgumentException.class, () -> PayApi.checkDisplayName("  "));
        assertThrows(IllegalArgumentException.class, () -> PayApi.checkDisplayName("一二三四五六七八九十一二三四五六七八九十一二三四五"));
        assertThrows(IllegalArgumentException.class, () -> PayApi.checkDisplayName("a\nb"));
        assertThrows(IllegalArgumentException.class, () -> PayApi.checkDisplayName("§cred"));
        assertThrows(IllegalArgumentException.class, () -> PayApi.checkDisplayName("a‮b"));
    }

    /** 没开 pay.yr（加载阶段）也能登记；登记名写错时抛异常 */
    @Test
    void registersBeforeStart() {
        PayApi.setDisplayName("test:dn", " 测试店 ");
        assertEquals("测试店", PayHooks.displayName("test:dn"));
        assertThrows(IllegalArgumentException.class, () -> PayApi.setDisplayName("NotAKey", "x"));
    }
}
