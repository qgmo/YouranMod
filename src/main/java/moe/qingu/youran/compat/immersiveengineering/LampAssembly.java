package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.BlueprintCraftingRecipe;
import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.compat.alchemistry.AlchemistryCompat;
import moe.qingu.youran.item.LampNbt;
import moe.qingu.youran.item.LaserNbt;
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
 * “光源”蓝图（服主 11-16，原“汞灯”蓝图改名）上的三样，沉浸工程工程师装配台：
 * <ul>
 *     <li>汞灯：玻璃管 + 1 掺钍阴极棒 + 1 钨棒（阳极）+ 1 氩或氙 + 2 中压接线器（或 2 根 IC2 二层绝缘金质导线）+ 1 小撮钼粉 → 汞灯（还没充汞）；</li>
 *     <li>ArF 激光腔：2 钢板 + 1 石英管（只认石英，纯度记进数据）+ 2 氟化钙（炼金化学化合物）+ 2 镍板 + 2 沉浸工程高压接线器
 *     （或 2 根 IC2 玻璃导线），第 6 格空 → 空激光腔；</li>
 *     <li>氦氖激光管：1 玻璃管（低档、石英都认）+ 1 钨棒 + 1 铝板 + 2 玻璃板 + 2 二氧化硅粉 + 2 根 IC2 二层绝缘金质导线
 *     （或 2 个沉浸工程中压接线器）→ 空氦氖激光管。</li>
 * </ul>
 * 通用材料（钢板、镍板、铝板、玻璃板、二氧化硅粉）按矿物词典。
 *
 * <p>装配台的配方产物是写死的，而且一次能做好几个；这里由 Mixin（{@code mixin.immersiveengineering.InventoryBlueprintMixin} 手动装配台、
 * {@code MultiblockProcessOutputMixin} 自动装配台）把产物换成一次一个、带上这次用的那根玻璃管的数据（种类、纯度、铸模）与气体。
 * 两种玻璃管 × 两种气体 × 两种接线各登记一条；JEI 里显示的产物是不带玻璃管数据的样子。
 */
