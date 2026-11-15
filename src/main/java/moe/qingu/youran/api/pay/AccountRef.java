package moe.qingu.youran.api.pay;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * pay.yr 的一个账户（服主 11-15 两种）：玩家账户（每个玩家角色一个，按 UUID）；模组创建的虚拟账户（按“模组 ID:名字”，
 * 如 {@code cfm:minebay}）。不可变，可以当表的键。字符串写法 {@code player:<uuid>} / {@code virtual:<模组 ID:名字>}。
 */
public final class AccountRef {
    public static final Pattern VIRTUAL_KEY = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}");

    /** 玩家账户的 UUID；虚拟账户为 null */
    public final UUID player;
    /** 虚拟账户的名字（“模组 ID:名字”）；玩家账户为 null */
    public final String virtual;
    private final String id;

    private AccountRef(UUID player, String virtual) {
        this.player = player;
        this.virtual = virtual;
        this.id = player != null ? "player:" + player : "virtual:" + virtual;
    }

    public static AccountRef player(UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("UUID 不能为 null");
        }
        return new AccountRef(uuid, null);
    }

    public static AccountRef virtual(String key) {
        if (key == null || !VIRTUAL_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("虚拟账户要写成“模组 ID:名字”（小写字母、数字、_ . - /）：" + key);
        }
        return new AccountRef(null, key);
    }

    /** 读字符串写法；不对时抛 {@link IllegalArgumentException} */
    public static AccountRef parse(String s) {
        if (s != null && s.startsWith("player:")) {
            return player(UUID.fromString(s.substring(7)));
        }
        if (s != null && s.startsWith("virtual:")) {
            return virtual(s.substring(8));
        }
        throw new IllegalArgumentException("账户写法不对：" + s);
    }

    public boolean isPlayer() {
        return player != null;
    }

    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AccountRef && ((AccountRef) o).id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
