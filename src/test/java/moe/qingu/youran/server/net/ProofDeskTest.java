package moe.qingu.youran.server.net;

import com.mojang.authlib.GameProfile;
import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmResult;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.identity.OperatorProof;
import moe.qingu.youran.api.network.identity.OperatorResult;
import moe.qingu.youran.api.network.identity.OperatorVerifier;
import moe.qingu.youran.api.network.location.LocationProof;
import moe.qingu.youran.api.network.location.LocationResult;
import moe.qingu.youran.api.network.location.LocationVerifier;
import moe.qingu.youran.server.net.identity.OperatorSigner;
import moe.qingu.youran.server.net.location.LocationSigner;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定位模块、身份模块的签名台（服主 11-27）：地址只取自网卡对象，确认框问调用方给的玩家，不同意不签，等确认时网卡拔掉不签。
 * 时间是固定的假时钟，确认框的结果由测试给。
 */
class ProofDeskTest {
    static final long NOW = 1_763_200_000_000L;
    static final String NONCE = "nonce-12345678";
    final UUID alice = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    final UUID bob = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    final GameProfile aliceProfile = new GameProfile(alice, "Alice");
    final Ipv6Address nicAddress = Ipv6Address.parse("fd12:3456:789a::c:4000:1e00");
    final Ipv6Address otherAddress = Ipv6Address.parse("fd12:3456:789a::c:4000:2e00");

    /** 记下问了谁、问了什么；结果由测试定 */
    static final class Asks implements ProofDesk.Env {
        final List<UUID> asked = new ArrayList<>();
        final List<ConfirmRequest> requests = new ArrayList<>();
        final List<CompletableFuture<ConfirmResult>> pending = new ArrayList<>();

        @Override
        public long nowMillis() {
            return NOW;
        }

        @Override
        public CompletableFuture<ConfirmResult> confirm(UUID player, ConfirmRequest request) {
            asked.add(player);
            requests.add(request);
            CompletableFuture<ConfirmResult> f = new CompletableFuture<>();
            pending.add(f);
            return f;
        }

        void answer(ConfirmResult.Outcome o, UUID who, String name) {
            pending.get(pending.size() - 1).complete(new ConfirmResult(o, who, name));
        }
    }

    final Asks env = new Asks();
    final ProofDesk desk = new ProofDesk(env);
    final LocationSigner locationSigner;
    final OperatorSigner operatorSigner;

    ProofDeskTest() throws Exception {
        locationSigner = new LocationSigner(LocationSigner.generate(new SecureRandom()));
        operatorSigner = new OperatorSigner(LocationSigner.generate(new SecureRandom()));
        desk.locationSigner = locationSigner;
        desk.operatorSigner = operatorSigner;
    }

    @Test
    void locationUsesTheNicAddressAndAsksTheGivenPlayer() throws Exception {
        TestNic nic = new TestNic();
        nic.plugAt(nicAddress);
        CompletableFuture<LocationResult> f = desk.location(nic, aliceProfile, 0, 120, 64, -35, "Shop.YR", NONCE);
        assertEquals(1, env.asked.size());
        assertEquals(alice, env.asked.get(0), "确认框给调用方指定的玩家");
        assertEquals("shop.yr", env.requests.get(0).site);
        assertFalse(f.isDone(), "同意之前不签");
        env.answer(ConfirmResult.Outcome.APPROVED, alice, "Alice");
        LocationResult r = f.get();
        assertTrue(r.ok());
        LocationProof p = LocationProof.parse(r.proof);
        assertEquals(nicAddress, p.device, "地址取自网卡对象");
        assertEquals(0, p.dimension);
        assertEquals(120, p.x);
        assertEquals(64, p.y);
        assertEquals(-35, p.z);
        assertEquals("shop.yr", p.site);
        assertEquals(NOW / 1000, p.time);
        assertTrue(new LocationVerifier(locationSigner.publicKey(), 300).verify(r.proof, "shop.yr", nicAddress, n -> n.equals(NONCE), NOW / 1000).ok());
        // 换一张网卡：签的是那张网卡的地址，原来那张的地址核不过
        TestNic other = new TestNic();
        other.plugAt(otherAddress);
        CompletableFuture<LocationResult> g = desk.location(other, aliceProfile, 0, 1, 2, 3, "shop.yr", NONCE);
        env.answer(ConfirmResult.Outcome.APPROVED, alice, "Alice");
        assertEquals(otherAddress, LocationProof.parse(g.get().proof).device);
        assertEquals("device", new LocationVerifier(locationSigner.publicKey(), 300).verify(g.get().proof, "shop.yr", nicAddress, null, NOW / 1000).reason);
    }

