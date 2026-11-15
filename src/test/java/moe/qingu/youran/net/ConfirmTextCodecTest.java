package moe.qingu.youran.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmText;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmTextCodecTest {
    /** 弹框的包：原样文字、翻译键（参数也可以是翻译键）、确认的是哪一类事，都原样到客户端 */
    @Test
    void roundTrip() {
        ConfirmText name = ConfirmText.translate("item.diamond.name", "钻石");
        ConfirmText line = ConfirmText.translate("youranmod.pay.confirm.take", "拿出：钻石 ×3", name, 3);
        ConfirmRequest r = new ConfirmRequest(ConfirmText.translate("youranmod.pay.confirm.title", "付款给 Bob", ConfirmText.literal("Bob")),
                "shop.yr", Arrays.asList(line, ConfirmText.literal("网站写的原样文字")), 30, ConfirmRequest.Purpose.PAY);
        ByteBuf buf = Unpooled.buffer();
        new ConfirmMessages.Show(7, r, 30).toBytes(buf);
        ConfirmMessages.Show s = new ConfirmMessages.Show();
        s.fromBytes(buf);
        assertFalse(s.bad);
        assertEquals(7, s.id);
        assertEquals(r.titleText, s.title);
        assertEquals(r.itemTexts, s.items);
        assertSame(ConfirmRequest.Purpose.PAY, s.purpose);
        assertEquals("shop.yr", s.site);
        assertEquals("付款给 Bob", r.title);
        assertEquals(Arrays.asList("拿出：钻石 ×3", "网站写的原样文字"), r.items);
    }

    /** 坏包（套得太深、截断）当坏包丢掉 */
    @Test
    void badPackets() {
        ByteBuf buf = Unpooled.buffer();
        new ConfirmMessages.Show(1, new ConfirmRequest("标题", "a.yr", null), 10).toBytes(buf);
        ByteBuf cut = buf.copy(0, buf.readableBytes() - 3);
        ConfirmMessages.Show s = new ConfirmMessages.Show();
        s.fromBytes(cut);
        assertTrue(s.bad);
        assertThrows(IllegalArgumentException.class, () -> ConfirmText.translate("a.b", "x",
                ConfirmText.translate("c.d", "y", ConfirmText.translate("e.f", "z"))), "最多套两层");
        assertThrows(IllegalArgumentException.class, () -> ConfirmText.translate("bad key", "x"));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmRequest(ConfirmText.translate("a.b", "颜色§c"), "a.yr", null, 30, null),
                "退路文字同样检查");
        assertThrows(IllegalArgumentException.class, () -> new ConfirmRequest(ConfirmText.translate("a.b", "x", "参数\n换行"), "a.yr", null, 30, null),
                "参数同样检查");
    }
}
