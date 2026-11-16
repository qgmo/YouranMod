package moe.qingu.youran.compat.carryon;

import moe.qingu.youran.YouranMod;
import net.minecraft.block.Block;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.event.FMLInterModComms;

/**
 * 搬运模组 Carry On 的兼容（服主 11-16：不写整合包的 carryon.cfg，用它的接口禁止，照 YouranSCP 的做法）：
 * 用 Carry On 的 IMC 接口 {@code blacklistBlock} 把不能搬的方块加进它的黑名单。
 *
 * <p>现在是光刻机：成形后的多方块方块、光源方块、对准台。搬走一格结构就坏了；光源搬走虽然数据跟着走，
 * 结构也会坏。以后加的光源方块（如准分子激光光源）也加在这里。
 *
 * <p>只用 Forge 的 IMC，不引用 Carry On 的类；没装 Carry On 时直接返回。要在初始化阶段发（Carry On 在 IMC 阶段读取）。
 */
public final class CarryOnCompat {
    public static final String MODID = "carryon";

    private CarryOnCompat() {
    }

    /** 不能搬的方块（注册名） */
    public static String[] forbiddenBlocks() {
        Block[] blocks = {
                moe.qingu.youran.registry.ModBlocks.lithoPart,
                moe.qingu.youran.registry.ModBlocks.mercuryLightSource,
                moe.qingu.youran.registry.ModBlocks.alignmentTable,
        };
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Block b : blocks) {
            if (b != null && b.getRegistryName() != null) {
                out.add(b.getRegistryName().toString());
            }
        }
        return out.toArray(new String[0]);
    }

    public static void sendBlacklist() {
        if (!Loader.isModLoaded(MODID)) {
            return;
        }
        String[] ids = forbiddenBlocks();
        for (String id : ids) {
            FMLInterModComms.sendMessage(MODID, "blacklistBlock", id);
        }
        YouranMod.LOG.info("Carry On：加入黑名单的方块 {} 种", ids.length);
    }
}
