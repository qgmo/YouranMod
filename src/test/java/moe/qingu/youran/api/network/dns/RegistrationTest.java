package moe.qingu.youran.api.network.dns;

import moe.qingu.youran.api.network.Ipv6Address;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册结果：成功、失败、等待（取消、回调） */
class RegistrationTest {
    static final Whois INFO = new Whois("shop.yr", null, "mymod", false, null,
            Collections.singletonList(Ipv6Address.parse("fd00::1")), 0);

    @Test
    void successAndFailure() {
        Registration ok = Registration.success(INFO);
        assertEquals(Registration.Status.SUCCESS, ok.status());
        assertSame(INFO, ok.whois());
        assertNull(ok.reason());
        Registration no = Registration.failure("已经被别人注册了");
        assertEquals(Registration.Status.FAILURE, no.status());
        assertEquals("已经被别人注册了", no.reason());
        assertNull(no.whois());
        // 定下来的不能再改、不能取消
        assertFalse(ok.fail("x"));
        assertFalse(no.cancel());
        assertEquals(Registration.Status.SUCCESS, ok.status());
    }

    @Test
    void doneResultCallsBackImmediately() {
        List<Registration> got = new ArrayList<>();
        Registration ok = Registration.success(INFO);
        ok.whenDone(got::add);
        assertEquals(Collections.singletonList(ok), got);
    }

    @Test
    void pendingThenSuccessCallsBackOnce() {
        Registration p = Registration.pending(null);
        List<Registration.Status> got = new ArrayList<>();
        p.whenDone(r -> got.add(r.status()));
        p.whenDone(r -> got.add(r.status()));
        assertEquals(Registration.Status.PENDING, p.status());
        assertTrue(got.isEmpty());
        assertTrue(p.succeed(INFO));
        assertEquals(Collections.nCopies(2, Registration.Status.SUCCESS), got);
        assertSame(INFO, p.whois());
        // 第二次定不了，回调不再调
        assertFalse(p.fail("晚了"));
        assertFalse(p.cancel());
        assertEquals(2, got.size());
    }

    @Test
    void pendingThenFailure() {
        Registration p = Registration.pending(null);
        List<String> got = new ArrayList<>();
        p.whenDone(r -> got.add(r.reason()));
        assertTrue(p.fail("审核没过"));
        assertEquals(Collections.singletonList("审核没过"), got);
        assertEquals(Registration.Status.FAILURE, p.status());
    }

    @Test
    void cancelTellsRegistrarAndCallsBack() {
        AtomicInteger withdrawn = new AtomicInteger();
        Registration p = Registration.pending(withdrawn::incrementAndGet);
        List<Registration> got = new ArrayList<>();
        p.whenDone(got::add);
        assertTrue(p.cancel());
        assertEquals(1, withdrawn.get());
        assertEquals(Registration.Status.FAILURE, p.status());
        assertEquals(Registration.CANCELLED, p.reason());
        assertEquals(1, got.size());
        // 取消以后注册局再定也不算
        assertFalse(p.succeed(INFO));
        assertFalse(p.cancel());
        assertEquals(1, withdrawn.get());
        assertEquals(Registration.Status.FAILURE, p.status());
    }
}
