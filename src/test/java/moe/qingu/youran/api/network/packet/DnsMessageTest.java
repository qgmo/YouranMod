package moe.qingu.youran.api.network.packet;

import moe.qingu.youran.api.network.Ipv6Address;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DNS 报文（RFC 1035）的字节格式 */
class DnsMessageTest {
    @Test
    void queryBytesAreStandard() {
        byte[] b = DnsMessage.query(0x1234, "test.yr", DnsMessage.TYPE_AAAA, true).encode();
        byte[] want = {0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
                4, 't', 'e', 's', 't', 2, 'y', 'r', 0, 0, 28, 0, 1};
        assertArrayEquals(want, b);
    }

    @Test
    void responseRoundTripWithCompression() throws Exception {
        DnsMessage q = DnsMessage.query(7, "www.test.yr", DnsMessage.TYPE_AAAA, true);
        List<DnsMessage.Record> an = new ArrayList<>();
        an.add(DnsMessage.Record.cname("www.test.yr", 60, "test.yr"));
        an.add(DnsMessage.Record.aaaa("test.yr", 300, Ipv6Address.parse("fd00:1::2")));
        List<DnsMessage.Record> ns = Collections.singletonList(DnsMessage.Record.soa("yr", 60,
                new DnsMessage.Soa("ns.yr", "hostmaster.yr", 1, 2, 3, 4, 5)));
        List<DnsMessage.Record> ar = Collections.singletonList(DnsMessage.Record.ns("yr", 3600, "ns.yr"));
        DnsMessage r = DnsMessage.reply(q, true, true, DnsMessage.RCODE_OK, an, ns, ar);
        byte[] b = r.encode();
        DnsMessage back = DnsMessage.parse(b);
        assertTrue(back.response);
        assertTrue(back.authoritative);
        assertTrue(back.recursionDesired);
        assertTrue(back.recursionAvailable);
        assertEquals(7, back.id);
        assertEquals("www.test.yr", back.question().name);
        assertEquals("test.yr", back.answers.get(0).target);
        assertEquals(Ipv6Address.parse("fd00:1::2"), back.answers.get(1).address);
        assertEquals(5, back.authority.get(0).soa.minimum);
        assertEquals("hostmaster.yr", back.authority.get(0).soa.rname);
        assertEquals("ns.yr", back.additional.get(0).target);
        // 压缩过：名字只完整出现一次
        int count = 0;
        for (int i = 0; i + 4 < b.length; i++) {
            if (b[i] == 4 && b[i + 1] == 't' && b[i + 2] == 'e' && b[i + 3] == 's' && b[i + 4] == 't') {
                count++;
            }
        }
        assertEquals(1, count);
    }

    @Test
    void pointerLoopsAndForwardPointersAreRejected() {
        // 问题的名字是指向自己的指针
        byte[] loop = {0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, (byte) 0xC0, 12, 0, 28, 0, 1};
        assertThrows(MalformedPacketException.class, () -> DnsMessage.parse(loop));
        byte[] forward = {0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, (byte) 0xC0, 20, 0, 28, 0, 1, 0, 0};
        assertThrows(MalformedPacketException.class, () -> DnsMessage.parse(forward));
    }

    @Test
    void truncatedAndHugeCountsAreRejected() {
        byte[] b = DnsMessage.query(1, "test.yr", DnsMessage.TYPE_AAAA, true).encode();
        for (int n = 0; n < b.length; n++) {
            byte[] cut = new byte[n];
            System.arraycopy(b, 0, cut, 0, n);
            assertThrows(MalformedPacketException.class, () -> DnsMessage.parse(cut), "长度 " + n);
        }
        byte[] huge = b.clone();
        huge[6] = (byte) 0xFF;
        huge[7] = (byte) 0xFF;
        assertThrows(MalformedPacketException.class, () -> DnsMessage.parse(huge));
    }

    @Test
    void udpResponsesOver512AreTruncated() throws Exception {
        DnsMessage q = DnsMessage.query(1, "many.yr", DnsMessage.TYPE_AAAA, true);
        List<DnsMessage.Record> an = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            an.add(DnsMessage.Record.aaaa("many.yr", 1, new Ipv6Address(0xfd00L << 48, i)));
        }
        DnsMessage r = DnsMessage.reply(q, true, false, 0, an, Collections.<DnsMessage.Record>emptyList(), Collections.<DnsMessage.Record>emptyList());
        assertTrue(r.encode().length > 512);
        byte[] udp = r.encodeForUdp();
        assertTrue(udp.length <= 512);
        DnsMessage back = DnsMessage.parse(udp);
        assertTrue(back.truncated);
        assertEquals(0, back.answers.size());
    }

    @Test
    void names() {
        assertTrue(DnsMessage.isValidName(""));
        assertTrue(DnsMessage.isValidName("test.yr."));
        assertFalse(DnsMessage.isValidName("a..b"));
        assertFalse(DnsMessage.isValidName("has space.yr"));
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            label.append('a');
        }
        assertFalse(DnsMessage.isValidName(label + ".yr"));
        assertTrue(DnsMessage.isUnder("A.Test.YR", "test.yr"));
        assertTrue(DnsMessage.isUnder("test.yr", "yr"));
        assertFalse(DnsMessage.isUnder("latest.yr", "test.yr"));
        assertTrue(DnsMessage.isUnder("anything", ""));
        assertEquals("test.yr", DnsMessage.canonical("TEST.yr."));
    }
}
