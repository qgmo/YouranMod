package moe.qingu.youran.net;

import io.netty.buffer.ByteBuf;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /ynet} 在玩家客户端上跑的子命令（服主 11-27：ping、dns、get、post、ntp 从执行者自己的玩家地址发出）：
 * 服务端查过权限后叫他的客户端跑（{@link Run}），客户端把结果一行一行回给服务端（{@link Line}），服务端显示给执行命令的人。
 */
public final class ProbeMessages {
    /** 一行最长（字） */
    public static final int MAX_LINE = 512;
    public static final int MAX_ARGS = 32;

    private ProbeMessages() {
    }

    /** 服务端 → 客户端：跑一个子命令 */
    public static final class Run implements IMessage {
        public long id;
        public String op;
        public String[] args;
        public boolean bad;

        public Run() {
        }

        public Run(long id, String op, String[] args) {
            this.id = id;
            this.op = op;
            this.args = args;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            PacketBuffer b = new PacketBuffer(buf);
            b.writeLong(id);
            b.writeString(op);
            b.writeByte(args.length);
            for (String a : args) {
                b.writeString(a);
            }
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            PacketBuffer b = new PacketBuffer(buf);
            try {
                id = b.readLong();
                op = b.readString(16);
                int n = b.readByte();
                if (n < 0 || n > MAX_ARGS) {
                    throw new IllegalArgumentException("参数太多");
                }
                List<String> list = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    list.add(b.readString(4096));
                }
                args = list.toArray(new String[0]);
            } catch (RuntimeException e) {
                bad = true;
            }
            buf.skipBytes(buf.readableBytes());
        }
    }

    /** 客户端 → 服务端：结果的一行（{@code done} 为最后一个，这时 {@code text} 可以为空） */
    public static final class Line implements IMessage {
        public long id;
        public String text;
        public boolean done;
        public boolean bad;

        public Line() {
        }

        public Line(long id, String text, boolean done) {
            this.id = id;
            this.text = text.length() > MAX_LINE ? text.substring(0, MAX_LINE) : text;
            this.done = done;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            PacketBuffer b = new PacketBuffer(buf);
            b.writeLong(id);
            b.writeString(text);
            b.writeBoolean(done);
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            PacketBuffer b = new PacketBuffer(buf);
            try {
                id = b.readLong();
                text = b.readString(MAX_LINE);
                done = b.readBoolean();
            } catch (RuntimeException e) {
                bad = true;
            }
            buf.skipBytes(buf.readableBytes());
        }
    }

    public static final class RunHandler implements IMessageHandler<Run, IMessage> {
        @Override
        public IMessage onMessage(Run m, MessageContext ctx) {
            if (!m.bad) {
                moe.qingu.youran.client.network.ClientProbes.run(m.id, m.op, m.args);
            }
            return null;
        }
    }

    /** Netty 线程上收到；转到服务端主线程交给命令 */
    public static final class LineHandler implements IMessageHandler<Line, IMessage> {
        @Override
        public IMessage onMessage(Line m, MessageContext ctx) {
            if (m.bad) {
                return null;
            }
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                NetworkBackend b = NetworkHooks.backend();
                if (b != null) {
                    b.onProbeLine(player, m.id, m.text, m.done);
                }
            });
            return null;
        }
    }
}