    @Test
    void notApprovedMeansNotSigned() throws Exception {
        TestNic nic = new TestNic();
        nic.plugAt(nicAddress);
        Object[][] cases = {
                {ConfirmResult.Outcome.DENIED, alice, LocationResult.Status.DENIED},
                {ConfirmResult.Outcome.TIMEOUT, alice, LocationResult.Status.TIMEOUT},
                {ConfirmResult.Outcome.OPERATOR_CHANGED, null, LocationResult.Status.OPERATOR_CHANGED},
                {ConfirmResult.Outcome.BUSY, alice, LocationResult.Status.BUSY},
                {ConfirmResult.Outcome.NO_OPERATOR, null, LocationResult.Status.NO_OPERATOR},
                // 回答的不是被问的人（不会发生，但不能签给他）
                {ConfirmResult.Outcome.APPROVED, bob, LocationResult.Status.OPERATOR_CHANGED},
        };
        int i = 0;
        for (Object[] c : cases) {
            // 每名玩家每 2 秒补 1 次、最多攒 3 次：换不同的玩家问，不碰频率限制
            UUID who = new UUID(0, 1000 + i++);
            CompletableFuture<LocationResult> f = desk.location(nic, new GameProfile(who, "P" + i), 0, 1, 2, 3, "shop.yr", NONCE);
            env.answer((ConfirmResult.Outcome) c[0], c[1] == alice ? who : (UUID) c[1], "x");
            LocationResult r = f.get();
            assertEquals(c[2], r.status, String.valueOf(c[0]));
            assertNull(r.proof);
        }
    }

    @Test
    void unpluggedNicSignsNothing() throws Exception {
        TestNic nic = new TestNic();
        assertEquals(LocationResult.Status.UNAVAILABLE, desk.location(nic, aliceProfile, 0, 1, 2, 3, "shop.yr", NONCE).get().status, "没插着：不问、不签");
        assertEquals(OperatorResult.Status.UNAVAILABLE, desk.operator(nic, aliceProfile, "id.yr", NONCE).get().status);
        assertTrue(env.asked.isEmpty());
        // 等确认的时候拔掉了
        nic.plugAt(nicAddress);
        CompletableFuture<OperatorResult> f = desk.operator(nic, aliceProfile, "id.yr", NONCE);
        nic.unplugFake();
        env.answer(ConfirmResult.Outcome.APPROVED, alice, "Alice");
        assertEquals(OperatorResult.Status.UNAVAILABLE, f.get().status);
        // 拔了又插到别处（地址变了）
        nic.plugAt(nicAddress);
        CompletableFuture<LocationResult> g = desk.location(nic, aliceProfile, 0, 1, 2, 3, "shop.yr", NONCE);
        nic.unplugFake();
        nic.plugAt(otherAddress);
        env.answer(ConfirmResult.Outcome.APPROVED, alice, "Alice");
        assertEquals(LocationResult.Status.UNAVAILABLE, g.get().status);
    }

    @Test
    void operatorProofNamesTheGivenPlayerAndNicAddress() throws Exception {
        TestNic nic = new TestNic();
        nic.plugAt(nicAddress);
        CompletableFuture<OperatorResult> f = desk.operator(nic, aliceProfile, "id.yr", NONCE);
        assertEquals(alice, env.asked.get(0), "确认框给调用方指定的玩家（电脑现在的操作者）");
        env.answer(ConfirmResult.Outcome.APPROVED, alice, "Alice");
        OperatorResult r = f.get();
        assertTrue(r.ok());
        OperatorProof p = OperatorProof.parse(r.proof);
        assertEquals(alice, p.player);
        assertEquals("Alice", p.name);
        assertEquals(nicAddress, p.address);
        assertEquals("id.yr", p.site);
        assertEquals(NONCE, p.nonce);
        assertTrue(new OperatorVerifier(operatorSigner.publicKey(), 300).verify(r.proof, "id.yr", nicAddress, n -> true, NOW / 1000).ok());
        // 定位模块的公钥核不过操作者证明（两把密钥分开）
        assertEquals("signature", new OperatorVerifier(locationSigner.publicKey(), 300).verify(r.proof, "id.yr", nicAddress, null, NOW / 1000).reason);
        // 不同意不签
        CompletableFuture<OperatorResult> g = desk.operator(nic, aliceProfile, "id.yr", NONCE);
        env.answer(ConfirmResult.Outcome.DENIED, alice, "Alice");
        assertEquals(OperatorResult.Status.DENIED, g.get().status);
        assertNull(g.get().proof);
    }

    @Test
    void badRequestsAndRateLimit() throws Exception {
        TestNic nic = new TestNic();
        nic.plugAt(nicAddress);
        assertEquals(LocationResult.Status.BAD_REQUEST, desk.location(nic, aliceProfile, 0, 1, 2, 3, "bad site!", NONCE).get().status);
        assertEquals(OperatorResult.Status.BAD_REQUEST, desk.operator(nic, aliceProfile, "id.yr", "short").get().status);
        assertEquals(OperatorResult.Status.NO_OPERATOR, desk.operator(nic, new GameProfile(alice, "bad\nname"), "id.yr", NONCE).get().status);
        assertTrue(env.asked.isEmpty());
        // 每名玩家最多攒 3 次（两种合起来），之后 BUSY
        desk.location(nic, aliceProfile, 0, 1, 2, 3, "shop.yr", NONCE);
        desk.operator(nic, aliceProfile, "id.yr", NONCE);
        desk.operator(nic, aliceProfile, "id.yr", NONCE);
        assertEquals(OperatorResult.Status.BUSY, desk.operator(nic, aliceProfile, "id.yr", NONCE).get().status);
        assertEquals(3, env.asked.size());
        // 没有签名密钥：不可用
        desk.operatorSigner = null;
        assertEquals(OperatorResult.Status.UNAVAILABLE, desk.operator(nic, new GameProfile(bob, "Bob"), "id.yr", NONCE).get().status);
    }
}
