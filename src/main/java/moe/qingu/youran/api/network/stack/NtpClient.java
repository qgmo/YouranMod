package moe.qingu.youran.api.network.stack;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.MalformedPacketException;
import moe.qingu.youran.api.network.packet.NtpPacket;

import java.util.concurrent.CompletableFuture;

/** 简单的 NTP 客户端（SNTP 的做法，RFC 4330）：发一个请求，用四个时间戳算偏差与往返 */
public final class NtpClient {
    static final long TIMEOUT_MS = 3000;

    private NtpClient() {
    }

    public static final class Result {
        /** 服务器的发送时间（Unix 毫秒） */
        public final long serverTimeMillis;
        /** 本机时钟比服务器慢多少毫秒（正数表示本机慢） */
        public final long offsetMillis;
        public final long roundTripMillis;
        public final int stratum;

        Result(long serverTimeMillis, long offsetMillis, long roundTripMillis, int stratum) {
            this.serverTimeMillis = serverTimeMillis;
            this.offsetMillis = offsetMillis;
            this.roundTripMillis = roundTripMillis;
            this.stratum = stratum;
        }
    }

    public static CompletableFuture<Result> query(NetStack stack, Ipv6Address server) {
        CompletableFuture<Result> f = new CompletableFuture<>();
        long[] sent = new long[1];
        stack.openUdp(null, 0, (socket, from, fromPort, to, data) -> {
            if (!from.equals(server) || fromPort != NtpPacket.PORT) {
                return;
            }
            long t4 = System.currentTimeMillis();
            NtpPacket p;
            try {
                p = NtpPacket.parse(data);
            } catch (MalformedPacketException e) {
                return;
            }
            if (p.mode != NtpPacket.MODE_SERVER || p.originTime != sent[0] || p.stratum == 0) {
                return;
            }
            socket.close();
            long t1 = NtpPacket.toUnixMillis(sent[0]);
            long t2 = NtpPacket.toUnixMillis(p.receiveTime);
            long t3 = NtpPacket.toUnixMillis(p.transmitTime);
            f.complete(new Result(t3, ((t2 - t1) + (t3 - t4)) / 2, (t4 - t1) - (t3 - t2), p.stratum));
        }).whenComplete((socket, err) -> {
            if (err != null) {
                f.completeExceptionally(err);
                return;
            }
            sent[0] = NtpPacket.fromUnixMillis(System.currentTimeMillis());
            socket.send(server, NtpPacket.PORT, NtpPacket.clientRequest(sent[0]).encode());
            stack.loop.schedule(() -> {
                if (!f.isDone()) {
                    socket.close();
                    f.completeExceptionally(new NetworkException(NetworkException.Reason.TIMEOUT, "NTP 服务器 " + server + " 没有回应"));
                }
            }, TIMEOUT_MS);
        });
        return f;
    }
}
