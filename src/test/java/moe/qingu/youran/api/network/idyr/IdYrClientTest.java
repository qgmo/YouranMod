package moe.qingu.youran.api.network.idyr;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** id.yr 客户端：登录网址、确认结果的签名（与 YouranAuth 的 id.yr 同一个算法、同一组例子）、结果解析、给玩家看的说明 */
class IdYrClientTest {
    @Test
    void signatureSameAsIdYr() {
        // 与 YouranAuth ConfirmServiceTest.signatureVector 是同一组例子
        assertEquals("_Sqr47WW4TAa_pGS5bq00IP3gC8OloafIl2HL8i76uA", IdYrClient.sign("s3cr3t", "cr_abc", "approved", null, "s-1"));
        assertEquals("b0rnFVfH-U_v5mkuyyagLwIj2O4iQ3nMQMyBM6d83ic", IdYrClient.sign("s3cr3t", "cr_abc", "denied", "timeout", null));
        IdYrClient.Ticket t = new IdYrClient.Ticket("cr_abc", "https://id.yr/confirm?request=cr_abc", "s3cr3t");
        String sig = IdYrClient.sign("s3cr3t", "cr_abc", "approved", null, "s-1");
        assertTrue(t.signatureOk("cr_abc", "approved", null, "s-1", sig));
        assertFalse(t.signatureOk("cr_abc", "approved", null, "s-2", sig), "state 改了");
        assertFalse(t.signatureOk("cr_xyz", "approved", null, "s-1", sig), "别的请求");
        assertFalse(t.signatureOk("cr_abc", "denied", null, "s-1", sig));
        assertFalse(t.signatureOk("cr_abc", "approved", null, "s-1", null));
    }

    @Test
    void outcomes() {
        JsonObject a = new JsonParser().parse("{\"request\":\"cr_1\",\"status\":\"approved\",\"source\":\"fd00::1\"}").getAsJsonObject();
        IdYrClient.Outcome o = IdYrClient.parseOutcome(a);
        assertTrue(o.approved());
        assertNull(o.reason);
        assertEquals("fd00::1", o.source);
        o = IdYrClient.parseOutcome(new JsonParser().parse("{\"status\":\"denied\",\"reason\":\"timeout\"}").getAsJsonObject());
        assertEquals(IdYrClient.Outcome.Status.DENIED, o.status);
        assertTrue(o.text(false).contains("超时"));
        o = IdYrClient.parseOutcome(new JsonParser().parse("{\"status\":\"pending\"}").getAsJsonObject());
        assertEquals(IdYrClient.Outcome.Status.PENDING, o.status);
        assertFalse(o.approved());
        // 验证身份：拒绝与不是本人说法一样（网站本来也分不出）
        assertTrue(IdYrClient.reasonText("denied", true).contains("不是帐号本人"));
        assertTrue(IdYrClient.reasonText("denied", false).contains("拒绝"));
        assertTrue(IdYrClient.reasonText("whatever", false).contains("暂时不可用"));
    }

    @Test
    void urlsAndErrors() {
        IdYrClient c = new IdYrClient(null, null, "ID.YR ", true, "platform.180.yr");
        assertEquals("id.yr", c.domain());
        assertEquals("http://platform.180.yr/", c.clientId("http"));
        assertTrue(c.authorizeUrl("http", "s", "v", "verify").endsWith("&scope=verify"));
        assertFalse(c.authorizeUrl("http", "s", "v", null).contains("scope="));
        assertTrue(new IdYrException(401, "invalid_token", "x").tokenExpired());
        assertTrue(new IdYrException(403, "insufficient_scope", "x").insufficientScope());
        assertFalse(new IdYrException(503, "temporarily_unavailable", "x").tokenExpired());
        assertEquals("action", IdYrClient.purposeCode(null));
        assertEquals("pay", IdYrClient.purposeCode(moe.qingu.youran.api.network.ConfirmRequest.Purpose.PAY));
        assertTrue(IdYrClient.loginErrorText("access_denied", "timeout").contains("超时"));
    }
}
