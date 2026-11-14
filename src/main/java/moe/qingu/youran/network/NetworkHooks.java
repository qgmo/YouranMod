package moe.qingu.youran.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 两端共用的类找服务端网络实现的地方 */
public final class NetworkHooks {
    /** 服务端网络实现的类名（只在完整包里有；客户端包里找不到时安静跳过） */
    public static final String SERVER_MODULE = "moe.qingu.youran.server.net.NetworkServer";
    private static final Logger LOG = LogManager.getLogger("YouranMod");

    private static volatile NetworkBackend backend;
    private static ServerNetworkModule module;
    private static boolean moduleLoaded;

    private NetworkHooks() {
    }

    /** 网络运行时的实现；没在运行（客户端、关服后、客户端包）为 null */
    public static NetworkBackend backend() {
        return backend;
    }

    public static void setBackend(NetworkBackend b) {
        backend = b;
    }

    /** 已经加载过的服务端网络模块（不触发加载）；没有为 null */
    public static synchronized ServerNetworkModule loadedModule() {
        return module;
    }

    /** 服务端网络模块；客户端包里没有时返回 null（只在第一次记一条日志） */
    public static synchronized ServerNetworkModule module() {
        if (!moduleLoaded) {
            moduleLoaded = true;
            try {
                module = (ServerNetworkModule) Class.forName(SERVER_MODULE).getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException e) {
                LOG.info("这是 YouranMod 的客户端包，没有服务端网络（单人游戏里游戏内网络不可用）");
            } catch (ReflectiveOperationException | LinkageError e) {
                LOG.error("加载服务端网络失败，游戏内网络不可用", e);
            }
        }
        return module;
    }
}
