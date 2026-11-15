package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.lang.ServerLang;
import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmText;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.Currency;
import moe.qingu.youran.api.pay.PayItem;
import net.minecraft.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 物品与金额的文字：网页上用服务端的中文名；确认框里用翻译键（客户端按玩家的语言显示），中文名作退路 */
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

    /** 确认框里的物品名（主线程）：没改名的用翻译键 */
    static ConfirmText confirmName(PayItem item) {
        String zh = clip(cache(item), 40);
        ItemStack s = item.toStack(1);
        if (s.isEmpty() || s.hasDisplayName()) {
            return ConfirmText.literal(clean(zh));
        }
        String key = s.getTranslationKey() + ".name";
        return ConfirmText.KEY.matcher(key).matches() ? ConfirmText.translate(key, clean(zh)) : ConfirmText.literal(clean(zh));
    }

    /** 确认框里的一行“前缀 名字 ×个数”：翻译键 {@code key} 的两个参数是名字与个数，{@code fallback} 是中文的格式（%s 名字、%s 个数） */
    static ConfirmText confirmLine(String key, String fallback, PayItem item, long n) {
        ConfirmText name = confirmName(item);
        return ConfirmText.translate(key, String.format(fallback, name.fallback, n), name, n);
    }

    /** 确认框里的一行钱：翻译键 {@code key} 的两个参数是标价单位的写法与另一种单位的写法（“20 绿宝石”“40 元”） */
    static ConfirmText confirmMoney(String key, String fallback, long yuan, Currency.Unit unit) {
        Currency.Unit other = unit == Currency.Unit.EMERALD ? Currency.Unit.YUAN : Currency.Unit.EMERALD;
        return ConfirmText.translate(key, String.format(fallback, Currency.format(yuan, unit), Currency.format(yuan, other)),
                money(yuan, unit), money(yuan, other));
    }

    private static ConfirmText money(long yuan, Currency.Unit u) {
        String n = u == Currency.Unit.EMERALD ? (yuan % Currency.YUAN_PER_EMERALD == 0 ? Long.toString(yuan / Currency.YUAN_PER_EMERALD)
                : (yuan / Currency.YUAN_PER_EMERALD) + ".5") : Long.toString(yuan);
        return ConfirmText.translate(u == Currency.Unit.EMERALD ? "youranmod.pay.confirm.emeralds" : "youranmod.pay.confirm.yuan",
                Currency.format(yuan, u), n);
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
