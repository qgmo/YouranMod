package moe.qingu.youran.item;

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
 * 软盘套（服主 11-16：只加这一个物品，未冲孔 / 已冲孔 / 冲坏三种用元数据区分）。聚氯乙烯团压成套子，再冲出中心孔、读写窗；
 * 已冲孔的套子装上抛光盘片就是软盘。手工冲孔的好坏在拿到手时定（{@link HandPunch}）。
 */
public class ItemFloppyJacket extends Item implements MetaModelled {
    public static final int UNPUNCHED = 0;
    public static final int PUNCHED = 1;
    public static final int BROKEN = 2;
    static final String[] NAMES = {"unpunched", "punched", "broken"};

    public ItemFloppyJacket() {
        setRegistryName("floppy_jacket");
        setTranslationKey("floppy_jacket");
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(16);
        setCreativeTab(YouranTab.tab);
    }

    static String name(int meta) {
        return NAMES[meta >= 0 && meta < NAMES.length ? meta : 0];
    }

    @Override
    public String getTranslationKey(ItemStack stack) {
        return "item.floppy_jacket." + name(stack.getMetadata());
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        lines.add(I18n.format(getTranslationKey(stack) + ".tooltip"));
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (isInCreativeTab(tab)) {
            for (int i = 0; i < NAMES.length; i++) {
                items.add(new ItemStack(this, 1, i));
            }
        }
    }

    @Override
    public int[] modelMetas() {
        return new int[]{UNPUNCHED, PUNCHED, BROKEN};
    }

    @Override
    public String modelName(int meta) {
        return "floppy_jacket_" + name(meta);
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
