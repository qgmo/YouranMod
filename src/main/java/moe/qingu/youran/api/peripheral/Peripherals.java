package moe.qingu.youran.api.peripheral;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 外设的查询（给电脑模组、YouranMod 的网络用）与按种类找物品的登记表。见包说明。
 */
public final class Peripherals {
    /** 汉卡：装上才能显示、输入汉字（GB2312）。YouranMod 注册（{@code youranmod:hanzi_card}） */
    public static final String KIND_HANZI = "hanzi";
    /** 5.25 英寸软驱（360KB）：个人电脑的 B:。YouranMod 注册（{@code youranmod:floppy_drive}） */
    public static final String KIND_FLOPPY = "floppy";
    /** 定位模块（位置外设：给网站、id.yr 签电脑的位置用；没有 I/O 端口，能力 {@link #CAP_LOCATION}）。名字先留着，物品另做 */
    public static final String KIND_LOCATOR = "locator";
    /** 能力：这台电脑能报出自己的位置（定位模块） */
    public static final String CAP_LOCATION = "location";

    private static final Map<String, Item> BY_KIND = new LinkedHashMap<>();

    private Peripherals() {
    }

    /** 登记一种外设物品（注册物品时；同一种重复登记时后来的为准） */
    public static void register(Item item) {
        if (!(item instanceof PeripheralItem)) {
            throw new IllegalArgumentException("只登记 PeripheralItem：" + item);
        }
        BY_KIND.put(((PeripheralItem) item).kind(), item);
    }

    /** 这种外设的一个物品（没登记时空）：电脑按出厂配置放外设时用 */
    public static ItemStack create(String kind) {
        Item item = BY_KIND.get(kind);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** 登记过的全部种类 */
    public static Set<String> knownKinds() {
        return Collections.unmodifiableSet(BY_KIND.keySet());
    }

    /** 物品是外设时返回它的外设接口，否则 null */
    @Nullable
    public static Peripheral of(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof Peripheral ? (Peripheral) stack.getItem() : null;
    }

    /** 物品的外设种类（不是外设时 null） */
    @Nullable
    public static String kind(ItemStack stack) {
        Peripheral p = of(stack);
        return p == null ? null : p.peripheralKind(stack);
    }

    /** 一组物品里外设的种类（排好、去重；不是外设的不算） */
    public static Set<String> kinds(Iterable<ItemStack> stacks) {
        Set<String> out = new TreeSet<>();
        for (ItemStack s : stacks) {
            String k = kind(s);
            if (k != null) {
                out.add(k);
            }
        }
        return out;
    }

    /** 一组物品里的外设有没有能力 cap */
    public static boolean hasCapability(Iterable<ItemStack> stacks, String cap) {
        for (ItemStack s : stacks) {
            Peripheral p = of(s);
            if (p != null && p.peripheralCapabilities(s).contains(cap)) {
                return true;
            }
        }
        return false;
    }

    /** 方块实体是电脑时返回它，否则 null */
    @Nullable
    public static PeripheralHost host(@Nullable TileEntity te) {
        return te instanceof PeripheralHost ? (PeripheralHost) te : null;
    }

    /** 这个位置的电脑装着的外设（不是电脑、区块没加载时空表）。服务端主线程调用 */
    public static List<ItemStack> installed(World world, BlockPos pos) {
        if (world == null || !world.isBlockLoaded(pos)) {
            return Collections.emptyList();
        }
        PeripheralHost h = host(world.getTileEntity(pos));
        return h == null ? Collections.emptyList() : new ArrayList<>(h.installedPeripherals());
    }

    /** 这个位置的电脑装没装 kind 这种外设 */
    public static boolean hasKind(World world, BlockPos pos, String kind) {
        return kinds(installed(world, pos)).contains(kind);
    }

    /** 这个位置的电脑有没有能力 cap（如 {@link #CAP_LOCATION}：装了定位模块） */
    public static boolean hasCapability(World world, BlockPos pos, String cap) {
        return hasCapability(installed(world, pos), cap);
    }
}
