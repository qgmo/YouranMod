package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.dns.DefaultRegistrar;
import moe.qingu.youran.api.network.dns.DnsServer;
import moe.qingu.youran.api.network.dns.Registration;
import moe.qingu.youran.api.network.stack.TestLink;
import moe.qingu.youran.network.DnsState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** .com 只收以模组身份注册的 */
class ComRegistrarTest {
    @TempDir
    File dir;

    @Test
    void refusesRegistrarIdentity() {
        DnsState.reset();
        TestLink link = new TestLink();
        DnsServer dns = DnsServer.start(link.host("fd00::2:53"));
        ComRegistrar com = new ComRegistrar("youranmod", dns, new File(dir, "com.json"));
        List<Ipv6Address> ns = Collections.singletonList(Ipv6Address.parse("fd00::c:53"));
        DefaultRegistrar sub = new DefaultRegistrar("someone", "example.com", dns, new File(dir, "sub.json"));
        Registration r = com.register("example.com", sub, ns);
        assertEquals(Registration.Status.FAILURE, r.status());
        assertEquals(ComRegistrar.REFUSED, r.reason());
        assertNull(com.whois("example.com"));
        assertEquals(Registration.Status.SUCCESS, com.register("wixsite.com", "youranscp", ns).status());
        assertEquals(Registration.Status.FAILURE, com.register("wixsite.com", "other", ns).status());
    }

    @Test
    void secondLevelOfName() {
        assertEquals("pay.yr", NetworkServer.secondLevel("docs.pay.yr"));
        assertEquals("180.yr", NetworkServer.secondLevel("180.yr"));
        assertEquals("yr", NetworkServer.secondLevel("yr"));
    }
}
