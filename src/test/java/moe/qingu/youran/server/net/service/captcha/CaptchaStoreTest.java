package moe.qingu.youran.server.net.service.captcha;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证码题库：一次性、过期、上限、不分大小写、字符表 */
class CaptchaStoreTest {
    final CaptchaStore store = new CaptchaStore(new Random(1), 10, 3);

    String answer(String id) {
        return store.peek(id, 0).answer;
    }

    @Test
    void answersUseTheAlphabetAndIdsAreHex() {
        Set<Character> seen = new HashSet<>();
        CaptchaStore big = new CaptchaStore(new Random(2), 5000, 5000);
        for (int i = 0; i < 400; i++) {
            String id = big.create("s", CaptchaStore.Pool.GAME, 0).id;
            assertTrue(CaptchaStore.validId(id), id);
            String a = big.peek(id, 0).answer;
            assertEquals(CaptchaStore.LENGTH, a.length());
            for (char c : a.toCharArray()) {
                assertTrue(CaptchaStore.ALPHABET.indexOf(c) >= 0, a);
                seen.add(c);
            }
        }
        assertEquals(CaptchaStore.ALPHABET.length(), seen.size(), "字符表里的字都会出");
        for (char bad : "0OQ1IL2Z5S8B6GUV".toCharArray()) {
            if (bad != '6') {
                assertFalse(CaptchaStore.ALPHABET.indexOf(bad) >= 0, "易混字符 " + bad);
            }
        }
        for (char c : CaptchaStore.ALPHABET.toCharArray()) {
            assertTrue(CaptchaImage.hasGlyph(c), "没有字形：" + c);
        }
    }

    @Test
    void eachChallengeVerifiesOnceCaseInsensitive() {
        String id = store.create("a", CaptchaStore.Pool.GAME, 0).id;
        String ans = answer(id);
        assertTrue(store.verify(id, "  " + ans.toLowerCase() + " ", 1000));
        assertFalse(store.verify(id, ans, 1000), "核对过就作废");
        assertNull(store.peek(id, 1000), "图也取不到了");

        String id2 = store.create("a", CaptchaStore.Pool.GAME, 0).id;
        String ans2 = answer(id2);
        assertFalse(store.verify(id2, "XXXXX".equals(ans2) ? "YYYYY" : "XXXXX", 10));
        assertFalse(store.verify(id2, ans2, 10), "答错一次也作废");
        assertFalse(store.verify("not-an-id", "x", 10));
        assertFalse(store.verify(null, null, 10));
    }

    @Test
    void expiresAfterFiveMinutes() {
        String id = store.create("a", CaptchaStore.Pool.GAME, 0).id;
        String ans = answer(id);
        assertNotNull(store.peek(id, CaptchaStore.TTL_MS - 1));
        assertNull(store.peek(id, CaptchaStore.TTL_MS));
        assertFalse(store.verify(id, ans, CaptchaStore.TTL_MS));
        assertEquals(0, store.size());
    }

    @Test
    void perSourceAndPerPoolLimits() {
        String first = store.create("a", CaptchaStore.Pool.GAME, 0).id;
        store.create("a", CaptchaStore.Pool.GAME, 1);
        store.create("a", CaptchaStore.Pool.GAME, 2);
        assertEquals(3, store.size());
        String fourth = store.create("a", CaptchaStore.Pool.GAME, 3).id;
        assertNotNull(fourth);
        assertNull(store.peek(first, 3), "同一来源超过 3 题时它最早的作废");
        assertEquals(3, store.size());
        // 别的来源把游戏内的池子（10 题）填满
        for (int i = 0; i < 7; i++) {
            assertNotNull(store.create("b" + i, CaptchaStore.Pool.GAME, 4).id);
        }
        CaptchaStore.Created full = store.create("c", CaptchaStore.Pool.GAME, 5);
        assertNull(full.id);
        assertTrue(full.full);
        // 本机端口的池子是分开的，不受影响；本机来源不受每来源 3 题的限制
        for (int i = 0; i < 10; i++) {
            assertNotNull(store.create(CaptchaLocalServer.SOURCE, CaptchaStore.Pool.LOCAL, 6).id);
        }
        assertNull(store.create(CaptchaLocalServer.SOURCE, CaptchaStore.Pool.LOCAL, 7).id);
        // 过期以后又能出
        assertNotNull(store.create("c", CaptchaStore.Pool.GAME, CaptchaStore.TTL_MS + 10).id);
        assertEquals(1, store.size(CaptchaStore.Pool.GAME));
        assertEquals(0, store.size(CaptchaStore.Pool.LOCAL));
    }

    @Test
    void limiter() {
        SourceLimiter l = new SourceLimiter(1, 3, 2);
        assertTrue(l.tryAcquire("a", 0));
        assertTrue(l.tryAcquire("a", 0));
        assertTrue(l.tryAcquire("a", 0));
        assertFalse(l.tryAcquire("a", 0));
        assertFalse(l.tryAcquire("a", 500));
        assertTrue(l.tryAcquire("a", 1000));
        assertTrue(l.tryAcquire("b", 1000));
        assertTrue(l.tryAcquire("c", 1000));
        assertEquals(2, l.sources(), "来源数有上限");
    }
}
