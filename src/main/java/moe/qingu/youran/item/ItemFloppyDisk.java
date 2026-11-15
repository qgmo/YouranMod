package moe.qingu.youran.item;

import moe.qingu.youran.registry.ModItems;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 5.25 英寸软盘（360KB，双面双密度），SCP 个人电脑的软驱 B: 读它。设计“软盘的数据存放”（服主 11-15）：
 * <ul>
 *     <li>物品 NBT 只存盘号、标签名（工具盘另记镜像名），见 {@link FloppyNbt}；盘里的内容在 079 服务那边，客户端拿不到；</li>
 *     <li>堆叠数 1；生产出来时就分配盘号（盘号决定初始坏道）。“生产出来”取物品第一次在服务端的物品栏里（每 tick 的 {@code onUpdate}）、
 *     地上（{@code onEntityItemUpdate}）、合成出来（{@code onCreated}）或插进软驱（电脑那边调 {@link #ensureId}）的时候——
 *     还没分配盘号的盘只是一张没人见过的盘，在机器的输出槽里也一样，堆叠数 1 所以不会叠成一摞，分配时机早晚不影响什么；</li>
 *     <li>工具盘（{@link ToolDisk}）由 {@link #toolDisk} 生成（以后 MineBay 上架调它）。</li>
 * </ul>
 */
public class ItemFloppyDisk extends ItemWithTooltip {
    public ItemFloppyDisk() {
        super("floppy_disk", 1, "");
    }

    /** 盘号（没有时 0） */
    public static long diskId(ItemStack stack) {
        return FloppyNbt.diskId(stack.getTagCompound());
    }

    /** 标签名（没有时空） */
    public static String label(ItemStack stack) {
        return FloppyNbt.label(stack.getTagCompound());
    }

    /** 工具盘的镜像名（空白盘为空） */
    public static String image(ItemStack stack) {
        return FloppyNbt.image(stack.getTagCompound());
    }

    /** 是软盘 */
    public static boolean is(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ItemFloppyDisk;
    }

    /** 还没有盘号时分配一个（只在服务端；客户端什么都不做），返回盘号（客户端上没有时 0） */
    public static long ensureId(ItemStack stack, World world) {
        long id = diskId(stack);
        if (id != 0 || world == null || world.isRemote || !is(stack)) {
            return id;
        }
        id = FloppyIds.issue(world);
        stack.setTagCompound(FloppyNbt.withId(stack.getTagCompound(), id));
        return id;
    }

    /** 生成一张工具盘（服务端；带新盘号）。以后 MineBay 上架时调用 */
    public static ItemStack toolDisk(ToolDisk tool, World world) {
        ItemStack stack = new ItemStack(ModItems.floppyDisk);
        stack.setTagCompound(FloppyNbt.toolDisk(FloppyIds.issue(world), tool.image, tool.label));
        return stack;
    }

    @Override
    public void onUpdate(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (!world.isRemote && diskId(stack) == 0) {
            ensureId(stack, world);
        }
    }

    @Override
    public boolean onEntityItemUpdate(EntityItem entity) {
        if (!entity.world.isRemote && diskId(entity.getItem()) == 0) {
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

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> lines, ITooltipFlag flag) {
        String label = label(stack);
        if (!label.isEmpty()) {
            lines.add(I18n.format("item.floppy_disk.label", label));
        }
        lines.add(I18n.format("item.floppy_disk.tooltip"));
        if (flag.isAdvanced() && diskId(stack) != 0) {
            lines.add(I18n.format("item.floppy_disk.id", Long.toHexString(diskId(stack)).toUpperCase()));
        }
    }
}
