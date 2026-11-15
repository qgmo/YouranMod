package moe.qingu.youran.server.net.nav;

import moe.qingu.youran.api.image.DyePng;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 投放表单与图片的检查 */
class AdInputTest {
    @Test
    void links() throws Exception {
        assertEquals("http://minebay.yr/", AdInput.link("minebay.yr"));
        assertEquals("https://shop.yr/a?b=1", AdInput.link(" HTTPS://Shop.YR/a?b=1 "));
        assertEquals("http://shop.yr/?x=1", AdInput.link("http://shop.yr?x=1"));
        assertEquals("http://xn--0zwm56d.yr/", AdInput.link("http://测试.yr"));
        assertEquals("http://shop.yr:8080/", AdInput.link("http://shop.yr:8080"));
        assertThrows(AdInput.Bad.class, () -> AdInput.link("javascript:alert(1)"));
        assertThrows(AdInput.Bad.class, () -> AdInput.link("ftp://shop.yr/"));
        assertThrows(AdInput.Bad.class, () -> AdInput.link("http://example.org/"));
        assertThrows(AdInput.Bad.class, () -> AdInput.link("http://user:pw@shop.yr/"));
        assertThrows(AdInput.Bad.class, () -> AdInput.link("http://shop.yr/a b"));
        assertThrows(AdInput.Bad.class, () -> AdInput.link("http://shop.yr/\"onclick"));
        assertThrows(AdInput.Bad.class, () -> AdInput.link("http://shop.yr/#x"));
        assertThrows(AdInput.Bad.class, () -> AdInput.link("http://shop.yr:0/"));
        assertThrows(AdInput.Bad.class, () -> AdInput.link("http://[fd00::1]/"));
        assertThrows(AdInput.Bad.class, () -> AdInput.link(""));
    }

    @Test
    void texts() throws Exception {
        assertEquals("便宜 木头", AdInput.text("  便宜   木头 ", 16, true, "文字"));
        assertEquals("", AdInput.text(null, 16, false, "说明"));
        assertThrows(AdInput.Bad.class, () -> AdInput.text("", 16, true, "文字"));
        assertThrows(AdInput.Bad.class, () -> AdInput.text("12345678901234567", 16, true, "文字"));
        assertThrows(AdInput.Bad.class, () -> AdInput.text("§4红", 16, true, "文字"));
        assertThrows(AdInput.Bad.class, () -> AdInput.text("a‮b", 16, true, "文字"));
        assertEquals(3, AdInput.days("3", 30));
        assertThrows(AdInput.Bad.class, () -> AdInput.days("0", 30));
        assertThrows(AdInput.Bad.class, () -> AdInput.days("31", 30));
        assertThrows(AdInput.Bad.class, () -> AdInput.days("1.5", 30));
        assertThrows(AdInput.Bad.class, () -> AdInput.days(null, 30));
    }

    @Test
    void images() throws Exception {
        byte[] p = new byte[125 * 125];
        p[0] = 5;
        String url = DyePng.toDataUrl(DyePng.encode(125, 125, p));
        byte[] png = AdInput.image(url, AdSlot.SQUARE);
        assertEquals(5, DyePng.decode(png, 125, 125).pixels[0]);
        AdInput.Bad e = assertThrows(AdInput.Bad.class, () -> AdInput.image(url, AdSlot.BANNER));
        assertTrue(e.getMessage().contains("468"));
        assertThrows(AdInput.Bad.class, () -> AdInput.image(DyePng.toDataUrl(DyePng.encode(125, 125, new byte[125 * 125])), AdSlot.SQUARE), "空白");
        assertThrows(AdInput.Bad.class, () -> AdInput.image("", AdSlot.SQUARE));
        assertThrows(AdInput.Bad.class, () -> AdInput.image("hello", AdSlot.SQUARE));
    }
}
