package moe.qingu.youran.api.lang;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLangTest {
    /** 测试用的表：locale + "|" + key → 值 */
    private static LangSource table(String... localeKeyValue) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < localeKeyValue.length; i += 3) {
            m.put(localeKeyValue[i] + "|" + localeKeyValue[i + 1], localeKeyValue[i + 2]);
        }
        return new LangSource() {
            @Override
            public String find(String locale, String key) {
                return m.get(locale + "|" + key);
            }

            @Override
            public String summary() {
                return "测试";
            }
        };
    }

    @BeforeEach
    void reset() {
        ServerLang.resetForTest();
        Map<String, String> english = new HashMap<>();
        english.put("item.beef.cooked.name", "Steak");
        english.put("item.only.server.name", "Server Only");
        ServerLang.serverEnglish = english::get;
    }

    @AfterEach
    void after() {
        ServerLang.resetForTest();
    }

    @Test
    void beforeLoadingOnlyServerEnglishAndKeys() {
        assertFalse(ServerLang.isLoaded());
        assertEquals("Steak", ServerLang.get("zh_cn", "item.beef.cooked.name"));
        assertEquals("item.none.name", ServerLang.get("zh_cn", "item.none.name"));
        assertNull(ServerLang.find("zh_cn", "item.beef.cooked.name"));
    }

    @Test
    void fallbackChain() {
        ServerLang.start(() -> table("zh_cn", "item.beef.cooked.name", "牛排",
                "en_us", "item.mod.name", "Mod Item",
                "zh_tw", "item.beef.cooked.name", "牛排（繁）"), false);
        assertTrue(ServerLang.isLoaded());
        assertEquals("牛排", ServerLang.get("zh_cn", "item.beef.cooked.name"));
        assertEquals("牛排", ServerLang.get("zh_CN", "item.beef.cooked.name"), "语言代码不分大小写");
        assertEquals("牛排（繁）", ServerLang.get("zh-tw", "item.beef.cooked.name"));
        assertEquals("Mod Item", ServerLang.get("zh_cn", "item.mod.name"), "中文没有用表里的英文");
        assertEquals("Server Only", ServerLang.get("zh_tw", "item.only.server.name"), "再没有用服务端英文");
        assertEquals("Server Only", ServerLang.find("en_us", "item.only.server.name"));
        assertNull(ServerLang.find("zh_tw", "item.mod.name"), "find 不回退");
        assertEquals("item.none.name", ServerLang.get("zh_cn", "item.none.name"), "最后是键名");
        assertEquals("", ServerLang.get("zh_cn", null));
    }

    @Test
    void anyLocaleCodeIsSupported() {
        ServerLang.start(() -> table("ja_jp", "item.beef.cooked.name", "ステーキ",
                "fil_ph", "item.beef.cooked.name", "Steyk",
                "zh_cn", "item.beef.cooked.name", "牛排"), false);
        assertEquals("ステーキ", ServerLang.get("ja_jp", "item.beef.cooked.name"));
        assertEquals("ステーキ", ServerLang.find("JA-JP", "item.beef.cooked.name"));
        assertEquals("Steyk", ServerLang.get("fil_ph", "item.beef.cooked.name"));
        assertEquals("Steak", ServerLang.get("ko_kr", "item.beef.cooked.name"), "没有的语言退回服务端英文");
        assertEquals("ステーキ x", ServerLang.format("ja_jp", "item.beef.cooked.name") + " x");
        assertEquals(Arrays.asList("Steak", "牛排"), ServerLang.names("item.beef.cooked.name"), "names 仍只取英文、简体、繁体");
    }

    @Test
    void namesAreEnglishSimplifiedTraditionalDeduplicated() {
        ServerLang.start(() -> table("zh_cn", "item.beef.cooked.name", " 牛排 ",
                "zh_tw", "item.beef.cooked.name", "牛排",
                "zh_cn", "item.milk.name", "牛奶桶",
                "zh_tw", "item.milk.name", "牛奶桶（繁）",
                "zh_cn", "item.empty.name", "  "), false);
        assertEquals(Arrays.asList("Steak", "牛排"), ServerLang.names("item.beef.cooked.name"));
        assertEquals(Arrays.asList("牛奶桶", "牛奶桶（繁）", "Steak", "牛排"),
                ServerLang.names("item.milk.name", null, "item.beef.cooked.name", "item.empty.name", "item.none.name"));
        assertEquals(Collections.emptyList(), ServerLang.names());
        assertEquals(Collections.emptyList(), ServerLang.names((String[]) null));
    }

    @Test
    void format() {
        ServerLang.start(() -> table("zh_cn", "item.skull.player.name", "%s的头", "zh_cn", "bad", "%d 个"), false);
        assertEquals("Steve的头", ServerLang.format("zh_cn", "item.skull.player.name", "Steve"));
        assertEquals("%d 个", ServerLang.format("zh_cn", "bad", "x"), "对不上时不填");
        assertEquals("%s的头", ServerLang.format("zh_cn", "item.skull.player.name"));
    }

    @Test
    void callbacksOnEveryLoadAndImmediatelyWhenAlreadyLoaded() {
        AtomicInteger early = new AtomicInteger();
        ServerLang.onLoaded(early::incrementAndGet);
        assertEquals(0, early.get());
        ServerLang.start(() -> table(), false);
        assertEquals(1, early.get());
        assertEquals(1, ServerLang.version());
        AtomicInteger late = new AtomicInteger();
        ServerLang.onLoaded(late::incrementAndGet);
        assertEquals(1, late.get(), "已经加载过的马上调一次");
        ServerLang.onLoaded(() -> {
            throw new IllegalStateException("回调出错不影响别的");
        });
        ServerLang.start(() -> table(), false);
        assertEquals(2, early.get());
        assertEquals(2, late.get());
        assertEquals(2, ServerLang.version());
    }

    @Test
    void failedLoadKeepsOldTable() {
        ServerLang.start(() -> table("zh_cn", "k", "旧的"), false);
        ServerLang.start(() -> {
            throw new IllegalStateException("读坏了");
        }, false);
        assertTrue(ServerLang.isLoaded());
        assertEquals("旧的", ServerLang.get("zh_cn", "k"));
        assertEquals(2, ServerLang.version());
    }

    @Test
    void backgroundLoadAndOnlyLatestWins() throws InterruptedException {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> slowThread = new AtomicReference<>();
        AtomicInteger done = new AtomicInteger();
        ServerLang.onLoaded(done::incrementAndGet);
        ServerLang.start(() -> {
            slowThread.set(Thread.currentThread());
            slowStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return table("zh_cn", "k", "慢的旧表");
        }, true);
        // 等的都是明确的信号（超时只防卡死，不影响结果）
        assertTrue(slowStarted.await(10, TimeUnit.SECONDS));
        assertFalse(ServerLang.isLoaded(), "加载完成前");
        // 后开始的那次在当前线程上加载完
        ServerLang.start(() -> table("zh_cn", "k", "新表"), false);
        assertEquals("新表", ServerLang.get("zh_cn", "k"));
        assertEquals(1, done.get());
        // 放慢的那次走完（加载线程结束 = 它的 finish 已经执行完）
        release.countDown();
        slowThread.get().join(10_000);
        assertFalse(slowThread.get().isAlive());
        assertEquals(1, done.get(), "旧的那次加载完成后丢掉，不再回调");
        assertEquals("新表", ServerLang.get("zh_cn", "k"));
        assertEquals(1, ServerLang.version());
    }
}
