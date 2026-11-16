package moe.qingu.youran.api.lang;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityList;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBanner;
import net.minecraft.item.ItemEnchantedBook;
import net.minecraft.item.ItemLingeringPotion;
import net.minecraft.item.ItemMonsterPlacer;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemShield;
import net.minecraft.item.ItemSkull;
import net.minecraft.item.ItemSplashPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemTippedArrow;
import net.minecraft.item.ItemWrittenBook;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.PotionUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.Map;

/** {@link ServerLang#findItemName} 的实现：照 1.12.2 各物品的 {@code getItemStackDisplayName}，翻译换成服务端翻译表 */
final class ItemNames {
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private ItemNames() {
    }

    @Nullable
    static String find(String locale, ItemStack s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            String v = byRule(locale, s);
            if (v == null && locale.equals(ServerLang.EN_US)) {
                v = ownName(s);
            }
            return v;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    @Nullable
    private static String byRule(String locale, ItemStack s) {
        Item i = s.getItem();
        if (i instanceof ItemPotion || i instanceof ItemTippedArrow) {
            String prefix = i instanceof ItemSplashPotion ? "splash_potion.effect." : i instanceof ItemLingeringPotion ? "lingering_potion.effect."
                    : i instanceof ItemTippedArrow ? "tipped_arrow.effect." : "potion.effect.";
            return ServerLang.find(locale, PotionUtils.getPotionFromItem(s).getNamePrefixed(prefix));
        }
        if (i instanceof ItemEnchantedBook) {
            String book = ServerLang.find(locale, "item.enchantedBook.name");
            Map<Enchantment, Integer> e = EnchantmentHelper.getEnchantments(s);
            if (book == null || e.size() != 1) {
                return book;
            }
            Map.Entry<Enchantment, Integer> only = e.entrySet().iterator().next();
            String n = enchantment(locale, only.getKey(), only.getValue());
            if (n == null) {
                return book;
            }
            return chinese(locale) ? book + "（" + n + "）" : book + " (" + n + ")";
        }
        if (i instanceof ItemMonsterPlacer) {
            String base = ServerLang.find(locale, i.getTranslationKey() + ".name");
            ResourceLocation id = ItemMonsterPlacer.getNamedIdFrom(s);
            String tn = id == null ? null : EntityList.getTranslationName(id);
            String entity = tn == null ? null : ServerLang.find(locale, "entity." + tn + ".name");
            if (base == null || entity == null) {
                return base;
            }
            return base.trim() + " " + entity;
        }
        if (i instanceof ItemSkull && s.getMetadata() == 3 && s.hasTagCompound()) {
            String owner = skullOwner(s.getTagCompound());
            if (owner != null) {
                String f = ServerLang.find(locale, "item.skull.player.name");
                return f == null ? null : ServerLang.fill(f, owner);
            }
        }
        if (i instanceof ItemWrittenBook && s.hasTagCompound()) {
            String title = s.getTagCompound().getString("title");
            if (!title.isEmpty()) {
                return title;
            }
        }
        if (i instanceof FormattedItemName) {
            FormattedItemName f = (FormattedItemName) i;
            String pattern = ServerLang.find(locale, f.nameKey(s));
            if (pattern == null) {
                return null;
            }
            String[] keys = f.nameArgKeys(s);
            Object[] args = new Object[keys.length];
            for (int k = 0; k < keys.length; k++) {
                String v = ServerLang.find(locale, keys[k]);
                args[k] = v != null ? v : ServerLang.get(ServerLang.EN_US, keys[k]);
            }
            return ServerLang.fill(pattern, args);
        }
        if (i instanceof ItemBanner) {
            return ServerLang.find(locale, "item.banner." + ItemBanner.getBaseColor(s).getTranslationKey() + ".name");
        }
        if (i instanceof ItemShield) {
            return ServerLang.find(locale, s.getSubCompound("BlockEntityTag") != null
                    ? "item.shield." + ItemBanner.getBaseColor(s).getTranslationKey() + ".name" : "item.shield.name");
        }
        return ServerLang.find(locale, s.getTranslationKey() + ".name");
    }

    /** 附魔名带等级：同 {@code Enchantment#getTranslatedName}（只有 I 级的附魔不写等级），不带颜色 */
    @Nullable
    private static String enchantment(String locale, Enchantment e, int level) {
        String n = ServerLang.find(locale, e.getName());
        if (n == null) {
            return null;
        }
        if (level == 1 && e.getMaxLevel() == 1) {
            return n;
        }
        String l = ServerLang.find(locale, "enchantment.level." + level);
        if (l == null) {
            l = level >= 1 && level <= ROMAN.length ? ROMAN[level - 1] : String.valueOf(level);
        }
        return n + " " + l;
    }

    @Nullable
    private static String skullOwner(NBTTagCompound tag) {
        if (tag.hasKey("SkullOwner", Constants.NBT.TAG_STRING)) {
            String name = tag.getString("SkullOwner");
            return name.isEmpty() ? null : name;
        }
        if (tag.hasKey("SkullOwner", Constants.NBT.TAG_COMPOUND)) {
            NBTTagCompound o = tag.getCompoundTag("SkullOwner");
            if (o.hasKey("Name", Constants.NBT.TAG_STRING)) {
                return o.getString("Name");
            }
        }
        return null;
    }

    /** 物品自己在服务端算的名字（服务端英文；不含改过的名字）；只是翻译键时为 null */
    @Nullable
    private static String ownName(ItemStack s) {
        String own = s.getItem().getItemStackDisplayName(s);
        if (own == null || own.trim().isEmpty() || own.equals(fallbackKey(s))) {
            return null;
        }
        return own.trim();
    }

    static String fallbackKey(ItemStack s) {
        try {
            return s == null || s.isEmpty() ? "" : s.getTranslationKey() + ".name";
        } catch (RuntimeException e) {
            return String.valueOf(s.getItem().getRegistryName());
        }
    }

    private static boolean chinese(String locale) {
        return locale.startsWith("zh_");
    }
}
