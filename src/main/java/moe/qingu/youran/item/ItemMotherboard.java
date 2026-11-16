package moe.qingu.youran.item;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.logic.pcb.BoardType;
import moe.qingu.youran.registry.YouranTab;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 主板（服主 11-16）：打好孔的光板在工程师装配台“主板”蓝图上装芯片，一次一块（芯片的品质数据都记进来，{@link BoardNbt}）。
 * 元数据 = 种类（普通电脑 0、个人电脑 1）。装配台只有 6 个输入格、芯片一片一格，所以分几次装：CPU、ROM、内存（一次最多 5 颗），
 * 装齐了（{@link BoardNbt#complete}）才能合成电脑。
 */
public class ItemMotherboard extends Item implements MetaModelled {
    public ItemMotherboard() {
        setRegistryName("motherboard");
        setTranslationKey("motherboard");
        setHasSubtypes(true);
        setMaxDamage(0);
        setMaxStackSize(1);
        setCreativeTab(YouranTab.tab);
    }

    @Nullable
    public static BoardType type(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof ItemMotherboard)) {
            return null;
        }
        BoardType t = BoardType.byId(s.getMetadata());
        return t != null && t.motherboard ? t : null;
    }

    /** 板子的数据（没有 NBT 时是空板子） */
    public static NBTTagCompound board(ItemStack s) {
        BoardType t = type(s);
        if (s.hasTagCompound() && s.getTagCompound().hasKey(BoardNbt.TAG, 10)) {
            return s.getTagCompound().getCompoundTag(BoardNbt.TAG);
        }
        return BoardNbt.empty(t == null ? BoardType.COMPUTER : t);
    }

    public static ItemStack make(Item item, BoardType t, NBTTagCompound board) {
        ItemStack s = new ItemStack(item, 1, t.id);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(BoardNbt.TAG, board);
        s.setTagCompound(tag);
        return s;
    }

    /** 芯片装齐了 */
    public static boolean complete(ItemStack s) {
        return type(s) != null && BoardNbt.complete(board(s));
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getItemStackDisplayName(ItemStack s) {
        BoardType t = type(s);
        return net.minecraft.util.text.translation.I18n.translateToLocalFormatted("item.motherboard.name",
                net.minecraft.util.text.translation.I18n.translateToLocal("youranmod.board." + (t == null ? "computer" : t.name)));
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        lines.add(I18n.format(complete(stack) ? "item.motherboard.complete" : "item.motherboard.tooltip"));
        BoardTooltip.lines(board(stack), lines, flag);
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        // 没装芯片的主板就是打好孔的光板（工序里的打孔板），不列；创造物品栏列装齐了标准芯片的（见 CreativeStandards）
        if (isInCreativeTab(tab)) {
            for (BoardType t : new BoardType[]{BoardType.COMPUTER, BoardType.PC}) {
                items.add(make(this, t, CreativeStandards.board(t)));
            }
        }
    }

    @Override
    public int[] modelMetas() {
        return new int[]{BoardType.COMPUTER.id, BoardType.PC.id};
    }

    @Override
    public String modelName(int meta) {
        return "motherboard_" + (meta == BoardType.PC.id ? "pc" : "computer");
    }
}
