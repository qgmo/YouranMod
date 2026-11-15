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

    public static List<PaymentListener> listeners(AccountRef account) {
        List<PaymentListener> list = LISTENERS.get(account);
        return list == null ? Collections.<PaymentListener>emptyList() : list;
    }
}
