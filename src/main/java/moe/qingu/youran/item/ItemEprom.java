package moe.qingu.youran.item;

import moe.qingu.youran.api.pcb.RomContent;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.litho.LampGlass;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.pcb.EpromBurn;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * EPROM（服主 11-16）：光刻机出来的 2764 芯片本体 + 做汞灯用的玻璃管（石英小窗）合成。NBT：
 * <pre>
 * {Chip:{芯片的 NBT（Model:"2764"、序列号、品质……）}, Window:{玻璃管的 NBT（Glass、Mold / Nines）}, Rom:{内容，见 RomContent；空白的没有}}
 * </pre>
 * 烧录器（电脑外设）把电脑磁盘上不超过 8 KB 的文件烧进去；紫外擦除器或匠魂晾干架（晒太阳，几个游戏日）擦掉。小窗的档次只影响烧录速度。
 */
public class ItemEprom extends ItemLangTooltip {
    public static final String TAG_CHIP = "Chip";
    public static final String TAG_WINDOW = "Window";
    public static final String TAG_ROM = "Rom";

    public ItemEprom() {
        super("eprom", 1);
    }

    public static boolean is(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof ItemEprom;
    }

    /** 2764 芯片 + 玻璃管 → EPROM（空白） */
    public static ItemStack make(ItemEprom item, ItemStack chip, ItemStack tube) {
        ItemStack out = new ItemStack(item);
        NBTTagCompound t = new NBTTagCompound();
        t.setTag(TAG_CHIP, chip.hasTagCompound() ? chip.getTagCompound().copy() : new NBTTagCompound());
        t.setTag(TAG_WINDOW, LampNbt.tag(LampNbt.tubeOf(tube.getTagCompound())));
        out.setTagCompound(t);
        return out;
    }

    /** 烧过东西（不是空白） */
    public static boolean erasable(ItemStack s) {
        return is(s) && s.hasTagCompound() && s.getTagCompound().hasKey(TAG_ROM, 10);
    }

    /** 内容（{@link RomContent} 的 NBT）；空白的为 null */
    @Nullable
    public static NBTTagCompound content(ItemStack s) {
        return erasable(s) ? s.getTagCompound().getCompoundTag(TAG_ROM) : null;
    }

    /** 擦干净以后 */
    public static ItemStack erased(ItemStack s) {
        ItemStack out = s.copy();
        if (out.hasTagCompound()) {
            out.getTagCompound().removeTag(TAG_ROM);
        }
        return out;
    }

    /** 烧进这些字节（服务端烧录器调） */
    public static void burn(ItemStack s, byte[] data) {
        if (!s.hasTagCompound()) {
            s.setTagCompound(new NBTTagCompound());
        }
        s.getTagCompound().setTag(TAG_ROM, RomContent.image(data));
    }

    /** 芯片部分的 NBT（品质；带进主板） */
    public static NBTTagCompound chip(ItemStack s) {
        return s.hasTagCompound() ? s.getTagCompound().getCompoundTag(TAG_CHIP) : new NBTTagCompound();
    }

    /** 小窗的玻璃管 */
    public static LampSpec window(ItemStack s) {
        return LampNbt.read(s.hasTagCompound() ? s.getTagCompound().getCompoundTag(TAG_WINDOW) : null);
    }

    /** 这块烧 1 KB 要多少 tick（看小窗） */
    public static int burnTicksPerKb(ItemStack s) {
        LampSpec w = window(s);
        return EpromBurn.ticksPerKb(w.glass, w.mold, w.nines);
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        // 没有芯片数据的 EPROM 没有意义（同芯片）；创造物品栏列一块标准的空白 EPROM（见 CreativeStandards）
        if (isInCreativeTab(tab)) {
            ItemStack s = new ItemStack(this);
            s.setTagCompound(CreativeStandards.eprom());
            items.add(s);
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        NBTTagCompound rom = content(stack);
        if (rom == null) {
            lines.add(I18n.format("youranmod.eprom.blank"));
        } else {
            lines.add(I18n.format("youranmod.eprom.content", rom.getInteger(RomContent.TAG_LEN),
                    String.format(java.util.Locale.ROOT, "%04X", rom.getInteger(RomContent.TAG_SUM))));
            if (flag.isAdvanced()) {
                lines.add("SHA-256 " + rom.getString(RomContent.TAG_SHA));
            }
        }
        LampSpec w = window(stack);
        lines.add(w.glass == LampGlass.QUARTZ ? I18n.format("youranmod.eprom.window.quartz", w.nines)
                : I18n.format("youranmod.eprom.window.glass", I18n.format("youranmod.wafer.mold." + (w.mold == null ? "clay" : w.mold.id))));
        ItemChip.chipLines(ChipModel.EPROM_2764, chip(stack), lines, flag);
    }
}
