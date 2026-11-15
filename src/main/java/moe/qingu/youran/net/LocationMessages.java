package moe.qingu.youran.net;

import io.netty.buffer.ByteBuf;
import moe.qingu.youran.api.network.location.LocationProof;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 定位模块：客户端（家具电脑这类协议栈在操作者客户端上的浏览器）请服务端给“我现在操作的这台设备”签位置（{@link Request}），
 * 服务端回结果（{@link Reply}）。坐标由服务端按设备表自己填，包里只有网站域名和随机数。
 */
public final class LocationMessages {
    private LocationMessages() {
    }

    public static final class Request implements IMessage {
        public int requestId;
        public String site;
        public String nonce;
        public boolean bad;

        public Request() {
        }

        public Request(int requestId, String site, String nonce) {
            this.requestId = requestId;
            this.site = site;
            this.nonce = nonce;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            PacketBuffer b = new PacketBuffer(buf);
            b.writeInt(requestId);
            b.writeString(site);
            b.writeString(nonce);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            PacketBuffer b = new PacketBuffer(buf);
            try {
                requestId = b.readInt();
                site = b.readString(253);
                nonce = b.readString(128);
            } catch (RuntimeException e) {
                bad = true;
                buf.skipBytes(buf.readableBytes());
            }
        }
    }

    public static final class Reply implements IMessage {
        public int requestId;
        public String status;
        /** 证明全文；没有为空串 */
        public String proof;

        public Reply() {
        }

        public Reply(int requestId, String status, String proof) {
            this.requestId = requestId;
            this.status = status;
            this.proof = proof == null ? "" : proof;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            PacketBuffer b = new PacketBuffer(buf);
            b.writeInt(requestId);
            b.writeString(status);
            b.writeString(proof);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            PacketBuffer b = new PacketBuffer(buf);
            try {
                requestId = b.readInt();
                status = b.readString(32);
                proof = b.readString(LocationProof.MAX_LENGTH);
            } catch (RuntimeException e) {
                status = "unavailable";
                proof = "";
                buf.skipBytes(buf.readableBytes());
            }
        }
    }

    /** Netty 线程上收到；转到服务端主线程处理 */
    public static final class RequestHandler implements IMessageHandler<Request, IMessage> {
        @Override
        public IMessage onMessage(Request m, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            if (m.bad) {
                return null;
            }
            player.getServerWorld().addScheduledTask(() -> {
                NetworkBackend b = NetworkHooks.backend();
                if (b != null) {
                    b.onLocationRequest(player, m.requestId, m.site, m.nonce);
                } else {
                    YouranNetwork.CHANNEL.sendTo(new Reply(m.requestId, "unavailable", ""), player);
                }
            });
            return null;
        }
    }

    public static final class ReplyHandler implements IMessageHandler<Reply, IMessage> {
        @Override
        public IMessage onMessage(Reply m, MessageContext ctx) {
            moe.qingu.youran.api.network.location.ClientLocation.onReply(m.requestId, m.status, m.proof);
            return null;
        }
    }
}
