package moe.qingu.youran.client.litho;

import moe.qingu.youran.litho.LithoSyncTarget;
import moe.qingu.youran.logic.litho.ChipModel;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Container;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** 光刻相关的客户端入口（只在客户端调用） */
@SideOnly(Side.CLIENT)
public final class ClientLitho {
    private ClientLitho() {
    }

    /** 服务端发来的界面数据（在网络线程上收到，转到主线程） */
    public static void receive(int windowId, NBTTagCompound data) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            Container c = mc.player == null ? null : mc.player.openContainer;
            if (c instanceof LithoSyncTarget && c.windowId == windowId) {
                ((LithoSyncTarget) c).receive(data);
            }
        });
    }

    /** 右键芯片设计图 / 掩模：打开版图画面 */
    public static void openLayout(ChipModel model, boolean mask) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiChipLayout(model, mask));
    }
}
