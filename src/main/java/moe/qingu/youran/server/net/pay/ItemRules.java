package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.PayItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.Locale;

/**
 * 什么物品能用来付（服主 11-15）：任何物品都行，但默认不收<b>装着东西的容器</b>（有内容的潜影盒、背包类；空的照常），
 * 另外有可配置的黑名单（{@code pay.blacklist}）。
 * <p>
 * “装着东西”的判断：物品有物品栏能力（{@code IItemHandler}）且里面有东西；或者 NBT 里有装着物品的列表
 * （潜影盒的 {@code BlockEntityTag.Items}，背包类常见的 {@code Items} / {@code Inventory} / {@code Contents} 等，元素带 {@code id}）。
 */
final class ItemRules {
    /** NBT 里常见的“装着的物品”列表的键（不分大小写） */
    static final String[] CONTENT_KEYS = {"items", "inventory", "contents", "storeditems", "stored", "backpack"};
    private static final int MAX_DEPTH = 12;

    private ItemRules() {
    }

    /** 黑名单里的一条：{@code 模组:物品}、{@code 模组:物品@数据值}、{@code 模组:*} */
    static boolean blacklisted(PayItem item, String[] list) {
        if (list == null) {
            return false;
        }
        for (String raw : list) {
            String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int at = s.indexOf('@');
            String id = at < 0 ? s : s.substring(0, at);
            if (at >= 0) {
                try {
                    if (Integer.parseInt(s.substring(at + 1)) != item.meta) {
                        continue;
                    }
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            if (id.endsWith(":*") ? item.id.startsWith(id.substring(0, id.length() - 1)) : item.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 装着东西的容器（主线程） */
    static boolean filledContainer(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        try {
            if (stack.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
                IItemHandler h = stack.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
                if (h != null) {
                    for (int i = 0; i < h.getSlots(); i++) {
                        if (!h.getStackInSlot(i).isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // 别的模组的能力出错：按 NBT 判断
        }
        return stack.getTagCompound() != null && nbtHasContents(stack.getTagCompound(), 0);
    }

    /** NBT 里有没有装着物品的列表（不碰物品注册表，测试可用） */
    static boolean nbtHasContents(NBTTagCompound tag, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        for (String key : tag.getKeySet()) {
            NBTBase v = tag.getTag(key);
            if (v instanceof NBTTagList && isContentKey(key) && listHasItems((NBTTagList) v)) {
                return true;
            }
            if (v instanceof NBTTagCompound && nbtHasContents((NBTTagCompound) v, depth + 1)) {
                return true;
            }
            if (v instanceof NBTTagList) {
                NBTTagList l = (NBTTagList) v;
                for (int i = 0; i < l.tagCount(); i++) {
                    if (l.get(i) instanceof NBTTagCompound && nbtHasContents((NBTTagCompound) l.get(i), depth + 1)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isContentKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        for (String c : CONTENT_KEYS) {
            if (k.equals(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean listHasItems(NBTTagList l) {
        for (int i = 0; i < l.tagCount(); i++) {
            NBTBase e = l.get(i);
            if (e instanceof NBTTagCompound) {
                NBTTagCompound c = (NBTTagCompound) e;
                boolean id = c.hasKey("id", 8) ? !c.getString("id").isEmpty() && !c.getString("id").equals("minecraft:air")
                        : c.hasKey("id", 99) && c.getShort("id") > 0;
                if (id && (!c.hasKey("Count") || c.getByte("Count") > 0)) {
                    return true;
                }
            }
        }
        return false;
    }
}
