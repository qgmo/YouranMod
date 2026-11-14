package moe.qingu.youran.network;

import moe.qingu.youran.api.network.ClientNic;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@link ClientNic} 背后的状态（由 {@code client.network.ClientNetwork} 在客户端网络线程上改） */
public final class ClientNicState {
    private static final Logger LOG = LogManager.getLogger("YouranMod");
    private static volatile ClientNic.Session current;
    private static final List<ClientNic.Listener> listeners = new CopyOnWriteArrayList<>();

    private ClientNicState() {
    }

    public static ClientNic.Session current() {
        return current;
    }

    public static void addListener(ClientNic.Listener l) {
        listeners.add(l);
    }

    public static void removeListener(ClientNic.Listener l) {
        listeners.remove(l);
    }

    public static void attached(ClientNic.Session s) {
        current = s;
        for (ClientNic.Listener l : listeners) {
            try {
                l.onAttached(s);
            } catch (Throwable t) {
                LOG.error("网卡回调出错", t);
            }
        }
    }

    public static void detached(ClientNic.Session s) {
        if (current == s) {
            current = null;
        }
        for (ClientNic.Listener l : listeners) {
            try {
                l.onDetached(s);
            } catch (Throwable t) {
                LOG.error("网卡回调出错", t);
            }
        }
    }
}
