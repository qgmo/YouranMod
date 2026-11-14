package moe.qingu.youran.api.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 地址的解析与 RFC 5952 写法 */
class Ipv6AddressTest {
    @Test
    void rfc5952Formatting() {
        assertEquals("::", Ipv6Address.parse("0:0:0:0:0:0:0:0").toString());
        assertEquals("::1", Ipv6Address.parse("0:0:0:0:0:0:0:1").toString());
        assertEquals("2001:db8::1", Ipv6Address.parse("2001:0DB8:0000:0000:0000:0000:0000:0001").toString());
        // 只有一段 0 不缩写
        assertEquals("2001:db8:0:1:1:1:1:1", Ipv6Address.parse("2001:db8:0:1:1:1:1:1").toString());
        // 一样长时缩写前面那段
        assertEquals("2001:db8::1:0:0:1", Ipv6Address.parse("2001:db8:0:0:1:0:0:1").toString());
        // 最长的那段
        assertEquals("2001:0:0:1::1", Ipv6Address.parse("2001:0:0:1:0:0:0:1").toString());
        assertEquals("fd12:3456:789a:1::53", Ipv6Address.parse("fd12:3456:789a:1:0:0:0:53").toString());
        assertEquals("1::", Ipv6Address.parse("1:0:0:0:0:0:0:0").toString());
    }

    @Test
    void parseForms() {
        Ipv6Address a = Ipv6Address.parse("[fd00::1]");
        assertEquals(0xfd00000000000000L, a.high());
        assertEquals(1, a.low());
        assertEquals(a, Ipv6Address.parse("fd00:0::0:1"));
        assertEquals(Ipv6Address.parse("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), new Ipv6Address(-1, -1));
    }

    @Test
    void rejectsBadText() {
        for (String s : new String[]{"", ":", "1::2::3", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "12345::", "g::1", "1:2:3:4:5:6:7::8", "::ffff:1.2.3.4", "test.yr"}) {
            assertThrows(IllegalArgumentException.class, () -> Ipv6Address.parse(s), s);
            assertNull(Ipv6Address.tryParse(s), s);
        }
    }

    @Test
    void bytesRoundTrip() {
        Ipv6Address a = Ipv6Address.parse("fd01:2345:6789:abcd:ef01:2345:6789:abcd");
        byte[] b = a.toBytes();
        assertEquals((byte) 0xfd, b[0]);
        assertEquals((byte) 0xcd, b[15]);
        assertEquals(a, Ipv6Address.read(b, 0));
        assertEquals(0xabcd, a.group(3));
        assertEquals(0xef01, a.group(4));
    }

    @Test
    void prefixes() {
        Ipv6Address p = Ipv6Address.parse("fd12:3456:789a::");
        assertTrue(Ipv6Address.parse("fd12:3456:789a:ffff::1").inPrefix(p, 48));
        assertFalse(Ipv6Address.parse("fd12:3456:789b::1").inPrefix(p, 48));
        assertTrue(Ipv6Address.parse("fd12:3456:789a:1:0:0:ffff:1").inPrefix(Ipv6Address.parse("fd12:3456:789a:1::"), 96));
        assertFalse(Ipv6Address.parse("fd12:3456:789a:1:0:1:0:1").inPrefix(Ipv6Address.parse("fd12:3456:789a:1::"), 96));
        assertTrue(p.inPrefix(p, 128));
        assertTrue(Ipv6Address.parse("ff02::1").isMulticast());
    }
}
