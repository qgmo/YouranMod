package moe.qingu.youran.item;

import moe.qingu.youran.api.peripheral.PeripheralItem;
import moe.qingu.youran.registry.YouranTab;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * YouranMod 的外设物品（软驱、汉卡……；接口见 {@code api.peripheral}）：没有状态、可以堆叠 16 个。说明文字是翻译键
 * {@code item.<注册名>.tooltip}（三份语言文件），再加一行“关机时装进电脑的外设槽”。
 */
public class ItemPeripheral extends PeripheralItem {
    public ItemPeripheral(String name, String kind, String... capabilities) {
        super(kind, 16, capabilities);
        setRegistryName(name);
        setTranslationKey(name);
        setCreativeTab(YouranTab.tab);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        lines.add(I18n.format(getTranslationKey() + ".tooltip"));
        lines.add(I18n.format("youranmod.peripheral.tooltip"));
    }
}
