package moe.qingu.youran.logic.uncraft;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 暮色森林拆解台的规则（服主 11-17：“把会出问题的拆解禁止掉”），只用普通数据，方便单测；
 * 读物品、配方、NBT 的部分在 {@code compat.twilightforest.UncraftGuard}。
 *
 * <p>拆解台的做法（暮色森林 3.11.1021，加上整合包里 Twilight Tweaks 改过的选配方）：放进去的物品按“物品 + 数据值”找所有产物是它的
 * 工作台配方（不看 NBT），玩家可以在几条配方之间换、在每格的几种候选材料之间换，拿走就按配方把材料全给玩家（带容器的材料，如水桶，不给）。
 * 下面每条规则对应一类由此产生的问题：
 * <ol>
 *     <li>{@link Verdict#INPUT_BLOCKED}：名单里的物品整件不让拆（芯片线、电脑、货币等数据或障碍物品，以及审查找出的刷物配方的产物）；</li>
 *     <li>{@link Verdict#CARRIES_DATA}：带本服数据（主板、芯片、软盘的 NBT）的物品不让拆，不管是哪个模组的；</li>
 *     <li>{@link Verdict#RECIPE_BLOCKED}：名单里的配方（注册名）不拿来拆；</li>
 *     <li>{@link Verdict#YIELDS_BLOCKED}：拆出来会有不该凭空得到的东西（芯片、主板、掩模、汞灯……）的配方不拿来拆；</li>
 *     <li>{@link Verdict#RETURNS_INPUT}：拆出来的材料里有它自己，或者某格材料本来就认它（染色、充能、重置这类“改造”配方），
 *     拆一次白得一份另加的材料（羊毛还能挑别的颜色，来回拆出无限的染料）；</li>
 *     <li>{@link Verdict#OUTPUT_NBT_MISMATCH}：配方产物带 NBT、而放进去的物品没有这些 NBT（拆解台不比 NBT，便宜做法做的东西会被当成贵做法拆）；</li>
 *     <li>{@link Verdict#CRAFTED_DIFFERS}：把拆出来的材料按原样摆回去合成，出来的不是这件东西（NBT 对不上，如线圈长度、平板里的存储模块档次）；</li>
 *     <li>{@link Verdict#KEEPS_INGREDIENT}：合成时会原样还回某格材料（工具、螺丝刀、被复制的那件），拆解台却把它也给了玩家；</li>
 *     <li>{@link Verdict#CHECK_FAILED}：检查时配方自己出错，不知道会怎样，按不让拆处理。</li>
 * </ol>
 * 另外：一格有几种候选材料的（如“水桶 / 化学喷射器 / 水单元”“金苹果 / 附魔金苹果”“箱子 / 末影箱”“钻石 / 工业钻石”），
 * 不让玩家挑，见 {@link #chooseVariant}。
 */
public final class UncraftRules {
    private UncraftRules() {
    }

    public enum Verdict {
        ALLOW, INPUT_BLOCKED, CARRIES_DATA, RECIPE_BLOCKED, YIELDS_BLOCKED, RETURNS_INPUT, OUTPUT_NBT_MISMATCH, CRAFTED_DIFFERS,
        KEEPS_INGREDIENT, CHECK_FAILED;

        public boolean allowed() {
            return this == ALLOW;
        }
    }

    /** 名单与开关 */
    public static final class Rules {
        public final boolean enabled;
        public final ItemPatterns blockedInputs;
        public final ItemPatterns neverYield;
        public final Set<String> blockedRecipes;
        public final Set<String> dataKeys;

        public Rules(boolean enabled, ItemPatterns blockedInputs, ItemPatterns neverYield, Collection<String> blockedRecipes,
                     Collection<String> dataKeys) {
            this.enabled = enabled;
            this.blockedInputs = blockedInputs;
            this.neverYield = neverYield;
            Set<String> r = new HashSet<>();
            for (String s : blockedRecipes) {
                if (s != null && !s.trim().isEmpty() && !s.trim().startsWith("#")) {
                    r.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
            this.blockedRecipes = Collections.unmodifiableSet(r);
            this.dataKeys = Collections.unmodifiableSet(new HashSet<>(dataKeys));
        }
    }

    /**
     * 拆解格里的一格：物品、数据值，拆解台会不会把它给玩家（空格、带容器的材料不给），以及它是不是“放进去的这件”
     * （同物品同数据值、NBT 放进去的都有，或者这一格的配方材料本来就认放进去的这件，如“任意羊毛”认白羊毛）。
     */
    public static final class Part {
        public final String id;
        public final int meta;
        public final boolean given;
        public final boolean isInput;

        public Part(String id, int meta, boolean given, boolean isInput) {
            this.id = id;
            this.meta = meta;
            this.given = given;
            this.isInput = isInput;
        }
    }

    /** 把拆出来的材料按原样摆回工作台试合成的结果 */
    public static final class Simulation {
        /** 没试（配方不认这样的摆法，或者产物为空）：不按这一项判 */
        public static final Simulation NOT_RUN = new Simulation(true, false);

        /** 合成出来的就是放进去的这件（物品相同，产物的 NBT 放进去的物品都有） */
        public final boolean craftsInput;
        /** 合成后会原样还回某一格、而拆解台会把那一格给玩家 */
        public final boolean keepsGivenPart;

        public Simulation(boolean craftsInput, boolean keepsGivenPart) {
            this.craftsInput = craftsInput;
            this.keepsGivenPart = keepsGivenPart;
        }
    }

    /**
     * 放进去的物品本身能不能拆（不看配方）。{@code topKeys} 是它 NBT 顶层的键，{@code blockEntityKeys} 是 {@code BlockEntityTag} 里的键
     * （方块物品的数据在那里），都可以是空的。
     */
    public static Verdict input(Rules rules, String id, int meta, Collection<String> topKeys, Collection<String> blockEntityKeys) {
        if (!rules.enabled) {
            return Verdict.ALLOW;
        }
        if (rules.blockedInputs.matches(id, meta)) {
            return Verdict.INPUT_BLOCKED;
        }
        if (carriesData(rules, topKeys) || carriesData(rules, blockEntityKeys)) {
            return Verdict.CARRIES_DATA;
        }
        return Verdict.ALLOW;
    }

    private static boolean carriesData(Rules rules, Collection<String> keys) {
        if (keys == null) {
            return false;
        }
        for (String k : keys) {
            if (rules.dataKeys.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这条配方能不能拿来拆这件物品。{@code parts} 是拆解格里显示的九格（照拆解台的摆法，空格也算），
     * {@code outputNbtCovered} 表示配方产物的 NBT 放进去的物品都有（产物没有 NBT 时为 true）。
     */
    public static Verdict recipe(Rules rules, String recipeId, List<Part> parts,
                                 boolean outputNbtCovered, Simulation sim) {
        if (!rules.enabled) {
            return Verdict.ALLOW;
        }
        if (recipeId != null && rules.blockedRecipes.contains(recipeId.toLowerCase(Locale.ROOT))) {
            return Verdict.RECIPE_BLOCKED;
        }
        for (Part p : parts) {
            if (p == null || !p.given) {
                continue;
            }
            if (rules.neverYield.matches(p.id, p.meta)) {
                return Verdict.YIELDS_BLOCKED;
            }
            if (p.isInput) {
                return Verdict.RETURNS_INPUT;
            }
        }
        if (!outputNbtCovered) {
            return Verdict.OUTPUT_NBT_MISMATCH;
        }
        if (!sim.craftsInput) {
            return Verdict.CRAFTED_DIFFERS;
        }
        if (sim.keepsGivenPart) {
            return Verdict.KEEPS_INGREDIENT;
        }
        return Verdict.ALLOW;
    }

    /**
     * JEI 的“拆解”页（整合包里的 Twilight Tweaks 加的，把工作台配方反过来列）要不要藏起这条配方（服主 11-18）。
     * 页上把配方产物当作放进拆解台的东西：{@code input} 是产物整件的判定（{@link #input}），{@code recipe} 是这条配方的判定
     * （{@link #recipe}；产物整件不让拆时不用算，可以是 null）。
     * <ul>
     *     <li>整件不让拆、或这条配方不拿来拆：藏；</li>
     *     <li>检查时配方自己出错（{@link Verdict#CHECK_FAILED}）：照旧显示。客户端是在没有世界的时候判的（JEI 启动时还没进世界），
     *     出错可能只是因为没有世界，拆解台在服务端有世界时不一定出错；不知道就不藏，真拆的时候服务端会挡。</li>
     * </ul>
     */
    public static boolean hiddenInJei(Verdict input, Verdict recipe) {
        if (!input.allowed()) {
            return true;
        }
        if (recipe == null || recipe == Verdict.CHECK_FAILED) {
            return false;
        }
        return !recipe.allowed();
    }

    /**
     * 一格有几种候选材料时给哪一种（拆解台本来让玩家在候选里随便挑，挑贵的就是洗：普通箱子做的拆出末影箱、绿粘液球做的拆出紫粘液球、
     * 橡树苗做的拆出粘液树种子……）：候选里有 {@code cheaper} 名单里的（便宜的替代品，如 IC2 工业钻石、Quark 的根染料）给它，
     * 否则给第一种（配方作者列的第一种，一般是原版或最普通的那种）。返回下标；没有候选时为 -1。
     */
    public static int chooseVariant(ItemPatterns cheaper, String[] ids, int[] metas) {
        if (ids == null || ids.length == 0) {
            return -1;
        }
        for (int i = 0; i < ids.length; i++) {
            if (cheaper.matches(ids[i], metas[i])) {
                return i;
            }
        }
        return 0;
    }
}
