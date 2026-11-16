package moe.qingu.youran.item;

import moe.qingu.youran.logic.pcb.BoardType;
import moe.qingu.youran.logic.pcb.PcbStage;
import moe.qingu.youran.registry.YouranTab;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 线路板（服主 11-16 的主板线）：空白主板 → 涂胶 → 曝光 → 蚀刻成光板 → 打孔，工序与种类编在元数据里（{@link PcbStage}）。
 * 名字“打好孔的普通电脑光板”这样（翻译键 {@code item.pcb.<工序>.name}，带种类的 %s 是种类名 {@code youranmod.board.<种类>}）。
 * 手工打孔的好坏在拿到手时定（{@link HandPunch}）。
 */
public class ItemPcb extends Item implements MetaModelled {
    public ItemPcb() {
        setRegistryName("pcb");
        setTranslationKey("pcb");
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(16);
        setCreativeTab(YouranTab.tab);
    }

    @Override
    public String getTranslationKey(ItemStack stack) {
        String stage = PcbStage.stageName(stack.getMetadata());
        return "item.pcb." + (stage == null ? "blank" : stage);
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        BoardType t = PcbStage.type(stack.getMetadata());
        String key = getTranslationKey(stack) + ".name";
        return t == null ? net.minecraft.util.text.translation.I18n.translateToLocal(key)
                : net.minecraft.util.text.translation.I18n.translateToLocalFormatted(key,
                net.minecraft.util.text.translation.I18n.translateToLocal("youranmod.board." + t.name));
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        String key = getTranslationKey(stack) + ".tooltip";
        if (I18n.hasKey(key)) {
            lines.add(I18n.format(key));
        }
        if (PcbStage.spoiled(stack.getMetadata())) {
            lines.add(I18n.format("item.pcb.spoiled"));
        }
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (isInCreativeTab(tab)) {
            for (int meta : PcbStage.all()) {
                items.add(new ItemStack(this, 1, meta));
            }
        }
    }

    @Override
    public int[] modelMetas() {
        return PcbStage.all();
    }

    /** 贴图：工序 + 种类（变质胶的看着一样） */
    @Override
    public String modelName(int meta) {
        String stage = PcbStage.stageName(meta);
        BoardType t = PcbStage.type(meta);
        return "pcb_" + (stage == null ? "blank" : stage) + (t == null ? "" : "_" + t.name);
    }

    @Override
    public void onCreated(ItemStack stack, World world, EntityPlayer player) {
        HandPunch.resolve(stack, world);
    }

    @Override
    public void onUpdate(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        HandPunch.resolve(stack, world);
    }

    @Override
    public boolean onEntityItemUpdate(EntityItem entity) {
        HandPunch.resolve(entity.getItem(), entity.world);
        return false;
    }
}
