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

/** 芯片（光刻机产出，每个都独一无二，NBT 见 {@link ChipNbt}）。报废的名字前加“报废的”。创造模式物品栏里不列（没有参数的芯片没有意义） */
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
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            return;
        }
        lines.add(I18n.format("youranmod.chip.serial", tag.getString(ChipNbt.SERIAL)));
        if (tag.getBoolean(ChipNbt.SCRAPPED)) {
            lines.add(I18n.format("youranmod.chip.scrap." + tag.getString(ChipNbt.SCRAP_REASON)));
        } else {
            ChipModel model = ChipModel.byId(tag.getString(ChipModel.TAG));
            if (model != null && !model.isCpu()) {
                lines.add(I18n.format("youranmod.chip.max_freq_ram", mhz(tag.getInteger(ChipNbt.MAX_FREQ)),
                        ChipModel.accessNs(tag.getInteger(ChipNbt.MAX_FREQ))));
            } else {
                lines.add(I18n.format("youranmod.chip.max_freq", mhz(tag.getInteger(ChipNbt.MAX_FREQ))));
            }
            lines.add(I18n.format("youranmod.chip.heat", tag.getInteger(ChipNbt.HEAT), Math.round(tag.getInteger(ChipNbt.HEAT) / 60.0)));
            lines.add(I18n.format("youranmod.chip.lifetime", tag.getLong(ChipNbt.LIFETIME) / 3600));
        }
        ChipModel kindOf = ChipModel.byId(tag.getString(ChipModel.TAG));
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

    static String mhz(int khz) {
        return String.format(java.util.Locale.ROOT, "%.2f", khz / 1000.0);
    }

    /** 这片芯片的型号；没有时为 null */
    @Nullable
    public static ChipModel chipModel(ItemStack stack) {
        return model(stack);
    }
}
