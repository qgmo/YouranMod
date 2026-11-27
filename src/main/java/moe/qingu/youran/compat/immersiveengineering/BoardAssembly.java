package moe.qingu.youran.compat.immersiveengineering;

import blusunrize.immersiveengineering.api.crafting.BlueprintCraftingRecipe;
import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import moe.qingu.youran.YouranMod;
import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.item.ItemEprom;
import moe.qingu.youran.item.ItemModelled;
import moe.qingu.youran.item.ItemMotherboard;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import moe.qingu.youran.logic.pcb.PcbStage;
import moe.qingu.youran.registry.ModItems;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 装芯片（服主 11-16）：工程师装配台的两张新蓝图。
 * <ul>
 *     <li>“主板”（{@value #MOTHERBOARD}）：打好孔的普通电脑 / 个人电脑光板（或装了一半的主板）+ 芯片 → 主板。装配台只有 6 个输入格、
 *     芯片一片占一格，所以分几次装，每条配方装一种：CPU（Z80 / 8088）、ROM（掩模 ROM 或 EPROM，烧错的、空白的也能装）、
 *     内存（4164，一次 1–5 颗）。装齐 = 普通电脑 Z80 + 6 颗 4164 + 监控程序 ROM，个人电脑 8088 + 20 颗 4164 + BIOS ROM；
 *     家具电脑主板（服主 11-16）= 打好孔的个人电脑光板 + 1 片 YR-2000，不插 ROM、内存，一次装齐；</li>
 *     <li>“扩展卡”（{@value #EXPANSION}）：打好孔的汉卡光板 + 字库 ROM 1–4 → 汉卡（一次装齐）；打好孔的 ISA 扩展卡光板 + 网卡控制芯片 →
 *     个人电脑网卡（2.3.1.0 第 1 期）。以后别的扩展卡也加在这张蓝图上：打好孔的那种光板 + 它的 ROM 等芯片 → 那种卡。</li>
 * </ul>
 * 一次一块，芯片的品质数据都记进产物（{@link BoardNbt}），由 {@link BlueprintOutputs} 现算。
 */
public final class BoardAssembly {
    public static final String MOTHERBOARD = BoardAssemblyNames.MOTHERBOARD;
    public static final String EXPANSION = BoardAssemblyNames.EXPANSION;
    /** 一次最多装几颗内存（6 个输入格：1 格板子 + 5 颗） */
    static final int MAX_RAM_PER_STEP = 5;

    private BoardAssembly() {
    }

    public static boolean isBoardRecipe(@Nullable Object recipe) {
        return recipe instanceof BlueprintCraftingRecipe
                && (MOTHERBOARD.equals(((BlueprintCraftingRecipe) recipe).blueprintCategory) || EXPANSION.equals(((BlueprintCraftingRecipe) recipe).blueprintCategory));
    }

    public static void register() {
        if (!LampAssembly.active()) {
            YouranMod.LOG.warn("沉浸工程装配台的 Mixin 没有生效，不加主板、扩展卡蓝图配方（不然芯片的数据带不进去）");
            return;
        }
        int n = 0;
        for (BoardType t : new BoardType[]{BoardType.COMPUTER, BoardType.PC}) {
            IngredientStack base = new IngredientStack(Arrays.asList(new ItemStack(ModItems.pcb, 1, PcbStage.punched(t)),
                    new ItemStack(ModItems.motherboard, 1, t.id)), 1);
            ItemStack shown = ItemMotherboard.make(ModItems.motherboard, t, BoardNbt.empty(t));
            BlueprintCraftingRecipe.addRecipe(MOTHERBOARD, shown, base, ModItems.chip.make(BoardNbt.cpu(t)));
            BlueprintCraftingRecipe.addRecipe(MOTHERBOARD, shown, base, ModItems.chip.make(BoardNbt.romMask(t)));
            BlueprintCraftingRecipe.addRecipe(MOTHERBOARD, shown, base, new ItemStack(ModItems.eprom));
            n += 3;
            if (t == BoardType.PC) {
                // 光刻机对准台的主板（服主 11-16）：个人电脑主板，ROM 换成激光对准控制程序（掩模 ROM；EPROM 烧的走上面那条）
                BlueprintCraftingRecipe.addRecipe(MOTHERBOARD, shown, base, ModItems.chip.make(ChipModel.ROM_LASER_ALIGN));
                n++;
            }
            for (int k = 1; k <= Math.min(MAX_RAM_PER_STEP, BoardNbt.ramCount(t)); k++) {
                ItemStack ram = ModItems.chip.make(ChipModel.DRAM_4164);
                ram.setCount(k);
                BlueprintCraftingRecipe.addRecipe(MOTHERBOARD, shown, base, ram);
                n++;
            }
        }
        // 家具电脑主板（服主 11-16）：打好孔的个人电脑光板 + 1 片 YR-2000
        BlueprintCraftingRecipe.addRecipe(MOTHERBOARD, ItemMotherboard.make(ModItems.motherboard, BoardType.FURNITURE, BoardNbt.empty(BoardType.FURNITURE)),
                new ItemStack(ModItems.pcb, 1, PcbStage.punched(BoardType.FURNITURE.blank())), ModItems.chip.make(BoardNbt.cpu(BoardType.FURNITURE)));
        n++;
        List<Object> hanzi = new ArrayList<>();
        hanzi.add(new ItemStack(ModItems.pcb, 1, PcbStage.punched(BoardType.HANZI)));
        for (ChipModel m : BoardNbt.fonts(BoardType.HANZI)) {
            hanzi.add(ModItems.chip.make(m));
        }
        ItemStack card = new ItemStack(ModItems.hanziCard);
        BlueprintCraftingRecipe.addRecipe(EXPANSION, card, hanzi.toArray());
        n++;
        // 个人电脑网卡（2.3.1.0 第 1 期）：打好孔的 ISA 扩展卡光板 + 网卡控制芯片
        BlueprintCraftingRecipe.addRecipe(EXPANSION, ModItems.nicPc.make(BoardNbt.empty(BoardType.ISA)),
                new ItemStack(ModItems.pcb, 1, PcbStage.punched(BoardType.ISA)), ModItems.chip.make(ChipModel.NIC_CTRL));
        n++;
        YouranMod.LOG.info("已加沉浸工程装配台蓝图“主板”“扩展卡”：{} 条", n);
    }

    /** 这一套输入装出来的东西；装不了时为空物品 */
    static ItemStack output(BlueprintCraftingRecipe recipe, List<ItemStack> consumed) {
        NBTTagCompound board = null;
        BoardType type = null;
        // 从打好孔的光板装起时：这块光板能装成的几种（个人电脑的光板：个人电脑主板或家具电脑主板，看装的是什么芯片）
        List<BoardType> candidates = new ArrayList<>();
        List<BoardNbt.Part> parts = new ArrayList<>();
        for (ItemStack s : consumed) {
            if (s.isEmpty()) {
                continue;
            }
            if (s.getItem() == ModItems.pcb) {
                type = PcbStage.type(s.getMetadata());
                board = type == null ? null : BoardNbt.empty(type);
                if (type != null) {
                    candidates.addAll(BoardType.builtOn(type));
                }
            } else if (s.getItem() == ModItems.motherboard) {
                type = ItemMotherboard.type(s);
                board = ItemMotherboard.board(s);
            } else if (s.getItem() == ModItems.chip) {
                ChipModel m = ItemModelled.model(s);
                if (m == null) {
                    return ItemStack.EMPTY;
                }
                for (int i = 0; i < s.getCount(); i++) {
                    parts.add(new BoardNbt.Part(m, s.hasTagCompound() ? s.getTagCompound() : new NBTTagCompound()));
                }
            } else if (ItemEprom.is(s)) {
                NBTTagCompound tag = s.hasTagCompound() ? s.getTagCompound() : new NBTTagCompound();
                parts.add(new BoardNbt.Part(ChipModel.EPROM_2764, ItemEprom.chip(s), true, ItemEprom.content(s), tag.getCompoundTag(ItemEprom.TAG_WINDOW)));
            }
        }
        if (board == null || type == null) {
            return ItemStack.EMPTY;
        }
        NBTTagCompound done = BoardNbt.install(board, parts);
        for (int i = 0; done == null && i < candidates.size(); i++) {
            type = candidates.get(i);
            done = BoardNbt.install(BoardNbt.empty(type), parts);
        }
        if (done == null) {
            return ItemStack.EMPTY;
        }
        if (type == BoardType.ISA) {
            // 装哪种控制芯片就是哪种卡（现在只有网卡）
            return BoardNbt.controllerModel(done) == ChipModel.NIC_CTRL ? ModItems.nicPc.make(done) : ItemStack.EMPTY;
        }
        if (type == BoardType.HANZI) {
            ItemStack card = new ItemStack(ModItems.hanziCard);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setTag(BoardNbt.TAG, done);
            card.setTagCompound(tag);
            return card;
        }
        return ItemMotherboard.make(ModItems.motherboard, type, done);
    }
}
