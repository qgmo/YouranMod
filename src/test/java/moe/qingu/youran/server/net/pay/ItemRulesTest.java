package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.PayItem;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemRulesTest {
    static NBTTagCompound item(String id, int count) {
        NBTTagCompound c = new NBTTagCompound();
        c.setString("id", id);
        c.setByte("Count", (byte) count);
        return c;
    }

    @Test
    void blacklist() {
        String[] list = {"minecraft:written_book", "minecraft:wool@14", "ic2:*", "  ", "# 注释"};
        assertTrue(ItemRules.blacklisted(PayItem.of("minecraft:written_book", 0), list));
        assertTrue(ItemRules.blacklisted(PayItem.of("minecraft:wool", 14), list));
        assertFalse(ItemRules.blacklisted(PayItem.of("minecraft:wool", 13), list));
        assertTrue(ItemRules.blacklisted(PayItem.of("ic2:te", 3), list));
        assertFalse(ItemRules.blacklisted(PayItem.of("ic2x:te", 3), list));
        assertFalse(ItemRules.blacklisted(PayItem.of("minecraft:diamond", 0), list));
        assertFalse(ItemRules.blacklisted(PayItem.of("minecraft:diamond", 0), null));
    }

    /** 有内容的潜影盒（BlockEntityTag.Items）、背包类常见的列表算装着东西；空的不算 */
    @Test
    void containers() {
        NBTTagCompound shulker = new NBTTagCompound();
        NBTTagCompound bet = new NBTTagCompound();
        NBTTagList items = new NBTTagList();
        items.appendTag(item("minecraft:dirt", 3));
        bet.setTag("Items", items);
        shulker.setTag("BlockEntityTag", bet);
        assertTrue(ItemRules.nbtHasContents(shulker, 0));

        NBTTagCompound empty = new NBTTagCompound();
        NBTTagCompound bet2 = new NBTTagCompound();
        bet2.setTag("Items", new NBTTagList());
        empty.setTag("BlockEntityTag", bet2);
        assertFalse(ItemRules.nbtHasContents(empty, 0));

        NBTTagCompound backpack = new NBTTagCompound();
        NBTTagList inv = new NBTTagList();
        inv.appendTag(item("minecraft:air", 0));
        backpack.setTag("Inventory", inv);
        assertFalse(ItemRules.nbtHasContents(backpack, 0), "只有空格子");
        inv.appendTag(item("minecraft:stone", 1));
        assertTrue(ItemRules.nbtHasContents(backpack, 0));

        NBTTagCompound numeric = new NBTTagCompound();
        NBTTagList old = new NBTTagList();
        NBTTagCompound o = new NBTTagCompound();
        o.setShort("id", (short) 4);
        o.setByte("Count", (byte) 2);
        old.appendTag(o);
        numeric.setTag("contents", old);
        assertTrue(ItemRules.nbtHasContents(numeric, 0), "老写法的数字 ID");

        NBTTagCompound book = new NBTTagCompound();
        NBTTagList pages = new NBTTagList();
        pages.appendTag(new net.minecraft.nbt.NBTTagString("第一页"));
        book.setTag("pages", pages);
        assertFalse(ItemRules.nbtHasContents(book, 0), "书页不是物品");
    }
}
