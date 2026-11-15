package moe.qingu.youran.api.network;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * 按来源地址认出来的人（{@link YouranNetworkApi#identify}）：哪台设备（或哪个玩家的临时地址）、有什么能力、现在谁在操作。
 * 是查的那一刻的快照，不可变。
 * <p>
 * 来源可以信：路由器只收设备当前操作者发来的包、源地址必须是设备地址；玩家临时地址的源地址也由服务器校验。
 */
public final class SourceIdentity {
    /** 来源是哪一类地址 */
    public enum Kind {
        /** 设备表里的一台设备（家具电脑、SCP 电脑等） */
        DEVICE,
        /** 玩家临时地址（{@link PlayerAddresses}）：直接就是那个玩家 */
        PLAYER
    }

    public final Kind kind;
    public final Ipv6Address address;
    /** 设备在哪；玩家临时地址为 null */
    public final DeviceLocation location;
    private final Set<DeviceCapability> capabilities;
    /** 当前操作者；没人操作为 null（玩家临时地址就是那个玩家） */
    public final UUID operator;
    public final String operatorName;

    public SourceIdentity(Kind kind, Ipv6Address address, DeviceLocation location, Set<DeviceCapability> capabilities,
                          UUID operator, String operatorName) {
        this.kind = kind;
        this.address = address;
        this.location = location;
        this.capabilities = capabilities == null || capabilities.isEmpty() ? Collections.<DeviceCapability>emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        this.operator = operator;
        this.operatorName = operator == null ? null : operatorName;
    }

    public Set<DeviceCapability> capabilities() {
        return capabilities;
    }

    public boolean has(DeviceCapability c) {
        return capabilities.contains(c);
    }

    /**
     * 能不能用游戏角色一键登录：设备声明了 {@link DeviceCapability#ONE_CLICK_LOGIN} 且现在有人操作；
     * 玩家临时地址总是可以（来源就是那个玩家）。
     */
    public boolean canOneClickLogin() {
        return operator != null && (kind == Kind.PLAYER || has(DeviceCapability.ONE_CLICK_LOGIN));
    }

    public boolean hasOperator() {
        return operator != null;
    }

    @Override
    public String toString() {
        return (kind == Kind.DEVICE ? "设备 " + address + "（维度 " + location + "）" : "玩家临时地址 " + address)
                + "，能力 " + capabilities + "，操作者 " + (operator == null ? "无" : operatorName + "（" + operator + "）");
    }
}
