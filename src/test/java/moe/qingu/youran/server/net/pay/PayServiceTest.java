package moe.qingu.youran.server.net.pay;

import com.google.gson.JsonObject;
import moe.qingu.youran.api.network.ConfirmResult;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PayItem;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayServiceTest {
    /** 登录后只跳回 pay.yr 自己的路径 */
    @Test
    void safeNext() {
        assertEquals("/c/ord_abcdefghijkmnpqr", PayService.safeNext("/c/ord_abcdefghijkmnpqr"));
        assertEquals("/account", PayService.safeNext("//evil.yr/x"));
        assertEquals("/account", PayService.safeNext("https://evil.yr/"));
        assertEquals("/account", PayService.safeNext(null));
        assertEquals("/account", PayService.safeNext("/a\"b"));
    }

    @Test
    void returnUrls() throws Exception {
        assertEquals("https://shop.yr/done?order=ord_x&status=paid", PayService.appendQuery("https://shop.yr/done", "ord_x", "paid"));
        assertEquals("https://shop.yr/done?a=1&order=ord_x&status=canceled", PayService.appendQuery("https://shop.yr/done?a=1", "ord_x", "canceled"));
        assertEquals("https://shop.yr/ok", PayServer.url("https://shop.yr/ok", "shop.yr", "return_url"));
        assertNull(PayServer.url(" ", "shop.yr", "return_url"));
        assertThrows(PayException.class, () -> PayServer.url("https://evil.yr/ok", "shop.yr", "return_url"), "别的网站");
        assertThrows(PayException.class, () -> PayServer.url("javascript:alert(1)", "shop.yr", "return_url"));
        assertThrows(PayException.class, () -> PayServer.url("https://shop.yr/a#b", "shop.yr", "return_url"));
        assertThrows(PayException.class, () -> PayServer.url("https://shop.yr/a b", "shop.yr", "return_url"));
    }

    /** 接口里的物品写法：ID、@数据值、SNBT（写成规范形式） */
    @Test
    void parseItem() throws Exception {
        PayItem w = PayService.parseItem("minecraft:wool@14", null, "item1");
        assertEquals(PayItem.of("minecraft:wool", 14), w);
        assertEquals(PayItem.of("minecraft:diamond", 0), PayService.parseItem("diamond", "", "item1"));
        PayItem n = PayService.parseItem("minecraft:diamond_sword", "{display:{Name:\"好剑\"},RepairCost:1}", "item2");
        assertEquals("{\"RepairCost\":1,\"display\":{\"Name\":\"好剑\"}}", n.nbt);
        assertThrows(PayException.class, () -> PayService.parseItem("minecraft:wool@x", null, "item1"));
        PayException bad = assertThrows(PayException.class, () -> PayService.parseItem("minecraft:stone", "{a:", "item3"));
        assertEquals("invalid_item3_nbt", bad.code);
        assertEquals(5, PayService.positive("5", "emerald"));
        assertThrows(PayException.class, () -> PayService.positive("0", "emerald"));
        assertThrows(PayException.class, () -> PayService.positive("-3", "emerald"));
        assertThrows(PayException.class, () -> PayService.positive("1e3", "emerald"));
    }

    /** 纯文本格式：一行一项，物品拆成 item1=、item1_count= */
    @Test
    void textFormat() {
        JsonObject o = new JsonObject();
        o.addProperty("id", "ord_x");
        o.addProperty("status", "paid");
        o.add("items", PayService.itemsJson(Collections.singletonMap(PayItem.of("minecraft:wool", 14), 3L)));
        String text = new String(PayService.apiOk(200, o, true).body, StandardCharsets.UTF_8);
        assertEquals("id=ord_x\nstatus=paid\nitem1=minecraft:wool@14\nitem1_count=3\n", text);
        String err = new String(PayService.apiError(401, "invalid_key", "密钥不对", false).body, StandardCharsets.UTF_8);
        assertTrue(err.contains("\"error\":\"invalid_key\""), err);
    }

    /** 表单里的物品个数：只认给的那些物品，个数要是整数 */
    @Test
    void formCounts() throws Exception {
        PayItem d = PayItem.of("minecraft:diamond", 0);
        Map<String, String> f = new HashMap<>();
        f.put("item_" + PayService.itemKey(d), "3");
        f.put("item_ffffffffffff", "9");
        assertEquals(Collections.singletonMap(d, 3L), PayService.itemCounts(f, Collections.singletonList(d)));
        f.put("item_" + PayService.itemKey(d), "三");
        assertThrows(PayException.class, () -> PayService.itemCounts(f, Collections.singletonList(d)));
        assertEquals(0, PayService.yuanField(" "));
        assertEquals(12, PayService.yuanField("12"));
        assertThrows(PayException.class, () -> PayService.yuanField("1.5"));
    }

    /**
     * 确认框的结果：同意了就算，谁同意的都行（服主 11-15：另一个玩家坐到已登录的电脑前动钱是允许的，记账照登录的账户）；
     * 拒绝、超时、离开、没人操作、忙都不算
     */
    @Test
    void confirmResults() {
        UUID me = UUID.randomUUID();
        assertNull(PayServer.refusal(new ConfirmResult(ConfirmResult.Outcome.APPROVED, me, "Me")));
        assertNull(PayServer.refusal(new ConfirmResult(ConfirmResult.Outcome.APPROVED, UUID.randomUUID(), "Other")), "别的玩家同意也算");
        assertEquals("denied", PayServer.refusal(new ConfirmResult(ConfirmResult.Outcome.DENIED, me, "Me")).code);
        assertEquals("timeout", PayServer.refusal(new ConfirmResult(ConfirmResult.Outcome.TIMEOUT, me, "Me")).code);
        assertEquals("operator_changed", PayServer.refusal(new ConfirmResult(ConfirmResult.Outcome.OPERATOR_CHANGED, me, "Me")).code);
        assertEquals("busy", PayServer.refusal(new ConfirmResult(ConfirmResult.Outcome.BUSY, me, "Me")).code);
        assertEquals("no_operator", PayServer.refusal(new ConfirmResult(ConfirmResult.Outcome.NO_OPERATOR, null, null)).code);
        assertEquals("unavailable", PayServer.refusal(null).code);
    }

    /** 余额用法的写法：none / all / 元数 / part + n；写错为 null */
    @Test
    void balanceUseParsing() {
        assertSame(CurrencyPlan.BalanceUse.NONE, PayService.balanceUse(Collections.<String, String>emptyMap()));
        assertSame(CurrencyPlan.BalanceUse.ALL, PayService.balanceUse(Collections.singletonMap("bal", "all")));
        assertEquals(12, PayService.balanceUse(Collections.singletonMap("bal", "12")).yuan);
        Map<String, String> part = new java.util.HashMap<>();
        part.put("bal", "part");
        part.put("n", " 7 ");
        assertEquals(7, PayService.balanceUse(part).yuan);
        assertEquals("7", PayService.balanceUse(part).code());
        part.put("n", "");
        assertSame(CurrencyPlan.BalanceUse.NONE, PayService.balanceUse(part));
        part.put("n", "-3");
        assertNull(PayService.balanceUse(part));
        assertNull(PayService.balanceUse(Collections.singletonMap("bal", "1e3")));
        assertSame(CurrencyPlan.BalanceUse.NONE, PayService.balanceUse(Collections.singletonMap("bal", "0")));
    }

    @Test
    void loginErrors() {
        assertTrue(PayService.loginError("login_unavailable", "no_capability").contains("一键登录"));
        assertTrue(PayService.loginError("access_denied", "timeout").contains("超时"));
        assertTrue(PayService.loginError("access_denied", null).contains("拒绝"));
    }

    /** PKCE：RFC 7636 附录 B 的例子 */
    @Test
    void pkce() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", IdClient.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertTrue(new IdClient(null, null, "id.yr", false).authorizeUrl("https", "st", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
                .startsWith("https://id.yr/authorize?response_type=code&client_id=https%3A%2F%2Fpay.yr%2F&redirect_uri=https%3A%2F%2Fpay.yr%2Flogin%2Fcallback&state=st"));
        IdClient c = new IdClient(null, null, "id.yr", false);
        assertTrue(c.issuerOk(null) && c.issuerOk("https://id.yr") && c.issuerOk("http://id.yr"));
        assertTrue(!c.issuerOk("https://evil.yr"));
    }
}
