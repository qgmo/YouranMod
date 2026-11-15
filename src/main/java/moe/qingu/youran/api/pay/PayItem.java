package moe.qingu.youran.api.pay;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * pay.yr 里“一种物品”：物品 ID + 数据值 + NBT（服主 11-15：完全一致才算同一种）。NBT 用规范写法（{@link NbtCanon}）存，
 * 物品带 Forge 能力数据（{@code ForgeCaps}）且和新做出来的同样物品不一样时也记下来，同样要一致。不可变，可以当表的键。
 * <p>
 * 绿宝石和悠然元（{@link Currency}）也是 {@code PayItem}，但在 {@link Amount} 里按价值合并记，不单独当一种物品。
 */
public final class PayItem implements Comparable<PayItem> {
    /** 物品 ID 的写法 */
    public static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    public static final int MAX_NBT = 8192;

    /** 物品 ID（如 {@code minecraft:diamond}） */
    public final String id;
    /** 数据值（工具是损耗） */
    public final int meta;
    /** NBT（规范写法）；没有为 null */
    public final String nbt;
    /** 与新物品不同的 Forge 能力数据（规范写法）；没有为 null */
    public final String caps;

    public PayItem(String id, int meta, String nbt, String caps) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("物品 ID 不对：" + id);
        }
        if (meta < 0 || meta > Short.MAX_VALUE) {
            throw new IllegalArgumentException("数据值不对：" + meta);
        }
        if ((nbt != null && nbt.length() > MAX_NBT) || (caps != null && caps.length() > MAX_NBT)) {
            throw new IllegalArgumentException("NBT 太长");
        }
        this.id = id;
        this.meta = meta;
        this.nbt = nbt == null || nbt.equals("{}") ? null : nbt;
        this.caps = caps == null || caps.equals("{}") ? null : caps;
    }

    public static PayItem of(String id, int meta) {
        return new PayItem(id, meta, null, null);
    }

    /**
     * 按物品堆认出是哪一种（数量不管）。空的返回 null。NBT 写法读不回原样（如 NaN）时抛 {@link IllegalArgumentException}。
     * 要在服务端主线程上调用（会做一个新物品来比较能力数据）。
     */
    public static PayItem of(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem().getRegistryName() == null) {
            return null;
        }
        NBTTagCompound tag = stack.getTagCompound();
        String nbt = tag == null || tag.isEmpty() ? null : NbtCanon.write(tag);
        if (nbt != null && !NbtCanon.roundTrips(nbt)) {
            throw new IllegalArgumentException("这个物品的 NBT 记不了账（有读不回原样的值）");
        }
        String caps = capsOf(stack);
        if (caps != null) {
            // 和新做出来的同样物品一样（默认的能力数据）就不记
            ItemStack fresh = new ItemStack(stack.getItem(), 1, stack.getMetadata());
            if (tag != null) {
                fresh.setTagCompound(tag.copy());
            }
            if (caps.equals(capsOf(fresh))) {
                caps = null;
            } else if (!NbtCanon.roundTrips(caps)) {
                throw new IllegalArgumentException("这个物品的能力数据记不了账");
            }
        }
        return new PayItem(stack.getItem().getRegistryName().toString(), stack.getMetadata(), nbt, caps);
    }

    private static String capsOf(ItemStack stack) {
        NBTTagCompound all = stack.writeToNBT(new NBTTagCompound());
        if (!all.hasKey("ForgeCaps", 10)) {
            return null;
        }
        NBTTagCompound c = all.getCompoundTag("ForgeCaps");
        return c.isEmpty() ? null : NbtCanon.write(c);
    }

    /** 做出 {@code count} 个这种物品（不管最大堆叠数）；物品不存在（模组没了）时返回空物品 */
    public ItemStack toStack(int count) {
        Item item = Item.REGISTRY.getObject(new ResourceLocation(id));
        if (item == null) {
            return ItemStack.EMPTY;
        }
        NBTTagCompound c = new NBTTagCompound();
        c.setString("id", id);
        c.setByte("Count", (byte) 1);
        c.setShort("Damage", (short) meta);
        if (nbt != null) {
            c.setTag("tag", NbtCanon.parse(nbt));
        }
        if (caps != null) {
            c.setTag("ForgeCaps", NbtCanon.parse(caps));
        }
        ItemStack s = new ItemStack(c);
        if (!s.isEmpty()) {
            s.setCount(count);
        }
        return s;
    }

    /** 物品在不在（模组还装着） */
    public boolean exists() {
        return Item.REGISTRY.getObject(new ResourceLocation(id)) != null;
    }

    /** 是不是绿宝石或悠然元 */
    public boolean isCurrency() {
        return Currency.Denomination.of(this) != null;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PayItem)) {
            return false;
        }
        PayItem p = (PayItem) o;
        return id.equals(p.id) && meta == p.meta && Objects.equals(nbt, p.nbt) && Objects.equals(caps, p.caps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, meta, nbt, caps);
    }

    @Override
    public int compareTo(PayItem o) {
        int c = id.compareTo(o.id);
        if (c == 0) {
            c = Integer.compare(meta, o.meta);
        }
        if (c == 0) {
            c = compare(nbt, o.nbt);
        }
        if (c == 0) {
            c = compare(caps, o.caps);
        }
        return c;
    }

    private static int compare(String a, String b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        return b == null ? 1 : a.compareTo(b);
    }

    /** 给人看的写法：{@code minecraft:wool@14{...}} */
    @Override
    public String toString() {
        return id + (meta == 0 ? "" : "@" + meta) + (nbt == null ? "" : nbt) + (caps == null ? "" : " caps" + caps);
    }
}
