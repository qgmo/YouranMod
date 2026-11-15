package moe.qingu.youran.net;

import io.netty.buffer.ByteBuf;
import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 系统确认框（{@code api.network.SystemConfirm}）的三种包：服务端叫客户端弹框（{@link Show}）、关框（{@link Close}），
 * 客户端回应（{@link Answer}）。服务端收到回应后在主线程上核对（是不是被问的玩家、题号对不对）。
 */
public final class ConfirmMessages {
    private ConfirmMessages() {
    }

    /** 服务端 → 客户端：弹框 */
    public static final class Show implements IMessage {
        public long id;
        public String title;
        /** 网站域名（A-label；客户端自己转成显示用的写法） */
        public String site;
        public List<String> items;
        public int seconds;
        /** 包坏了 */
        public boolean bad;

        public Show() {
        }

        public Show(long id, ConfirmRequest r, int seconds) {
            this.id = id;
            this.title = r.title;
            this.site = r.site;
            this.items = r.items;
            this.seconds = seconds;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            PacketBuffer b = new PacketBuffer(buf);
            b.writeLong(id);
            b.writeString(title);
            b.writeString(site);
            b.writeByte(items.size());
            for (String i : items) {
                b.writeString(i);
            }
            b.writeShort(seconds);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            PacketBuffer b = new PacketBuffer(buf);
            try {
                id = b.readLong();
                title = b.readString(ConfirmRequest.MAX_TITLE * 2);
                site = b.readString(253);
                int n = b.readByte();
                if (n < 0 || n > ConfirmRequest.MAX_ITEMS) {
                    throw new IllegalArgumentException("条目太多");
                }
                List<String> list = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    list.add(b.readString(ConfirmRequest.MAX_ITEM * 2));
                }
                items = Collections.unmodifiableList(list);
                seconds = b.readShort();
            } catch (RuntimeException e) {
                bad = true;
                buf.skipBytes(buf.readableBytes());
            }
        }
    }

    /** 服务端 → 客户端：框作废了（超时、操作者变了） */
    public static final class Close implements IMessage {
        public long id;

        public Close() {
        }

        public Close(long id) {
            this.id = id;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(id);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            id = buf.readableBytes() >= 8 ? buf.readLong() : 0;
            buf.skipBytes(buf.readableBytes());
        }
    }

    /** 客户端 → 服务端：操作者点了同意或拒绝 */
    public static final class Answer implements IMessage {
        public long id;
        public boolean approve;

        public Answer() {
        }

        public Answer(long id, boolean approve) {
            this.id = id;
            this.approve = approve;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(id);
            buf.writeBoolean(approve);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            if (buf.readableBytes() >= 9) {
                id = buf.readLong();
                approve = buf.readBoolean();
            } else {
                id = 0;
            }
            buf.skipBytes(buf.readableBytes());
        }
    }

    public static final class ShowHandler implements IMessageHandler<Show, IMessage> {
        @Override
        public IMessage onMessage(Show m, MessageContext ctx) {
            if (!m.bad) {
                moe.qingu.youran.client.confirm.ClientConfirms.show(m.id, m.title, m.site, m.items, m.seconds);
            }
            return null;
        }
    }

    public static final class CloseHandler implements IMessageHandler<Close, IMessage> {
        @Override
        public IMessage onMessage(Close m, MessageContext ctx) {
            moe.qingu.youran.client.confirm.ClientConfirms.close(m.id);
            return null;
        }
    }

    /** 在 Netty 线程上收到；转到服务端主线程核对 */
    public static final class AnswerHandler implements IMessageHandler<Answer, IMessage> {
        @Override
        public IMessage onMessage(Answer m, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            long id = m.id;
            boolean approve = m.approve;
            player.getServerWorld().addScheduledTask(() -> {
                NetworkBackend b = NetworkHooks.backend();
                if (b != null) {
                    b.onConfirmAnswer(player, id, approve);
                }
            });
            return null;
        }
    }
}
