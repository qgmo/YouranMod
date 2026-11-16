package moe.qingu.youran.client;

import moe.qingu.youran.YouranMod;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/** IC2 枯竭燃料棒挤过氙以后（NBT 里 {@code YouranDegassed}）悬停说明多一行“已脱气”（只在客户端） */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YouranMod.MODID)
public final class DegassedTooltip {
    private DegassedTooltip() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack s = event.getItemStack();
        if (!s.isEmpty() && s.hasTagCompound() && s.getTagCompound().getBoolean("YouranDegassed")) {
            event.getToolTip().add(I18n.format("youranmod.degassed"));
        }
    }
}
