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
public class ItemPeripheral extends PeripheralItem implements MetaModelled {
    /** 外壳（服主 11-16：软驱、烧录器、定位模块合成时四角用钢板是金属壳，用聚氯乙烯片是塑料壳；只是样子不同，元数据 0 / 1） */
    public static final int CASING_STEEL = 0;
    public static final int CASING_PVC = 1;
    private boolean casings;

    public ItemPeripheral(String name, String kind, String... capabilities) {
        super(kind, 16, capabilities);
        setRegistryName(name);
        setTranslationKey(name);
        setCreativeTab(YouranTab.tab);
    }

    /** 有钢壳、塑料壳两种样子 */
    public ItemPeripheral withCasings() {
        casings = true;
        setHasSubtypes(true);
        setMaxDamage(0);
        return this;
    }

    @Override
    public void getSubItems(net.minecraft.creativetab.CreativeTabs tab, net.minecraft.util.NonNullList<ItemStack> items) {
        if (!isInCreativeTab(tab)) {
            return;
        }
        items.add(new ItemStack(this, 1, CASING_STEEL));
        if (casings) {
            items.add(new ItemStack(this, 1, CASING_PVC));
        }
    }

    @Override
    public int[] modelMetas() {
        return casings ? new int[]{CASING_STEEL, CASING_PVC} : new int[]{0};
    }

    @Override
    public String modelName(int meta) {
        return getRegistryName().getPath() + (casings && meta == CASING_PVC ? "_pvc" : "");
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        lines.add(I18n.format(getTranslationKey() + ".tooltip"));
        lines.add(I18n.format("youranmod.peripheral.tooltip"));
    }
}
