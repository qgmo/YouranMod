package moe.qingu.youran.api.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 国际化域名：转 A-label、来回一致、非法名字报错、防仿冒的显示规则 */
class DomainNamesTest {
    @Test
    void roundTrips() {
        assertEquals("xn--0zwm56d.yr", DomainNames.toAscii("测试.yr"));
        assertEquals("xn--ddu228a.yr", DomainNames.toAscii("悠然.yr"));
        String shop = DomainNames.toAscii("商店.悠然.yr");
        assertTrue(shop.matches("xn--[a-z0-9-]+\\.xn--ddu228a\\.yr"), shop);
        for (String n : new String[]{"测试.yr", "悠然.yr", "商店.悠然.yr", "minebay.yr", "café.yr", "日本語テスト.yr", "한국어.yr"}) {
            String a = DomainNames.toAscii(n);
            assertTrue(DomainNames.isAscii(a));
            assertEquals(n, DomainNames.toUnicode(a), n);
            assertEquals(a, DomainNames.toAscii(DomainNames.toUnicode(a)), "A-label → Unicode → A-label 不变");
            assertEquals(n, DomainNames.toDisplay(a), "单一文字、中日韩组合直接显示");
        }
        // 大小写、结尾点、全角句号、已经是 A-label
        assertEquals("minebay.yr", DomainNames.toAscii("MineBay.YR."));
        assertEquals("xn--0zwm56d.yr", DomainNames.toAscii("测试。yr"));
        assertEquals("xn--0zwm56d.yr", DomainNames.toAscii("XN--0ZWM56D.yr"));
        assertEquals("test.yr", DomainNames.toDisplay("test.yr"));
    }

    @Test
    void invalidNamesThrow() {
        for (String bad : new String[]{"", ".", "a..yr", "-a.yr", "a-.yr", "a_b.yr", "a b.yr", "a/b.yr",
                "xn--zz-invalid-\u0000.yr", "xn--a.yr",
                // 63 个字符以上的标签
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.yr",
                // Unicode 里还没分配的码位（不开 ALLOW_UNASSIGNED）
                "͸.yr"}) {
            assertThrows(IllegalArgumentException.class, () -> DomainNames.toAscii(bad), bad);
            assertNull(DomainNames.tryToAscii(bad));
        }
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longName.append("abcd.");
        }
        longName.append("yr");
        assertThrows(IllegalArgumentException.class, () -> DomainNames.toAscii(longName.toString()), "超过 253 个字符");
        assertThrows(IllegalArgumentException.class, () -> DomainNames.toAscii(null));
    }

    @Test
    void mixedScriptsShowALabel() {
        // 拉丁字母 a 混西里尔字母 а（U+0430）：仿冒 paypal 之类
        String spoof = DomainNames.toAscii("pаypal.yr");
        assertTrue(spoof.startsWith("xn--"));
        assertEquals(spoof, DomainNames.toDisplay(spoof));
        // 整个西里尔字母、整个希腊字母：单一文字，显示 Unicode
        assertEquals("пример.yr", DomainNames.toDisplay(DomainNames.toAscii("пример.yr")));
        assertEquals("δοκιμή.yr", DomainNames.toDisplay(DomainNames.toAscii("δοκιμή.yr")));
        // 汉字混西里尔：A-label；只有混的那一级显示 A-label，别的级照常
        String mixed = DomainNames.toAscii("商店.测试а.yr");
        assertEquals("商店." + mixed.split("\\.")[1] + ".yr", DomainNames.toDisplay(mixed));
        // 拉丁 + 汉字（中文里常见）可以
        assertTrue(DomainNames.isSafeToDisplay("mc百科"));
        assertTrue(DomainNames.isSafeToDisplay("ㄅㄆ注音"));
        assertFalse(DomainNames.isSafeToDisplay("한국ひらがな"));
        assertFalse(DomainNames.isSafeToDisplay("abcαβγ"));
        // 转不回来的 A-label 原样显示
        assertEquals("xn--a.yr", DomainNames.toDisplay("xn--a.yr"));
    }
}
