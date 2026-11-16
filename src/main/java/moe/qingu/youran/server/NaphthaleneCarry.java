package moe.qingu.youran.server;

import moe.qingu.youran.logic.resist.NaphthaleneToxicity;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.NonNullList;

/**
 * 身上带的萘太多会反胃（服主 11-16）：物品栏（含快捷栏、副手；盔甲栏不会有）里所有萘加起来超过 32 个，
 * 每秒给一次反胃，时长与等级见 {@link NaphthaleneToxicity}。创造、旁观模式不管。照 {@link ToxicCarry} 的写法，每秒查一次。
 */
public final class NaphthaleneCarry {
    private NaphthaleneCarry() {
    }

    /** 每秒调用一次 */
    static void check(EntityPlayerMP player) {
        if (player.isSpectator() || player.capabilities.isCreativeMode) {
            return;
        }
        int total = count(player.inventory.mainInventory) + count(player.inventory.offHandInventory);
        if (NaphthaleneToxicity.poisons(total)) {
            player.addPotionEffect(new PotionEffect(MobEffects.NAUSEA, NaphthaleneToxicity.seconds(total) * 20,
                    NaphthaleneToxicity.amplifier(total)));
        }
    }

    private static int count(NonNullList<ItemStack> slots) {
        int n = 0;
        for (ItemStack s : slots) {
            if (!s.isEmpty() && s.getItem() == ModItems.naphthalene) {
                n += s.getCount();
            }
        }
        return n;
    }
}
