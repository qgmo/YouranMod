package moe.qingu.youran.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** 客户端的预初始化（只在客户端调用） */
@SideOnly(Side.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    public static void preInit() {
        // 首次载入资源在所有模组预初始化之后，这时加进去赶得上
        try {
            @SuppressWarnings("unchecked")
            java.util.List<net.minecraft.client.resources.IResourcePack> packs = (java.util.List<net.minecraft.client.resources.IResourcePack>)
                    net.minecraftforge.fml.relauncher.ReflectionHelper.findField(Minecraft.class, "field_110449_ao", "defaultResourcePacks")
                            .get(Minecraft.getMinecraft());
            packs.add(Ic2SoundPack.create());
        } catch (ReflectiveOperationException | RuntimeException e) {
            moe.qingu.youran.YouranMod.LOG.warn("加不了 IC2 声音的资源包，光刻机的嗡嗡声与报警放不出来：{}", e.toString());
        }
    }
}
