package moe.qingu.youran.logic.reaction;

/**
 * 反应配方表看物品的方式：配方表本身不依赖 Minecraft，由调用方把真实的物品（{@code ItemStack}）翻译成这几样。
 *
 * @param <S> 物品的类型（游戏里是 {@code ItemStack}，单元测试里是假物品）
 */
public interface StackFacts<S> {
    /** 空物品（{@code ItemStack.EMPTY} 或数量 ≤ 0） */
    boolean isEmpty(S stack);

    /** 数量 */
    int count(S stack);

    /** 注册名，如 {@code minecraft:iron_ingot} */
    String itemId(S stack);

    /** 元数据（损伤值） */
    int meta(S stack);

    /** 在不在这个矿物词典名下 */
    boolean hasOre(S stack, String oreName);
}
