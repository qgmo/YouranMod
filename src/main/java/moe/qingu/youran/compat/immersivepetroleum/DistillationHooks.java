package moe.qingu.youran.compat.immersivepetroleum;

import flaxbeard.immersivepetroleum.api.crafting.DistillationRecipe;
import moe.qingu.youran.fluid.FluidPurity;
import moe.qingu.youran.logic.purity.Purity;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 精馏塔提纯三氯氢硅（芯片线“硅”第 4 步，服主 11-15），由 {@code mixin.immersivepetroleum} 调用。只有装了沉浸原油时才会被加载。
 *
 * <p>沉浸原油找配方（{@code DistillationRecipe.findRecipe}）按 {@code FluidStack.containsFluid} 比，连 NBT 一起比，
 * 一份固定的配方对不上带纯度标记的三氯氢硅，产物也没法随输入的纯度变。所以三氯氢硅由这里按输入现给一份配方
 * （{@link TrichlorosilaneDistillation}），同一种 NBT 给同一个对象：精馏塔判断“罐里的和要灌进来的是不是同一个配方”时比的是对象。
 */
public final class DistillationHooks {
    /** 每种 NBT 一份；纯度的种类很少（每蒸一遍多一种），留 64 份够用 */
    private static final int CACHE_SIZE = 64;
    private static final Map<String, TrichlorosilaneDistillation> CACHE = new LinkedHashMap<String, TrichlorosilaneDistillation>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TrichlorosilaneDistillation> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /** 输入罐里的余量（不到一份）放了这么多 tick 没变，就当一份蒸掉 */
    public static final int IDLE_TICKS = 20;
    /** 精馏塔这一 tick 允不允许蒸余量（由 {@code mixin.immersivepetroleum.TileEntityDistillationTowerMixin} 在 update 前后设） */
    private static final ThreadLocal<Boolean> ALLOW_REMAINDER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private DistillationHooks() {
    }

    /** 精馏塔 update 开始、结束时调用：这一 tick 找配方时余量能不能当一份 */
    public static void allowRemainder(boolean allow) {
        ALLOW_REMAINDER.set(allow);
    }

    /** 输入罐里是不是只剩余量（三氯氢硅、不到一份） */
    public static boolean isRemainder(@Nullable FluidStack tank) {
        return handles(tank) && tank.amount > 0 && tank.amount < Purity.BATCH_INPUT;
    }

    /** 归不归这里管：是三氯氢硅 */
    public static boolean handles(@Nullable FluidStack input) {
        return FluidPurity.isTrichlorosilane(input);
    }

    /**
     * 三氯氢硅的配方；量不够一份（{@value Purity#BATCH_INPUT} mB）时为 null，同原来 {@code containsFluid} 量不够的情况，
     * 只有精馏塔放了一会儿的余量例外（{@link #allowRemainder}）。
     */
    @Nullable
    public static DistillationRecipe recipeFor(FluidStack input) {
        int amount = Purity.BATCH_INPUT;
        if (input.amount < Purity.BATCH_INPUT) {
            if (input.amount <= 0 || !ALLOW_REMAINDER.get()) {
                return null;
            }
            amount = input.amount;
        }
        String key = (input.tag == null ? "" : input.tag.toString()) + "#" + amount;
        int batch = amount;
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(key, k -> new TrichlorosilaneDistillation(input, batch));
        }
    }
}
