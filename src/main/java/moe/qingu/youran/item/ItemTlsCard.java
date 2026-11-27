package moe.qingu.youran.item;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.api.pcb.TlsSpec;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 个人电脑的 ISA 加密卡（外设，种类 {@code tls_pc}；2.3.1.0 第 4 期）：打好孔的 ISA 扩展卡光板 + 加密协处理器，在工程师装配台“扩展卡”蓝图上装，
 * 一次一块。芯片的品质数据记进 NBT 的 {@code Board}（{@link BoardNbt}，{@code Type:"isa"}、{@code Ctrl}）；规格不存，由芯片品质现算
 * （{@link TlsSpec}）。另有卡号 {@code CardId}：卡里的私钥（A 方式）与证书链按卡号存在游戏服务器，不进物品 NBT，卡换到哪台电脑都跟着走。
 * 卡号照软盘盘号的办法在服务端分配（{@link TlsCardIds}）。每块芯片都不一样，所以不能堆叠。
 * <p>
 * 插进个人电脑的外设槽，程序就能经 INT F9h 做 TLS（HTTPS）：卡是 TLS 引擎，不碰网络，字节由程序在网卡与它之间搬。
 */
public class ItemTlsCard extends ItemPeripheral {
    public ItemTlsCard(String name, String kind) {
        super(name, kind);
        setMaxStackSize(1);
    }

    /** 一块装好的加密卡（板子数据 board；还没有卡号） */
    public ItemStack make(NBTTagCompound board) {
        ItemStack s = new ItemStack(this);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(BoardNbt.TAG, board.copy());
        s.setTagCompound(tag);
        return s;
    }

    /** 还没有卡号时分配一个（只在服务端；客户端什么都不做），返回卡号（客户端上没有时 0） */
    public static long ensureId(ItemStack stack, World world) {
        long id = TlsSpec.cardId(stack);
        if (id != 0 || world == null || world.isRemote || stack.isEmpty() || !(stack.getItem() instanceof ItemTlsCard)) {
            return id;
        }
        id = TlsCardIds.issue(world);
        NBTTagCompound tag = stack.hasTagCompound() ? stack.getTagCompound() : new NBTTagCompound();
        tag.setLong(TlsSpec.CARD_ID, id);
        stack.setTagCompound(tag);
        return id;
    }

    @Override
    public void onUpdate(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (!world.isRemote && TlsSpec.cardId(stack) == 0) {
            ensureId(stack, world);
        }
    }

    @Override
    public boolean onEntityItemUpdate(EntityItem entity) {
        if (!entity.world.isRemote && TlsSpec.cardId(entity.getItem()) == 0) {
            ItemStack s = entity.getItem().copy();
            ensureId(s, entity.world);
            entity.setItem(s);
        }
        return false;
    }

    @Override
    public void onCreated(ItemStack stack, World world, EntityPlayer player) {
        ensureId(stack, world);
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        // 创造物品栏给一块装着标准品质加密协处理器的（拿到物品栏里才分配卡号）
        if (isInCreativeTab(tab)) {
            items.add(make(CreativeStandards.card(BoardType.ISA, ChipModel.TLS_COPROC)));
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        TlsSpec spec = TlsSpec.ofItem(stack);
        if (spec == null) {
            lines.add(I18n.format("youranmod.tls.broken"));
        } else {
            lines.add(I18n.format("youranmod.tls.spec", spec.handshakes, spec.sessions, spec.bytesPerSecond / 1024));
        }
        long id = TlsSpec.cardId(stack);
        if (id != 0) {
            lines.add(I18n.format("youranmod.tls.card", Long.toHexString(id).toUpperCase(java.util.Locale.ROOT)));
        }
        if (stack.hasTagCompound() && stack.getTagCompound().hasKey(BoardNbt.TAG, 10)) {
            BoardTooltip.lines(stack.getTagCompound().getCompoundTag(BoardNbt.TAG), lines, flag);
        }
    }
}
