package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.Ipv6Packet;
import moe.qingu.youran.api.network.stack.EventLoop;
import moe.qingu.youran.net.NicMessages;

import java.util.UUID;

/**
 * 设备网卡在服务端的一侧（网络线程上）：设备发来的包先在这里校验，合格的才交给路由器。
 * 守住：只收当前操作者的包；源地址必须等于设备地址；包不超过 1280 字节；每秒包数、字节数有上限；
 * 没人操作、断电、区块没加载时不收。不合格的丢掉并记日志（{@link RejectLog} 限频）。
 */
final class NicGateway {
    private final EventLoop loop;
    private final DeviceTable devices;
    private final Router router;
    private final RejectLog rejects;
    private final int packetsPerSecond;
    private final int bytesPerSecond;
    volatile long accepted;

    NicGateway(EventLoop loop, DeviceTable devices, Router router, RejectLog rejects, int packetsPerSecond, int bytesPerSecond) {
        this.loop = loop;
        this.devices = devices;
        this.router = router;
        this.rejects = rejects;
        this.packetsPerSecond = packetsPerSecond;
        this.bytesPerSecond = bytesPerSecond;
    }

    /** 玩家客户端发来的（{@code packet} 为 null 表示消息本身不对，{@code messageLength} 是消息长度） */
    void fromPlayer(UUID player, String name, byte[] packet, int messageLength) {
        String source = "玩家 " + name;
        if (packet == null) {
            reject(source, messageLength > NicMessages.MAX_MESSAGE
                    ? "消息超过 " + NicMessages.MAX_MESSAGE + " 字节（" + messageLength + " 字节），包超过 1280 字节"
                    : "不是原始包消息或长度不对（" + messageLength + " 字节）");
            return;
        }
        DeviceTable.Device d = devices.byPlayer(player);
        if (d == null) {
            reject(source, "没有接着的设备");
            return;
        }
        DeviceTable.Attachment a = d.attachment;
        if (!(a instanceof DeviceTable.PlayerAttachment) || !((DeviceTable.PlayerAttachment) a).player.equals(player)) {
            reject(source, "不是 " + d.label() + " 现在的操作者");
            return;
        }
        check(d, packet, source + " 操作的" + d.label());
    }

    /** 服务端端点（如测试设备）发的 */
    void fromDevice(DeviceTable.Device d, byte[] packet) {
        check(d, packet, d.label());
    }

    private void check(DeviceTable.Device d, byte[] p, String source) {
        if (p.length > Ipv6Packet.MAX_PACKET) {
            reject(source, "包超过 1280 字节（" + p.length + " 字节）");
            return;
        }
        if (p.length < Ipv6Packet.HEADER || (p[0] & 0xF0) != 0x60) {
            reject(source, "不是 IPv6 包");
            return;
        }
        int plen = ((p[4] & 0xFF) << 8) | (p[5] & 0xFF);
        if (plen > p.length - Ipv6Packet.HEADER) {
            reject(source, "IPv6 首部的载荷长度超过实际长度");
            return;
        }
        String blocked = d.blockedReason();
        if (blocked != null) {
            reject(source, blocked);
            return;
        }
        Ipv6Address src = Ipv6Packet.sourceOf(p);
        if (!src.equals(d.address)) {
            reject(source, "源地址 " + src + " 不是设备地址");
            return;
        }
        if (d.limiter == null) {
            d.limiter = new RateLimiter(packetsPerSecond, bytesPerSecond);
        }
        if (!d.limiter.tryAcquire(p.length, loop.nowMillis())) {
            reject(source, "超过速率上限（每秒 " + packetsPerSecond + " 个包、" + bytesPerSecond + " 字节）");
            return;
        }
        accepted++;
        router.forward(p);
    }

    private void reject(String source, String reason) {
        rejects.reject(source, reason, loop.nowMillis());
    }
}
