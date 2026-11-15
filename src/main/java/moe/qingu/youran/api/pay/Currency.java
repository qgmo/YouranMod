package moe.qingu.youran.api.pay;

/**
 * pay.yr 里能互换的“钱”（服主 11-15）：绿宝石与悠然元按固定汇率 1 绿宝石 = {@value #YUAN_PER_EMERALD} 元互换。
 * 悠然元是 YouranMod 的一元纸币、一元硬币、五元、十元。都只认没有 NBT、数据值 0 的（改过名的就是普通物品）。
 * 账户里两者按价值合并记（单位：元）。其他物品（钻石等）不互换。
 */
public final class Currency {
    public static final int YUAN_PER_EMERALD = 2;

    /** 网站标价用的单位（只影响显示；记账一律按元） */
    public enum Unit {
        EMERALD("emerald"),
        YUAN("yuan");

        public final String code;

        Unit(String code) {
            this.code = code;
        }

        public static Unit of(String code) {
            for (Unit u : values()) {
                if (u.code.equals(code)) {
                    return u;
                }
            }
            return null;
        }
    }

    /** 面额（价值单位：元） */
    public enum Denomination {
        EMERALD("minecraft:emerald", YUAN_PER_EMERALD),
        TEN("youranmod:tenyuan", 10),
        FIVE("youranmod:fiveyuan", 5),
        ONE_NOTE("youranmod:oneyuan", 1),
        ONE_COIN("youranmod:oneyuancoin", 1);

        public final PayItem item;
        public final int value;

        Denomination(String id, int value) {
            this.item = PayItem.of(id, 0);
            this.value = value;
        }

        /** 这种物品是哪一种钱；不是钱为 null */
        public static Denomination of(PayItem item) {
            if (item == null || item.meta != 0 || item.nbt != null || item.caps != null) {
                return null;
            }
            for (Denomination d : values()) {
                if (d.item.id.equals(item.id)) {
                    return d;
                }
            }
            return null;
        }
    }

    private Currency() {
    }

    /** 按单位写的价格换成元 */
    public static long toYuan(long amount, Unit unit) {
        return unit == Unit.EMERALD ? Math.multiplyExact(amount, YUAN_PER_EMERALD) : amount;
    }

    /** 给人看：{@code 20 绿宝石}、{@code 15 元}；按绿宝石标但有零头时写成 {@code 10.5 绿宝石} */
    public static String format(long yuan, Unit unit) {
        if (unit == Unit.EMERALD) {
            return (yuan % YUAN_PER_EMERALD == 0 ? Long.toString(yuan / YUAN_PER_EMERALD)
                    : (yuan / YUAN_PER_EMERALD) + ".5") + " 绿宝石";
        }
        return yuan + " 元";
    }

    /** 两种都写：{@code 20 绿宝石（40 元）}、{@code 15 元（7.5 绿宝石）} */
    public static String formatBoth(long yuan, Unit unit) {
        Unit other = unit == Unit.EMERALD ? Unit.YUAN : Unit.EMERALD;
        return format(yuan, unit) + "（" + format(yuan, other) + "）";
    }
}
