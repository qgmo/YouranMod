package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.PayItem;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * pay.yr 动世界的那几下（主线程）：背包、玩家存档、脚边的掉落物。实现是 {@link McWorld}；测试里换成假的，好模拟在各步之间崩溃。
 * <p>
 * “在不在存档里”一律<b>读文件</b>核对：玩家存档（{@code playerdata/<uuid>.dat}）里有没有这笔的标记、区块存档里有没有带这笔标记的掉落物。
 * 标记和背包写在同一个文件里、一起存，所以“标记在”就等于“背包的改动存下来了”。
 */
interface WorldPort {
    /** 在线玩家；不在线为 null */
    PlayerPort player(UUID id);

    /** 玩家存档文件里有没有这笔的标记；读不了抛 IOException */
    boolean markerOnDisk(UUID player, String tx) throws IOException;

    /** 区块存档里这笔的掉落物（物品 → 个数）；读不了抛 IOException */
    Map<PayItem, Long> dropsOnDisk(int dim, int cx, int cz, String tx) throws IOException;

    /** 把这个区块写盘（等写完） */
    void persistChunk(int dim, int cx, int cz) throws IOException;

    /** 把区块里这笔的掉落物删掉（只改内存；区块没加载时先加载） */
    void killDrops(int dim, int cx, int cz, String tx);

    /** 这笔算数了：掉落物可以捡了（只有这个玩家能捡，快消失前别人也能捡；只改内存） */
    void releaseDrops(int dim, int cx, int cz, String tx);

    /** 一个在线玩家（主线程上用） */
    interface PlayerPort {
        UUID id();

        String name();

        /** 背包（主背包 + 副手）里能记账的物品各有多少 */
        Map<PayItem, Long> counts();

        /** 从背包拿走这些（全有或全无，只改内存）；不够时什么都不拿、返回 false */
        boolean take(Map<PayItem, Long> items);

        /** 先拿走 {@code take} 再放进 {@code give} 的话，放不下的有哪些（只算，不改背包） */
        Map<PayItem, Long> overflow(Map<PayItem, Long> take, Map<PayItem, Long> give);

        /** 放进背包（只改内存），返回放不下的 */
        Map<PayItem, Long> add(Map<PayItem, Long> items);

        /** 在玩家数据里加 / 去掉这笔的标记（只改内存，和背包一起存盘） */
        void mark(String tx);

        void unmark(String tx);

        /** 现在就把玩家数据写盘 */
        void persist();

        int dimension();

        int chunkX();

        int chunkZ();

        /** 掉在脚边（带这笔的标记、暂时不能捡）；返回实际掉出来的 */
        Map<PayItem, Long> drop(Map<PayItem, Long> items, String tx);
    }
}
