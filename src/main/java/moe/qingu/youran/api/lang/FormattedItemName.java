package moe.qingu.youran.api.lang;

import net.minecraft.item.ItemStack;

/**
 * 名字带参数的物品（如“Z80 掩模”= {@code item.photomask.name}「%s 掩模」填上型号名）：给 {@link ServerLang#findItemName} 用，
 * 服务端按各语言的翻译拼出名字（不然只能拿到没填参数的格式串）。物品类实现它，和自己的 {@code getItemStackDisplayName} 一致。
 */
public interface FormattedItemName {
    /** 名字的翻译键（格式串，带 {@code %s}） */
    String nameKey(ItemStack stack);

    /** 依次填进格式串的参数，每个也是翻译键（查不到时用键名） */
    String[] nameArgKeys(ItemStack stack);
}
