package moe.qingu.youran.api.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /48 的划分与默认维度方案（X 20 有符号 + Y 8 + Z 20 有符号 + 8 位） */
class NetworkLayoutTest {
    private final NetworkLayout layout = NetworkLayout.fromGlobalId(0x123456789aL);
    private final DefaultDimensionNetwork dn = DefaultDimensionNetwork.INSTANCE;

    @Test
    void prefixIsUla() {
        assertEquals("fd12:3456:789a::", layout.prefix().toString());
        assertEquals(0x123456789aL, layout.globalId());
        assertTrue(layout.contains(Ipv6Address.parse("fd12:3456:789a:ffff::")));
        assertFalse(layout.contains(Ipv6Address.parse("fd12:3456:789b::")));
    }

    @Test
    void gameplayAddresses() {
        assertEquals("fd12:3456:789a:1::53", layout.hostAddress(0, 0x53).toString());
        assertEquals("fd12:3456:789a:1:0:2::", layout.datacenterPrefix(2).toString());
        Ipv6Address h = layout.hostAddress(0xffffffffL, 0xffffffffL);
        assertEquals(NetworkLayout.SUBNET_GAMEPLAY, layout.subnetOf(h));
        assertEquals(0xffffffffL, NetworkLayout.datacenterIndexOf(h));
        assertEquals(0xffffffffL, NetworkLayout.hostPartOf(h));
    }

    @Test
    void coordinateAddresses() {
        long bits = dn.encode(0, 64, 0, 0);
        Ipv6Address a = layout.coordinateAddress(1, bits);
        assertEquals(NetworkLayout.SUBNET_COORDINATES, layout.subnetOf(a));
        assertEquals(1, NetworkLayout.dimensionIndexOf(a));
        assertEquals(bits, NetworkLayout.dimensionBitsOf(a));
        assertEquals("fd12:3456:789a:0:100:4::", a.toString());
        assertEquals("fd12:3456:789a:0:ff00::", layout.dimensionPrefix(255).toString());
    }

    @Test
    void defaultDimensionRoundTrip() {
        int[][] cases = {{0, 0, 0}, {-1, 0, -1}, {524287, 255, 524287}, {-524288, 0, -524288}, {123, 64, -4567}, {-30000, 200, 30000}};
        for (int[] c : cases) {
            for (int slot : new int[]{0, 1, 255}) {
                long bits = dn.encode(c[0], c[1], c[2], slot);
                assertTrue(bits >= 0 && bits < (1L << 56));
                assertEquals(new DimensionNetwork.BlockSlot(c[0], c[1], c[2], slot), dn.decode(bits));
            }
        }
        // 负坐标的位：-1 是 20 个 1
        assertEquals(0xFFFFFL << 36, dn.encode(-1, 0, 0, 0));
        assertEquals(0xFFFFFL << 8, dn.encode(0, 0, -1, 0));
        assertEquals(255L << 28, dn.encode(0, 255, 0, 0));
    }

    @Test
    void outOfRangeHasNoAddress() {
        assertEquals(-1, dn.encode(524288, 0, 0, 0));
        assertEquals(-1, dn.encode(-524289, 0, 0, 0));
        assertEquals(-1, dn.encode(0, 0, 524288, 0));
        assertEquals(-1, dn.encode(0, 0, -524289, 0));
        assertEquals(-1, dn.encode(0, -1, 0, 0));
        assertEquals(-1, dn.encode(0, 256, 0, 0));
        assertEquals(-1, dn.encode(0, 0, 0, 256));
        assertEquals(-1, dn.encode(0, 0, 0, -1));
        assertEquals(-1, dn.encode(30_000_000, 64, 0, 0));
    }
}
