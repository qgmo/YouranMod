package moe.qingu.youran.server.net.location;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.http.HttpHeaders;
import moe.qingu.youran.api.network.http.HttpRequest;
import moe.qingu.youran.api.network.http.HttpResponse;
import moe.qingu.youran.api.network.location.LocationProof;
import moe.qingu.youran.api.network.location.LocationVerifier;
import moe.qingu.youran.api.network.location.NonceBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 位置证明：签名与核对、篡改任一字段都不过、随机数一次性、密钥文件、location.yr 的接口 */
class LocationProofTest {
    static final Ipv6Address DEVICE = Ipv6Address.parse("fd12:3456:789a::c:4000:1e00");
    static final long NOW = 1_763_200_000L;
    final LocationSigner signer;

    LocationProofTest() throws Exception {
        signer = new LocationSigner(LocationSigner.generate(new SecureRandom()));
    }

    String proof(String site, String nonce) throws Exception {
        return signer.sign(0, 120, 64, -35, DEVICE, NOW, site, nonce).text();
    }

    LocationVerifier verifier() {
        return new LocationVerifier(signer.publicKey(), 300);
    }

    @Test
    void signAndVerify() throws Exception {
        String text = proof("shop.yr", "nonce-12345678");
        assertTrue(text.startsWith("youran-location-proof v1\ndimension=0\nx=120\ny=64\nz=-35\ndevice=fd12:3456:789a::c:4000:1e00\ntime=1763200000\n"
                + "site=shop.yr\nnonce=nonce-12345678\nkey=" + signer.keyId() + "\nsignature="), text);
        LocationVerifier.Result r = verifier().verify(text, "shop.yr", DEVICE, n -> n.equals("nonce-12345678"), NOW + 10);
        assertTrue(r.ok(), r.toString());
        assertEquals(-35, r.proof.z);
        // 表单提交时换行变成 CRLF、末尾多空白：照样能核
        assertTrue(verifier().verify(text.replace("\n", "\r\n") + "  ", "shop.yr", DEVICE, null, NOW).ok());
    }

    @Test
    void tamperingAnyFieldFails() throws Exception {
        String text = proof("shop.yr", "nonce-12345678");
        String[][] edits = {
                {"dimension=0", "dimension=1"},
                {"x=120", "x=121"},
                {"y=64", "y=65"},
                {"z=-35", "z=35"},
                {"device=fd12:3456:789a::c:4000:1e00", "device=fd12:3456:789a::c:4000:1e01"},
                {"time=1763200000", "time=1763200001"},
                {"site=shop.yr", "site=shoq.yr"},
                {"nonce=nonce-12345678", "nonce=nonce-12345679"},
        };
        for (String[] e : edits) {
            String bad = text.replace(e[0], e[1]);
            assertFalse(LocationProof.parse(bad).signatureValid(signer.publicKey()), "改了 " + e[0]);
            assertEquals("signature", verifier().verify(bad, null, null, null, NOW).reason, e[0]);
        }
        // 改签名本身
        int i = text.indexOf("signature=") + 12;
        char c = text.charAt(i) == 'A' ? 'B' : 'A';
        String badSig = text.substring(0, i) + c + text.substring(i + 1);
        assertFalse(verifier().verify(badSig, null, null, null, NOW).ok());
        // 换一种写法的地址、带前导 0 的数：不是规范写法
        assertEquals("format", verifier().verify(text.replace("x=120", "x=0120"), null, null, null, NOW).reason);
        assertEquals("format", verifier().verify(text.replace("device=fd12:3456:789a::c:4000:1e00", "device=fd12:3456:789a:0:0:c:4000:1e00"),
                null, null, null, NOW).reason);
        // 少一行、多一行、换顺序
        assertEquals("format", verifier().verify(text.replace("y=64\n", ""), null, null, null, NOW).reason);
        assertEquals("format", verifier().verify(text.replace("x=120\ny=64", "y=64\nx=120"), null, null, null, NOW).reason);
        assertEquals("format", verifier().verify("hello", null, null, null, NOW).reason);
        // 别的密钥签的
        LocationSigner other = new LocationSigner(LocationSigner.generate(new SecureRandom()));
        String foreign = other.sign(0, 120, 64, -35, DEVICE, NOW, "shop.yr", "nonce-12345678").text();
        assertEquals("signature", verifier().verify(foreign, null, null, null, NOW).reason);
    }

    @Test
    void siteDeviceAgeAndNonce() throws Exception {
        String text = proof("shop.yr", "nonce-12345678");
        assertEquals("site", verifier().verify(text, "evil.yr", DEVICE, null, NOW).reason, "转给别的网站");
        assertEquals("device", verifier().verify(text, "shop.yr", Ipv6Address.parse("fd12:3456:789a::c:4000:1f00"), null, NOW).reason, "换一台电脑");
        assertEquals("expired", verifier().verify(text, "shop.yr", DEVICE, null, NOW + 301).reason);
        assertEquals("expired", verifier().verify(text, "shop.yr", DEVICE, null, NOW - 31).reason, "未来太远");

        NonceBook book = new NonceBook(60_000, 3);
        String n = book.issue(0);
        String t = proof("shop.yr", n);
        assertTrue(verifier().verify(t, "shop.yr", DEVICE, x -> book.consume(x, 1000), NOW).ok());
        assertEquals("nonce", verifier().verify(t, "shop.yr", DEVICE, x -> book.consume(x, 1000), NOW).reason, "重放");
        String n2 = book.issue(0);
        assertFalse(book.consume(n2, 60_000), "过期");
        assertFalse(book.consume("not-issued-0001", 0), "不是自己发的");
        // 满了丢最早的
        String a = book.issue(0);
        book.issue(0);
        book.issue(0);
        book.issue(0);
        assertFalse(book.consume(a, 1));
        assertEquals(3, book.size());
    }

