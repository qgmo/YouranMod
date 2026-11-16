package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import java.util.List;

/** 编译用替身（见 package-info）：发货回调 */
public interface MineBayDelivery {
    List<ItemStack> prepare(EntityPlayerMP buyer, int quantity);

    default void delivered(EntityPlayerMP buyer, int quantity, List<ItemStack> stacks) {
    }
}
