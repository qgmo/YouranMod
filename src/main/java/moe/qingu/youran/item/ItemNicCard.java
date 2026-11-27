package moe.qingu.youran.item;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.api.pcb.NicSpec;
import moe.qingu.youran.logic.pcb.BoardType;
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
 * 个人电脑的 ISA 网卡（外设，种类 {@code nic_pc}；2.3.1.0 第 1 期）：打好孔的 ISA 扩展卡光板 + 网卡控制芯片，在工程师装配台“扩展卡”蓝图上装，
 * 一次一块。芯片的品质数据记进 NBT 的 {@code Board}（{@link BoardNbt}，{@code Type:"isa"}、{@code Ctrl}）；规格（同时连接数、缓冲区）
 * 不存，每次由芯片品质现算（{@link NicSpec}）。每块芯片都不一样，所以不能堆叠。
 * <p>
 * 插进个人电脑的外设槽，电脑就接进游戏内网络（079 服务里是 I/O 300h、IRQ 3、INT F8h 的那块卡）。
 */
public class ItemNicCard extends ItemPeripheral {
    public ItemNicCard(String name, String kind) {
        super(name, kind);
        setMaxStackSize(1);
    }

    /** 一块装好的网卡（板子数据 board） */
    public ItemStack make(NBTTagCompound board) {
        ItemStack s = new ItemStack(this);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(BoardNbt.TAG, board.copy());
        s.setTagCompound(tag);
        return s;
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        // 创造物品栏给一块装着标准品质网卡控制芯片的（同别的芯片的“标准”变种）
        if (isInCreativeTab(tab)) {
            items.add(make(CreativeStandards.card(BoardType.ISA)));
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        NicSpec spec = NicSpec.ofItem(stack);
        if (spec == null) {
            lines.add(I18n.format("youranmod.nic.broken"));
        } else {
            lines.add(I18n.format("youranmod.nic.spec", spec.conns, spec.rxBytes, spec.txBytes));
        }
        if (stack.hasTagCompound() && stack.getTagCompound().hasKey(BoardNbt.TAG, 10)) {
            BoardTooltip.lines(stack.getTagCompound().getCompoundTag(BoardNbt.TAG), lines, flag);
        }
    }
}