    @Test
    void fieldValidation() {
        assertThrows(IllegalArgumentException.class, () -> signer.sign(0, 0, 0, 0, DEVICE, NOW, "Shop.yr", "nonce-12345678"));
        assertThrows(IllegalArgumentException.class, () -> signer.sign(0, 0, 0, 0, DEVICE, NOW, "shop.yr", "short"));
        assertThrows(IllegalArgumentException.class, () -> signer.sign(0, 0, 0, 0, DEVICE, NOW, "shop.yr", "has space 1234"));
        assertThrows(IllegalArgumentException.class, () -> signer.sign(0, 0, 0, 0, DEVICE, NOW, "shop.yr\nx=1", "nonce-12345678"));
    }

    @Test
    void keyFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config/youranmod-location-key.pem");
        LocationSigner.Loaded first = LocationSigner.loadOrCreate(f);
        assertTrue(first.created);
        String content = new String(Files.readAllBytes(f), StandardCharsets.US_ASCII);
        assertTrue(content.contains("-----BEGIN PRIVATE KEY-----") && content.contains("-----BEGIN PUBLIC KEY-----"));
        LocationSigner.Loaded again = LocationSigner.loadOrCreate(f);
        assertFalse(again.created);
        assertEquals(first.signer.keyId(), again.signer.keyId(), "第二次开服读同一把");
        String t = again.signer.sign(0, 1, 2, 3, DEVICE, NOW, "a.yr", "nonce-12345678").text();
        assertTrue(LocationProof.parse(t).signatureValid(LocationProof.readPublicKey(first.signer.publicKeyPem())));
        // 坏了的文件：不覆盖，抛出
        Files.write(f, "garbage".getBytes(StandardCharsets.US_ASCII));
        assertThrows(Exception.class, () -> LocationSigner.loadOrCreate(f));
        assertEquals("garbage", new String(Files.readAllBytes(f), StandardCharsets.US_ASCII));
        // 私钥与公钥不是一对
        KeyPair x = LocationSigner.generate(new SecureRandom());
        KeyPair y = LocationSigner.generate(new SecureRandom());
        String mixed = LocationSigner.write(x).replaceAll("(?s)-----BEGIN PUBLIC KEY-----.*", "") + LocationProof.publicKeyPem(y.getPublic());
        Files.write(f, mixed.getBytes(StandardCharsets.US_ASCII));
        assertThrows(Exception.class, () -> LocationSigner.loadOrCreate(f));
    }

    HttpRequest request(String method, String path, String form) {
        HttpHeaders h = new HttpHeaders().add("Host", "location.yr");
        if (form != null) {
            h.add("Content-Type", "application/x-www-form-urlencoded");
        }
        return new HttpRequest(method, path, "HTTP/1.1", h, form == null ? new byte[0] : form.getBytes(StandardCharsets.US_ASCII),
                DEVICE, 40000, Ipv6Address.parse("fd12:3456:789a:1::9"), 80, false);
    }

    static String body(HttpResponse r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    @Test
    void locationService() throws Exception {
        LocationService s = new LocationService(signer, () -> NOW * 1000);
        HttpResponse key = s.respond(request("GET", "/key", null));
        assertEquals(200, key.status);
        assertEquals(signer.keyId(), LocationProof.keyId(LocationProof.readPublicKey(body(key))));
        assertEquals(body(key), body(s.respond(request("GET", "/.well-known/location-key", null))));
        String text = proof("shop.yr", "nonce-12345678");
        String enc = URLEncoder.encode(text, "UTF-8");
        assertEquals("ok", body(s.respond(request("POST", "/verify", "proof=" + enc + "&site=shop.yr&device=" + DEVICE))));
        assertEquals("ok", body(s.respond(request("POST", "/verify", "proof=" + URLEncoder.encode(text.replace("\n", "\r\n"), "UTF-8")))));
        assertEquals("fail site", body(s.respond(request("POST", "/verify", "proof=" + enc + "&site=evil.yr"))));
        assertEquals("fail device", body(s.respond(request("POST", "/verify", "proof=" + enc + "&device=fd00::1"))));
        assertEquals("fail signature", body(s.respond(request("POST", "/verify", "proof=" + URLEncoder.encode(text.replace("x=120", "x=1"), "UTF-8")))));
        assertEquals("fail format", body(s.respond(request("POST", "/verify", "proof=abc"))));
        assertEquals(405, s.respond(request("GET", "/verify", null)).status);
        assertEquals(200, s.respond(request("GET", "/", null)).status);
        assertEquals(404, s.respond(request("GET", "/nope", null)).status);
        LocationService old = new LocationService(signer, () -> (NOW + 301) * 1000);
        assertEquals("fail expired", body(old.respond(request("POST", "/verify", "proof=" + enc))));
    }
}
