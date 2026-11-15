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

    /** 输入罐里的余量（不到一份）放了这么多 tick（5 秒）没变，就当一份蒸掉；等久一点，管道断断续续送来的也能先凑成整份 */
    public static final int IDLE_TICKS = 100;

    /** 找配方时的场合：量不到一份时怎么回答 */
    public enum Lookup {
        /**
         * 不在精馏塔的 update 里（管道往输入口灌、界面放容器时比“是不是同一个配方”）：给整份的配方，
         * 罐里只剩余量时同纯度的照样能补进来。这时不会开始处理。
         */
        COMPARE,
        /** 精馏塔 update 里开始处理：余量没放够 {@link #IDLE_TICKS} tick，不给配方 */
        UPDATE,
        /** 精馏塔 update 里开始处理：余量放够了，给余量那么多的配方 */
        UPDATE_REMAINDER,
        /** 读档恢复处理中的配方（{@code DistillationRecipe.loadFromNBT}）：存的输入是多少就给多少的 */
        EXACT
    }

    private static final ThreadLocal<Lookup> LOOKUP = ThreadLocal.withInitial(() -> Lookup.COMPARE);

    private DistillationHooks() {
    }

    /** 设这个线程接下来找配方的场合，返回原来的（用完要设回去） */
    public static Lookup lookup(Lookup lookup) {
        Lookup old = LOOKUP.get();
        LOOKUP.set(lookup);
        return old;
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
     * 三氯氢硅的配方。量够一份时是整份（{@value Purity#BATCH_INPUT} mB）的；不够一份时看场合（{@link Lookup}）：
     * 比较时给整份的，开始处理时只有放够了的余量才给余量的配方（不然为 null，同原来 {@code containsFluid} 量不够），
     * 读档时照存的量给。
     */
    @Nullable
    public static DistillationRecipe recipeFor(FluidStack input) {
        int amount = Purity.BATCH_INPUT;
        if (input.amount <= 0) {
            return null;
        }
        if (input.amount < Purity.BATCH_INPUT) {
            Lookup lookup = LOOKUP.get();
            if (lookup == Lookup.UPDATE) {
                return null;
            }
            if (lookup != Lookup.COMPARE) {
                amount = input.amount;
            }
        }
        String key = (input.tag == null ? "" : input.tag.toString()) + "#" + amount;
        int batch = amount;
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(key, k -> new TrichlorosilaneDistillation(input, batch));
        }
    }
}
