package moe.qingu.youran.net;

import io.netty.buffer.ByteBuf;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.litho.ContainerLitho;
import moe.qingu.youran.litho.ContainerMicroscope;
import moe.qingu.youran.litho.LithoGuiHandler;
import moe.qingu.youran.litho.TileLithoMaster;
import moe.qingu.youran.logic.litho.Alignment;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 光刻机界面的网络包：服务端 → 客户端的界面数据（{@link Sync}），客户端 → 服务端的操作（{@link Action}：开显微镜、回主界面、
 * 拧旋钮、完成对准）。服务端在主线程核对：玩家开着的正是这个窗口、离机器够近、机器还成形；拧旋钮、完成对准只认操作者。
 */
public final class LithoMessages {
    public static final int OPEN_MICROSCOPE = 0;
    public static final int OPEN_MAIN = 1;
    public static final int KNOB = 2;
    public static final int CONFIRM = 3;

    private LithoMessages() {
    }

    /** 旋钮参数：轴、方向、粗细 → 一个整数 */
    public static int knobArg(Alignment.Axis axis, int dir, boolean fine) {
        return axis.ordinal() * 4 + (dir > 0 ? 2 : 0) + (fine ? 1 : 0);
    }

    public static final class Sync implements IMessage {
        int windowId;
        NBTTagCompound data;

        public Sync() {
        }

        public Sync(int windowId, NBTTagCompound data) {
            this.windowId = windowId;
            this.data = data;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            windowId = buf.readInt();
            data = ByteBufUtils.readTag(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(windowId);
            ByteBufUtils.writeTag(buf, data);
        }
    }

    public static final class SyncHandler implements IMessageHandler<Sync, IMessage> {
        @Override
        public IMessage onMessage(Sync msg, MessageContext ctx) {
            if (msg.data != null) {
                moe.qingu.youran.client.litho.ClientLitho.receive(msg.windowId, msg.data);
            }
            return null;
        }
    }

    public static final class Action implements IMessage {
        int windowId;
        int action;
        int arg;

        public Action() {
        }

        public Action(int windowId, int action, int arg) {
            this.windowId = windowId;
            this.action = action;
            this.arg = arg;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            windowId = buf.readInt();
            action = buf.readByte();
            arg = buf.readByte();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(windowId);
            buf.writeByte(action);
            buf.writeByte(arg);
        }
    }

    public static final class ActionHandler implements IMessageHandler<Action, IMessage> {
        @Override
        public IMessage onMessage(Action msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(player, msg));
            return null;
        }

        static void handle(EntityPlayerMP player, Action msg) {
            Container c = player.openContainer;
            if (c == null || c.windowId != msg.windowId) {
                return;
            }
            TileLithoMaster m = c instanceof ContainerLitho ? ((ContainerLitho) c).master
                    : c instanceof ContainerMicroscope ? ((ContainerMicroscope) c).master : null;
            if (m == null || !m.usableBy(player)) {
                return;
            }
            switch (msg.action) {
                case OPEN_MICROSCOPE:
                    if (c instanceof ContainerLitho) {
                        LithoGuiHandler.openMicroscope(player, player.world, m.getPos());
                    }
                    break;
                case OPEN_MAIN:
                    if (c instanceof ContainerMicroscope) {
                        LithoGuiHandler.openMain(player, player.world, m.getPos());
                    }
                    break;
                case KNOB: {
                    if (!(c instanceof ContainerMicroscope) || !player.getUniqueID().equals(m.operator())) {
                        return;
                    }
                    int axis = msg.arg / 4;
                    if (axis < 0 || axis >= Alignment.Axis.values().length) {
                        YouranMod.LOG.warn("光刻机：{} 发来的旋钮参数不对：{}", player.getName(), msg.arg);
                        return;
                    }
                    m.turnKnob(Alignment.Axis.values()[axis], (msg.arg & 2) != 0 ? 1 : -1, (msg.arg & 1) != 0);
                    break;
                }
                case CONFIRM:
                    if (c instanceof ContainerMicroscope && player.getUniqueID().equals(m.operator())) {
                        m.confirmAlignment();
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
