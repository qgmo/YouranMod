package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.lang.ServerLang;
import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.PayItem;
import net.minecraft.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 物品与金额的文字：网页上、请 id.yr 弹的确认框里都用服务端的中文名 */
final class ItemText {
    private static final Map<PayItem, String> NAMES = new ConcurrentHashMap<>();
    private static volatile int namesVersion = -1;

    private ItemText() {
    }

    /** 中文名（改过名的用改的名字）；物品不存在了写 ID。做物品要在主线程，结果缓存，网络线程只读缓存（没有时写 ID） */
    static String name(PayItem item) {
        checkVersion();
        String n = NAMES.get(item);
        return n != null ? n : item.id + (item.meta == 0 ? "" : "@" + item.meta) + (item.nbt == null ? "" : "（带 NBT）");
    }

    /** 主线程：算好名字放进缓存 */
    static String cache(PayItem item) {
        checkVersion();
        String n = NAMES.get(item);
        if (n == null) {
            ItemStack s = item.toStack(1);
            if (s.isEmpty()) {
                n = item.id + "（这个物品没有了）";
            } else if (s.hasDisplayName()) {
                n = s.getDisplayName();
            } else {
                n = ServerLang.itemName(ServerLang.ZH_CN, s);
            }
            NAMES.put(item, n);
        }
        return n;
    }

    /** 服务端翻译重新加载过：名字重算 */
    private static synchronized void checkVersion() {
        int v = ServerLang.version();
        if (namesVersion != v) {
            NAMES.clear();
            namesVersion = v;
        }
    }

    static void cacheAll(Amount a) {
        for (PayItem i : a.items.keySet()) {
            cache(i);
        }
    }

    static void cacheAll(Map<PayItem, Long> m) {
        for (PayItem i : m.keySet()) {
            cache(i);
        }
    }

    /** 网页上的金额 */
    static String describe(Amount a, Currency.Unit unit) {
        return a.describe(unit, ItemText::name);
    }

    static String clip(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
    }

    /** 去掉确认框不收的字符（物品改的名字里可能有颜色代码） */
    static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            if (ConfirmRequest.isAllowedChar(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim().isEmpty() ? "?" : sb.toString();
    }
}
