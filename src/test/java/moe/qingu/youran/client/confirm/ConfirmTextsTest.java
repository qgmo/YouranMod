package moe.qingu.youran.client.confirm;

import moe.qingu.youran.api.network.ConfirmText;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfirmTextsTest {
    static final Map<String, String> EN = new HashMap<>();

    static {
        EN.put("youranmod.pay.confirm.take", "Take out: %s ×%s");
        EN.put("item.diamond.name", "Diamond");
        EN.put("evil", "§c红字‮反向");
    }

    static String render(ConfirmText t) {
        return ConfirmTexts.render(t, 80, EN::containsKey, (k, a) -> String.format(EN.get(k), a));
    }

    /** 按玩家的语言翻译（参数也翻）；客户端没有这个键时用服务端给的中文 */
    @Test
    void translatesWithFallback() {
        ConfirmText line = ConfirmText.translate("youranmod.pay.confirm.take", "拿出：钻石 ×3", ConfirmText.translate("item.diamond.name", "钻石"), 3);
        assertEquals("Take out: Diamond ×3", render(line));
        assertEquals("Take out: 模组物品 ×1", render(ConfirmText.translate("youranmod.pay.confirm.take", "拿出：模组物品 ×1",
                ConfirmText.translate("item.unknown.name", "模组物品"), 1)), "参数的键没有时参数用中文");
        assertEquals("没有的键", render(ConfirmText.translate("no.such.key", "没有的键")));
        assertEquals("网站原样", render(ConfirmText.literal("网站原样")));
    }

    /** 翻译文件里的颜色代码、文字方向控制也去掉 */
    @Test
    void cleansTranslations() {
        assertEquals("c红字反向", render(ConfirmText.translate("evil", "退路")));
    }
}
