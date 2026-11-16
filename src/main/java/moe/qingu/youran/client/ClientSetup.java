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
                    defaultResourcePacksField().get(Minecraft.getMinecraft());
            Ic2SoundPack ic2Sounds = Ic2SoundPack.create();
            packs.add(ic2Sounds);
            // Forge 在模组预初始化之前就重载过一次资源，声音的登记（sounds.json 里文件在不在）紧接着用的是那次的状态：
            // 光加进列表要等下一次整体重载才生效，所以同时直接加进正在用的资源管理器
            net.minecraft.client.resources.IResourceManager manager = Minecraft.getMinecraft().getResourceManager();
            if (manager instanceof net.minecraft.client.resources.SimpleReloadableResourceManager) {
                ((net.minecraft.client.resources.SimpleReloadableResourceManager) manager).reloadResourcePack(ic2Sounds);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            moe.qingu.youran.YouranMod.LOG.warn("加不了 IC2 声音的资源包，光刻机的嗡嗡声与报警放不出来：{}", e.toString());
        }
    }

    /**
     * 原版的默认资源包列表：正式环境里叫 SRG 名，开发环境里叫 MCP 名，都试一遍。
     * 不用 Forge 的 {@code ReflectionHelper.findField}：实测它在开发环境里只试第一个名字就抛异常，没试到 MCP 名。
     */
    private static java.lang.reflect.Field defaultResourcePacksField() throws NoSuchFieldException {
        for (String name : new String[]{"field_110449_ao", "defaultResourcePacks"}) {
            try {
                java.lang.reflect.Field f = Minecraft.class.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 换下一个名字
            }
        }
        throw new NoSuchFieldException("defaultResourcePacks");
    }
}