public final class LampAssembly {
    static final ResourceLocation CONNECTOR = new ResourceLocation("immersiveengineering", "connector");
    /** 中压、高压接线器（{@code BlockTypes_Connector.CONNECTOR_MV}、{@code CONNECTOR_HV}） */
    static final int CONNECTOR_MV = 2;
    static final int CONNECTOR_HV = 4;
    public static final int CONNECTORS = 2;
    /** IC2 导线的种类序号（{@code CableType}：copper 0、glass 1、gold 2），物品元数据也是它 */
    static final int CABLE_GLASS = 1;
    static final int CABLE_GOLD = 2;
    /** 氟化钙（炼金化学化合物，由正胶那一轮注册：1 钙 + 2 氟）；名字以那边为准 */
    public static final String CALCIUM_FLUORIDE = "calcium_fluoride";

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
        added += registerLasers(connector, wires);
        YouranMod.LOG.info("已加沉浸工程装配台蓝图“{}”：{} 条", LithoRecipes.LAMP_BLUEPRINT, added);
    }

    /** ArF 激光腔、氦氖激光管（服主 11-16）；返回加了几条 */
    static int registerLasers(Item connector, List<IngredientStack> mvWires) {
        int added = 0;
        // ArF 激光腔：接线两种都认——2 个高压接线器或 2 根 IC2 玻璃导线
        ItemStack caf2 = AlchemistryCompat.stack(CALCIUM_FLUORIDE, 2);
        if (caf2.isEmpty()) {
            YouranMod.LOG.warn("炼金化学里没有化合物 {}（氟化钙，正胶那一轮注册），不加 ArF 激光腔的蓝图配方", CALCIUM_FLUORIDE);
        } else {
            List<IngredientStack> hvWires = new java.util.ArrayList<>();
            hvWires.add(new IngredientStack(new ItemStack(connector, CONNECTORS, CONNECTOR_HV)));
            ItemStack glass = ic2Cable(CABLE_GLASS, 0);
            if (!glass.isEmpty()) {
                hvWires.add(new IngredientStack(glass).setUseNBT(true));
            }
            ItemStack shown = ModItems.arfLaserCavity.make(LaserNbt.cavityOf(ModItems.quartzTube.make(null, 1).getTagCompound()));
            for (IngredientStack wire : hvWires) {
                BlueprintCraftingRecipe.addRecipe(LithoRecipes.LAMP_BLUEPRINT, shown, new IngredientStack("plateSteel", 2),
                        new IngredientStack(new ItemStack(ModItems.quartzTube)), new IngredientStack(caf2.copy()), new IngredientStack("plateNickel", 2), wire);
                added++;
            }
        }
        // 氦氖激光管：玻璃管两种都认；接线两种都认
        for (Item tube : new Item[]{ModItems.glassTube, ModItems.quartzTube}) {
            ItemStack shown = ModItems.heneLaserTube.make(LaserNbt.heneOf(tube == ModItems.quartzTube ? ModItems.quartzTube.make(null, 1).getTagCompound()
                    : ModItems.glassTube.make(null, 1).getTagCompound()));
            for (IngredientStack wire : mvWires) {
                BlueprintCraftingRecipe.addRecipe(LithoRecipes.LAMP_BLUEPRINT, shown, new IngredientStack(new ItemStack(tube)),
                        new ItemStack(ModItems.tungstenRod), new IngredientStack("plateAluminum", 1), new IngredientStack("paneGlass", 2),
                        new IngredientStack("dustSiliconDioxide", 2), wire);
                added++;
            }
        }
        return added;
    }

    /** IC2 二层绝缘金质导线（{@code {type:2b, insulation:2b}}），数量 {@value #CONNECTORS}，按 NBT 比，只认这一种 */
    static ItemStack ic2GoldCable() {
        return ic2Cable(CABLE_GOLD, 2);
    }

    /**
     * IC2 导线：IC2 exp 2.8 的导线是一个物品 {@code ic2:cable}，元数据是种类的序号（{@code ItemCable.getCable}：
     * {@code new ItemStack(cable, 1, type.getId())}，{@code getId()} 就是序号），种类与绝缘层数另记在 NBT {@code {type, insulation}}。
     * 数量 {@value #CONNECTORS}；没装 IC2 时为空物品
     */
    static ItemStack ic2Cable(int type, int insulation) {
        Item cable = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ic2", "cable"));
        if (cable == null) {
            return ItemStack.EMPTY;
        }
        ItemStack s = new ItemStack(cable, CONNECTORS, type);
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        tag.setByte("type", (byte) type);
        tag.setByte("insulation", (byte) insulation);
        s.setTagCompound(tag);
        return s;
    }

    public static boolean isLampRecipe(@Nullable Object recipe) {
        return recipe instanceof BlueprintCraftingRecipe && LithoRecipes.LAMP_BLUEPRINT.equals(((BlueprintCraftingRecipe) recipe).blueprintCategory);
    }

    /** 这条配方、这些输入做出来的那一个（汞灯、激光腔、氦氖激光管）；输入里找不到玻璃管时照配方原样（一个） */
    public static ItemStack output(BlueprintCraftingRecipe recipe, List<ItemStack> inputs) {
        Item made = recipe.output.getItem();
        if (made == ModItems.arfLaserCavity || made == ModItems.heneLaserTube) {
            for (ItemStack s : inputs) {
                if (!s.isEmpty() && (s.getItem() == ModItems.quartzTube || (made == ModItems.heneLaserTube && s.getItem() == ModItems.glassTube))) {
                    return made == ModItems.arfLaserCavity ? ModItems.arfLaserCavity.make(LaserNbt.cavityOf(s.getTagCompound()))
                            : ModItems.heneLaserTube.make(LaserNbt.heneOf(s.getTagCompound()));
                }
            }
            return recipe.output.copy();
        }
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
