package moe.qingu.youran.server.net.identity;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.http.HttpHeaders;
import moe.qingu.youran.api.network.http.HttpRequest;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.api.network.identity.OperatorProof;
import moe.qingu.youran.api.network.identity.OperatorVerifier;
import moe.qingu.youran.api.network.location.LocationProof;
import moe.qingu.youran.server.net.location.LocationSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 操作者证明：格式、签名与核对、篡改任一字段都不过、地址与网站、过期、密钥文件、operator.yr 的接口 */
class OperatorProofTest {
    static final Ipv6Address PC = Ipv6Address.parse("fd12:3456:789a::c:4000:1e00");
    static final UUID STEVE = UUID.fromString("0f8e1c2a-3b4d-4e5f-8a9b-0c1d2e3f4a5b");
    static final long NOW = 1_763_200_000L;
    final OperatorSigner signer;

    OperatorProofTest() throws Exception {
        signer = new OperatorSigner(LocationSigner.generate(new SecureRandom()));
    }

    String proof(String site, String nonce) throws Exception {
        return signer.sign(STEVE, "Steve", PC, NOW, site, nonce).text();
    }

    OperatorVerifier verifier() {
        return new OperatorVerifier(signer.publicKey(), 300);
    }

    @Test
    void signAndVerify() throws Exception {
        String text = proof("id.yr", "nonce-12345678");
        assertTrue(text.startsWith("youran-operator-proof v1\nuuid=0f8e1c2a-3b4d-4e5f-8a9b-0c1d2e3f4a5b\nname=Steve\naddress=fd12:3456:789a::c:4000:1e00\n"
                + "time=1763200000\nsite=id.yr\nnonce=nonce-12345678\nkey=" + signer.keyId() + "\nsignature="), text);
        OperatorVerifier.Result r = verifier().verify(text, "id.yr", PC, n -> n.equals("nonce-12345678"), NOW + 10);
        assertTrue(r.ok(), r.toString());
        assertEquals(STEVE, r.proof.player);
        assertEquals("Steve", r.proof.name);
        assertTrue(verifier().verify(text.replace("\n", "\r\n") + "  ", "id.yr", PC, null, NOW).ok(), "CRLF 照样能核");
    }

    @Test
    void tamperingAnyFieldFails() throws Exception {
        String text = proof("id.yr", "nonce-12345678");
        String[][] edits = {
                {"uuid=0f8e1c2a", "uuid=0f8e1c2b"},
                {"name=Steve", "name=Alex"},
                {"address=fd12:3456:789a::c:4000:1e00", "address=fd12:3456:789a::c:4000:1e01"},
                {"time=1763200000", "time=1763200001"},
                {"site=id.yr", "site=ie.yr"},
                {"nonce=nonce-12345678", "nonce=nonce-12345679"},
        };
        for (String[] e : edits) {
            String bad = text.replace(e[0], e[1]);
            assertEquals("signature", verifier().verify(bad, null, null, null, NOW).reason, e[0]);
        }
        assertEquals("format", verifier().verify(text.replace("uuid=0f8e1c2a", "uuid=0F8E1C2A"), null, null, null, NOW).reason, "UUID 要小写");
        assertEquals("format", verifier().verify(text.replace("name=Steve\n", ""), null, null, null, NOW).reason);
        assertEquals("format", verifier().verify(LocationSignerProof.any(), null, null, null, NOW).reason, "位置证明不是操作者证明");
        OperatorSigner other = new OperatorSigner(LocationSigner.generate(new SecureRandom()));
        assertEquals("signature", verifier().verify(other.sign(STEVE, "Steve", PC, NOW, "id.yr", "nonce-12345678").text(), null, null, null, NOW).reason);
    }

    @Test
    void siteAddressAgeAndNonce() throws Exception {
        String text = proof("id.yr", "nonce-12345678");
        assertEquals("site", verifier().verify(text, "evil.yr", PC, null, NOW).reason, "转给别的网站");
        assertEquals("address", verifier().verify(text, "id.yr", Ipv6Address.parse("fd12:3456:789a::c:4000:1f00"), null, NOW).reason,
                "别的电脑签的证明拿来用");
        assertEquals("expired", verifier().verify(text, "id.yr", PC, null, NOW + 301).reason);
        assertEquals("expired", verifier().verify(text, "id.yr", PC, null, NOW - 31).reason);
        assertEquals("nonce", verifier().verify(text, "id.yr", PC, n -> false, NOW).reason);
        assertThrows(IllegalArgumentException.class, () -> signer.sign(STEVE, "bad\nname", PC, NOW, "id.yr", "nonce-12345678"));
        assertThrows(IllegalArgumentException.class, () -> signer.sign(STEVE, "Steve", PC, NOW, "Id.yr", "nonce-12345678"));
    }

    @Test
    void keyFileIsSeparateFromLocation(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("op.pem");
        OperatorSigner.Loaded first = OperatorSigner.loadOrCreate(f);
        assertTrue(first.created);
        assertTrue(new String(Files.readAllBytes(f), StandardCharsets.US_ASCII).contains("BEGIN PRIVATE KEY"));
        OperatorSigner.Loaded again = OperatorSigner.loadOrCreate(f);
        assertFalse(again.created);
        assertEquals(first.signer.keyId(), again.signer.keyId());
        LocationSigner.Loaded loc = LocationSigner.loadOrCreate(dir.resolve("loc.pem"));
        assertNotEquals(loc.signer.keyId(), first.signer.keyId(), "两把密钥");
    }

    static HttpRequest request(String method, String path, String form) {
        HttpHeaders h = new HttpHeaders().add("Host", "operator.yr");
        if (form != null) {
            h.add("Content-Type", "application/x-www-form-urlencoded");
        }
        return new HttpRequest(method, path, "HTTP/1.1", h, form == null ? new byte[0] : form.getBytes(StandardCharsets.US_ASCII),
                PC, 40000, Ipv6Address.parse("fd12:3456:789a:1::9"), 80, false);
    }

    static String body(HttpResponse r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    @Test
    void operatorService() throws Exception {
        OperatorService s = new OperatorService(signer, () -> NOW * 1000);
        HttpResponse key = s.respond(request("GET", "/key", null));
        assertEquals(200, key.status);
        assertEquals(signer.keyId(), LocationProof.keyId(LocationProof.readPublicKey(body(key))));
        String enc = URLEncoder.encode(proof("id.yr", "nonce-12345678"), "UTF-8");
        assertEquals("ok", body(s.respond(request("POST", "/verify", "proof=" + enc + "&site=id.yr&address=" + PC))));
        assertEquals("fail site", body(s.respond(request("POST", "/verify", "proof=" + enc + "&site=evil.yr"))));
        assertEquals("fail address", body(s.respond(request("POST", "/verify", "proof=" + enc + "&address=fd00::1"))));
        assertEquals("fail format", body(s.respond(request("POST", "/verify", "proof=abc"))));
        assertEquals(405, s.respond(request("GET", "/verify", null)).status);
        assertEquals(200, s.respond(request("GET", "/", null)).status);
        assertEquals(404, s.respond(request("GET", "/nope", null)).status);
    }

    /** 拿一份位置证明（格式对、但不是操作者证明） */
    static final class LocationSignerProof {
        static String any() throws Exception {
            return new LocationSigner(LocationSigner.generate(new SecureRandom())).sign(0, 1, 2, 3, PC, NOW, "id.yr", "nonce-12345678").text();
        }
    }
}
