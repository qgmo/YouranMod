package moe.qingu.youran.logic.reaction;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * 反应的输入物品：矿物词典名、具体物品（注册名 + 元数据）或调用方给的判断（CraftTweaker 的配料），加上每次消耗的数量。
 *
 * @param <S> 物品的类型
 */
public final class ItemIngredient<S> {
    /** 元数据写这个表示任意元数据（同 {@code OreDictionary.WILDCARD_VALUE}） */
    public static final int ANY_META = 32767;

    private final String ore;
    private final String itemId;
    private final int meta;
    private final Predicate<S> custom;
    private final int count;
    private final String description;

    private ItemIngredient(String ore, String itemId, int meta, Predicate<S> custom, int count, String description) {
        if (count <= 0) {
            throw new IllegalArgumentException("输入物品的数量要大于 0：" + count);
        }
        this.ore = ore;
        this.itemId = itemId;
        this.meta = meta;
        this.custom = custom;
        this.count = count;
        this.description = description;
    }

    /** 矿物词典名下的任意物品 */
    public static <S> ItemIngredient<S> ore(String oreName, int count) {
        if (oreName == null || oreName.isEmpty()) {
            throw new IllegalArgumentException("矿物词典名不能为空");
        }
        return new ItemIngredient<>(oreName, null, 0, null, count, "<ore:" + oreName + "> * " + count);
    }

    /** 具体物品；{@code meta} 为 {@link #ANY_META} 时不看元数据 */
    public static <S> ItemIngredient<S> item(String itemId, int meta, int count) {
        if (itemId == null || itemId.isEmpty()) {
            throw new IllegalArgumentException("物品注册名不能为空");
        }
        return new ItemIngredient<>(null, itemId, meta, null, count, "<" + itemId + ":" + (meta == ANY_META ? "*" : meta) + "> * " + count);
    }

    /** 调用方给的判断（只判断是不是这种物品，不看数量）；description 照原样用作说明（应已带上数量） */
    public static <S> ItemIngredient<S> custom(Predicate<S> test, int count, String description) {
        Objects.requireNonNull(test, "test");
        return new ItemIngredient<>(null, null, 0, test, count, description);
    }

    /** 是不是这种物品（不看数量） */
    public boolean matchesType(S stack, StackFacts<S> facts) {
        if (stack == null || facts.isEmpty(stack)) {
            return false;
        }
        if (ore != null) {
            return facts.hasOre(stack, ore);
        }
        if (itemId != null) {
            return itemId.equals(facts.itemId(stack)) && (meta == ANY_META || meta == facts.meta(stack));
        }
        return custom.test(stack);
    }

    /** 是这种物品，且数量够一次 */
    public boolean matches(S stack, StackFacts<S> facts) {
        return matchesType(stack, facts) && facts.count(stack) >= count;
    }

    /** 每次消耗的数量 */
    public int count() {
        return count;
    }

    /** 矿物词典名；不是矿物词典配料时为 null */
    public String oreName() {
        return ore;
    }

    @Override
    public String toString() {
        return description;
    }
}
