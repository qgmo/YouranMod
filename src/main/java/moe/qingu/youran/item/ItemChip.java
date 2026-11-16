package moe.qingu.youran.item;

import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.litho.ChipPackage;
import moe.qingu.youran.logic.litho.ResistType;
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

/** 芯片（光刻机产出，每个都独一无二，NBT 见 {@link ChipNbt}；型号是元数据，NBT 里的 {@code Model} 照样写一份，芯片数据抄进主板、EPROM 时带着型号）。报废的名字前加“报废的”。创造模式物品栏里不列（没有参数的芯片没有意义） */
public class ItemChip extends ItemModelled {
    public ItemChip() {
        super("chip");
    }

    public static boolean scrapped(ItemStack stack) {
        return stack.hasTagCompound() && stack.getTagCompound().getBoolean(ChipNbt.SCRAPPED);
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        String name = super.getItemStackDisplayName(stack);
        return scrapped(stack) ? net.minecraft.util.text.translation.I18n.translateToLocalFormatted("item.chip.scrapped", name) : name;
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        chipLines(model(stack), stack.getTagCompound(), lines, flag);
    }

    /** 一片芯片的说明（芯片、EPROM、主板上的芯片共用）：序列号、报废原因或三项指标、额定频率、做这片时的条件 */
    @SideOnly(Side.CLIENT)
    public static void chipLines(@Nullable ChipModel model, @Nullable NBTTagCompound tag, List<String> lines, ITooltipFlag flag) {
        if (tag == null) {
            return;
        }
        lines.add(I18n.format("youranmod.chip.serial", tag.getString(ChipNbt.SERIAL)));
        if (tag.getBoolean(ChipNbt.SCRAPPED)) {
            lines.add(I18n.format("youranmod.chip.scrap." + tag.getString(ChipNbt.SCRAP_REASON)));
        } else {
            if (model != null && !model.isCpu()) {
                lines.add(I18n.format("youranmod.chip.max_freq_ram", mhz(tag.getInteger(ChipNbt.MAX_FREQ)),
                        ChipModel.accessNs(tag.getInteger(ChipNbt.MAX_FREQ))));
            } else {
                lines.add(I18n.format("youranmod.chip.max_freq", mhz(tag.getInteger(ChipNbt.MAX_FREQ))));
            }
            lines.add(I18n.format("youranmod.chip.heat", tag.getInteger(ChipNbt.HEAT), Math.round(tag.getInteger(ChipNbt.HEAT) / 60.0)));
            lines.add(I18n.format("youranmod.chip.lifetime", tag.getLong(ChipNbt.LIFETIME) / 3600));
        }
        ChipModel kindOf = model;
        if (kindOf != null && !kindOf.isCpu()) {
            lines.add(I18n.format("youranmod.chip.rated_access", ChipModel.accessNs(kindOf.ratedKHz), mhz(kindOf.ratedKHz)));
        } else {
            lines.add(I18n.format("youranmod.chip.freq", mhz(tag.getInteger(ChipNbt.STD_FREQ)), mhz(tag.getInteger(ChipNbt.MIN_FREQ)),
                    mhz(tag.getInteger(ChipNbt.MAX_ADJ_FREQ))));
        }
        if (flag.isAdvanced() || net.minecraft.client.gui.GuiScreen.isShiftKeyDown()) {
            NBTTagCompound made = tag.getCompoundTag(ChipNbt.MADE);
            ChipPackage pack = ChipPackage.byId(made.getString("Package"));
            ResistType resist = ResistType.byId(made.getString("Resist"));
            lines.add(I18n.format("youranmod.chip.made", made.getInteger("Nines"),
                    I18n.format("youranmod.wafer.mold." + (made.getString("Mold").isEmpty() ? "clay" : made.getString("Mold"))),
                    I18n.format("youranmod.resist." + (resist == null ? "negative" : resist.id)),
                    made.getInteger("Solvent") / 10.0, made.getInteger("Align") / 10.0,
                    I18n.format("youranmod.package." + (pack == null ? "iron" : pack.id))));
        } else {
            lines.add(I18n.format("youranmod.chip.shift"));
        }
    }

    /** 频率给人看（带单位：1 GHz 以上写 GHz，见 {@link ChipModel#formatFreq}） */
    static String mhz(int khz) {
        return ChipModel.formatFreq(khz);
    }

    /** 这片芯片的型号；没有时为 null */
    @Nullable
    public static ChipModel chipModel(ItemStack stack) {
        return model(stack);
    }
}
