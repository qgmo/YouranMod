package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.BlueprintCraftingRecipe;
import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.item.LampNbt;
import moe.qingu.youran.logic.litho.LampGas;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.recipe.LithoRecipes;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 汞灯组装（服主 11-16）：沉浸工程工程师装配台，蓝图“汞灯”。玻璃管 + 1 掺钍阴极棒 + 1 钨棒（阳极）+ 1 氩或氙
 * + 2 中压接线器（或 2 根 IC2 二层绝缘金质导线）+ 1 小撮钼粉 → 汞灯（还没充汞）。
 *
 * <p>装配台的配方产物是写死的，而且一次能做好几个；这里由 Mixin（{@code mixin.immersiveengineering.InventoryBlueprintMixin} 手动装配台、
 * {@code MultiblockProcessOutputMixin} 自动装配台）把产物换成一次一个、带上这次用的那根玻璃管的数据（种类、纯度、铸模）与气体。
 * 两种玻璃管 × 两种气体 × 两种接线各登记一条；JEI 里显示的产物是不带玻璃管数据的样子。
 */
public final class LampAssembly {
    static final ResourceLocation CONNECTOR = new ResourceLocation("immersiveengineering", "connector");
    /** 中压接线器（{@code BlockTypes_Connector.CONNECTOR_MV}） */
    static final int CONNECTOR_MV = 2;
    public static final int CONNECTORS = 2;

    private LampAssembly() {
    }

    /** 两个 Mixin 生效了没有（手动装配台的会给 {@code InventoryBlueprint} 加上 {@link LampAssemblyPatched}） */
    public static boolean active() {
        try {
            return LampAssemblyPatched.class.isAssignableFrom(Class.forName("blusunrize.immersiveengineering.common.gui.InventoryBlueprint"))
                    && LampAssemblyPatched.class.isAssignableFrom(
                    Class.forName("blusunrize.immersiveengineering.common.blocks.metal.TileEntityMultiblockMetal$MultiblockProcess"));
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public static void register() {
        if (!active()) {
            YouranMod.LOG.warn("沉浸工程装配台的 Mixin 没有生效，不加汞灯蓝图配方（不然做出来的灯没有玻璃管的数据）");
            return;
        }
        Item connector = ForgeRegistries.ITEMS.getValue(CONNECTOR);
        if (connector == null) {
            return;
        }
        // 接线器那一格两种都认（服主 11-16）：2 个沉浸工程中压接线器，或 2 根 IC2 二层绝缘金质导线
        java.util.List<IngredientStack> wires = new java.util.ArrayList<>();
        wires.add(new IngredientStack(new ItemStack(connector, CONNECTORS, CONNECTOR_MV)));
        ItemStack gold = ic2GoldCable();
        if (!gold.isEmpty()) {
            wires.add(new IngredientStack(gold).setUseNBT(true));
        }
        int added = 0;
        for (LampGas gas : LampGas.values()) {
            ItemStack element = AlchemistryCompat.chemical(gas.id, 1);
            if (element.isEmpty()) {
                YouranMod.LOG.info("炼金化学里没有 {}，不加用它的汞灯蓝图配方", gas.id);
                continue;
            }
            for (Item tube : new Item[]{ModItems.glassTube, ModItems.quartzTube}) {
                for (IngredientStack wire : wires) {
                    ItemStack shown = new ItemStack(ModItems.mercuryLamp);
                    shown.setTagCompound(LampNbt.tag(LampNbt.tubeOf(tube == ModItems.quartzTube ? ModItems.quartzTube.make(null, 1).getTagCompound()
                            : ModItems.glassTube.make(null, 1).getTagCompound()).assembled(gas)));
                    BlueprintCraftingRecipe.addRecipe(LithoRecipes.LAMP_BLUEPRINT, shown, new IngredientStack(new ItemStack(tube)),
                            new ItemStack(ModItems.thoriatedCathodeRod), new ItemStack(ModItems.tungstenRod), element, wire,
                            new IngredientStack("dustTinyMolybdenum", 1));
                    added++;
                }
            }
        }
        YouranMod.LOG.info("已加沉浸工程装配台蓝图“{}”：{} 条", LithoRecipes.LAMP_BLUEPRINT, added);
    }

    /**
     * IC2 二层绝缘金质导线：IC2 exp 2.8 的导线是一个物品 {@code ic2:cable}（元数据 0），种类与绝缘层数记在 NBT：
     * {@code {type:2b, insulation:2b}}（{@code CableType.gold} 的序号是 2）。数量 {@value #CONNECTORS}，按 NBT 比，只认这一种。
     */
    static ItemStack ic2GoldCable() {
        Item cable = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ic2", "cable"));
        if (cable == null) {
            return ItemStack.EMPTY;
        }
        ItemStack s = new ItemStack(cable, CONNECTORS, 0);
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        tag.setByte("type", (byte) 2);
        tag.setByte("insulation", (byte) 2);
        s.setTagCompound(tag);
        return s;
    }

    public static boolean isLampRecipe(@Nullable Object recipe) {
        return recipe instanceof BlueprintCraftingRecipe && LithoRecipes.LAMP_BLUEPRINT.equals(((BlueprintCraftingRecipe) recipe).blueprintCategory);
    }

    /** 这条配方、这些输入做出来的那一个灯；输入里找不到玻璃管时照配方原样（一个） */
    public static ItemStack output(BlueprintCraftingRecipe recipe, List<ItemStack> inputs) {
        Item tubeItem = recipe.inputs.length > 0 && !recipe.inputs[0].stack.isEmpty() ? recipe.inputs[0].stack.getItem() : null;
        LampSpec shown = LampNbt.read(recipe.output.getTagCompound());
        ItemStack out = new ItemStack(ModItems.mercuryLamp);
        for (ItemStack s : inputs) {
            if (!s.isEmpty() && s.getItem() == tubeItem) {
                out.setTagCompound(LampNbt.tag(LampNbt.tubeOf(s.getTagCompound()).assembled(shown.gas)));
                return out;
            }
        }
        out.setTagCompound(recipe.output.hasTagCompound() ? recipe.output.getTagCompound().copy() : null);
        return out;
    }
}
