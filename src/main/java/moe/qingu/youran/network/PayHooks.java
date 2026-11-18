package moe.qingu.youran.network;

import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.PaymentListener;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 两端共用的类找 pay.yr 服务端实现的地方；收款回调也登记在这里（模组加载阶段就能登记，开服以后照样有效） */
public final class PayHooks {
    private static volatile PayBackend backend;
    private static final Map<AccountRef, List<PaymentListener>> LISTENERS = new ConcurrentHashMap<>();
    /** 虚拟账户（“模组 ID:名字”）→ 给人看的名字；加载阶段就能登记，pay.yr 开着时马上记进账本 */
    private static final Map<String, String> DISPLAY_NAMES = new ConcurrentHashMap<>();

    /** 模组 ID → 处理它的虚拟账户付款请求的一方（可选） */
    private static final Map<String, moe.qingu.youran.api.pay.PaymentRequestHandler> REQUEST_HANDLERS = new ConcurrentHashMap<>();

    private PayHooks() {
    }

    public static PayBackend backend() {
        return backend;
    }

    public static void setBackend(PayBackend b) {
        backend = b;
    }

    public static void addListener(AccountRef account, PaymentListener l) {
        LISTENERS.computeIfAbsent(account, k -> new CopyOnWriteArrayList<>()).add(l);
    }

    public static void removeListener(AccountRef account, PaymentListener l) {
        List<PaymentListener> list = LISTENERS.get(account);
        if (list != null) {
            list.remove(l);
        }
    }

    public static void setDisplayName(String key, String name) {
        DISPLAY_NAMES.put(key, name);
        PayBackend b = backend;
        if (b != null) {
            b.applyDisplayName(key, name);
        }
    }

    /** 登记过的显示名；没有为 null */
    public static String displayName(String key) {
        return key == null ? null : DISPLAY_NAMES.get(key);
    }

    public static Map<String, String> displayNames() {
        return Collections.unmodifiableMap(new java.util.HashMap<>(DISPLAY_NAMES));
    }

    public static List<PaymentListener> listeners(AccountRef account) {
        List<PaymentListener> list = LISTENERS.get(account);
        return list == null ? Collections.<PaymentListener>emptyList() : list;
    }

    public static void setRequestHandler(String modId, moe.qingu.youran.api.pay.PaymentRequestHandler h) {
        if (modId == null || !modId.matches("[a-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("模组 ID 不对：" + modId);
        }
        if (h == null) {
            REQUEST_HANDLERS.remove(modId);
        } else {
            REQUEST_HANDLERS.put(modId, h);
        }
    }

    /** 处理这个模组的虚拟账户付款请求的一方；没有为 null */
    public static moe.qingu.youran.api.pay.PaymentRequestHandler requestHandler(String modId) {
        return modId == null ? null : REQUEST_HANDLERS.get(modId);
    }
}
