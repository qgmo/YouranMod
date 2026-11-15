package moe.qingu.youran.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 两种引导方式共用的东西：要交给 Mixin 的配置（照 YouranSCP 的“一份代码，两种引导”）。
 *
 * <p>Mixin 类只有一份。两边只差“谁、在什么时候把配置交给 Mixin”：
 * <ul>
 *     <li>客户端（含单人游戏，MixinBooter）：{@link YouranLateMixinLoader}，由 MixinBooter 在模组上类路径之后注册（late mixin）。</li>
 *     <li>服务端（SpongeForge 自带的 Mixin 0.8，没有 MixinBooter）：{@link YouranCorePlugin}，在核心插件阶段先把 IC2 的 jar
 *     加进类路径，再注册配置。</li>
 * </ul>
 */
public final class MixinSetup {
    /** 修补 IC2 的类（装罐机反应配方、太阳能蒸馏机提纯三氯氢硅）：没装 IC2 时不注册（目标类不存在） */
    public static final String IC2_CONFIG = "mixins.youranmod.ic2.json";
    /** 修补沉浸工程的类（搅拌机按比例换流体）：没装沉浸工程时不注册 */
    public static final String IE_CONFIG = "mixins.youranmod.ie.json";
    /** 修补沉浸原油的类（精馏塔提纯三氯氢硅）：要沉浸工程与沉浸原油都在 */
    public static final String IP_CONFIG = "mixins.youranmod.ip.json";
    /** 修补匠魂的类（浇铸台收带 NBT 的流体）：没装匠魂时不注册 */
    public static final String TIC_CONFIG = "mixins.youranmod.tic.json";
    /** IC2 的主类，用来认出它的 jar */
    static final String IC2_MAIN_CLASS = "ic2/core/IC2.class";
    /** 沉浸工程的主类 */
    static final String IE_MAIN_CLASS = "blusunrize/immersiveengineering/ImmersiveEngineering.class";
    /** 沉浸原油的主类 */
    static final String IP_MAIN_CLASS = "flaxbeard/immersivepetroleum/ImmersivePetroleum.class";
    /** 匠魂的主类 */
    static final String TIC_MAIN_CLASS = "slimeknights/tconstruct/TConstruct.class";
    /** 要修补的模组的主类，核心插件按这些找 jar（顺序同 {@link #configs(boolean, boolean, boolean, boolean)} 的参数） */
    static final String[] MAIN_CLASSES = {IC2_MAIN_CLASS, IE_MAIN_CLASS, IP_MAIN_CLASS, TIC_MAIN_CLASS};

    private MixinSetup() {
    }

    /** 要在 late 时机（模组上了类路径之后）注册的配置：按装了哪些模组（每次调用时现查） */
    public static List<String> lateConfigs() {
        ClassLoader loader = MixinSetup.class.getClassLoader();
        return configs(present(loader, IC2_CONFIG, IC2_MAIN_CLASS), present(loader, IE_CONFIG, IE_MAIN_CLASS),
                present(loader, IP_CONFIG, IP_MAIN_CLASS), present(loader, TIC_CONFIG, TIC_MAIN_CLASS));
    }

    private static boolean present(ClassLoader loader, String config, String mainClass) {
        return loader.getResource(config) != null && loader.getResource(mainClass) != null;
    }

    static List<String> configs(boolean ic2) {
        return configs(ic2, false, false);
    }

    static List<String> configs(boolean ic2, boolean ie, boolean ip) {
        return configs(ic2, ie, ip, false);
    }

    /** 沉浸原油的配置要沉浸工程也在（它的类继承沉浸工程的类）；匠魂的单独看 */
    static List<String> configs(boolean ic2, boolean ie, boolean ip, boolean tic) {
        List<String> configs = new ArrayList<>();
        if (ic2) {
            configs.add(IC2_CONFIG);
        }
        if (ie) {
            configs.add(IE_CONFIG);
            if (ip) {
                configs.add(IP_CONFIG);
            }
        }
        if (tic) {
            configs.add(TIC_CONFIG);
        }
        return Collections.unmodifiableList(configs);
    }

    /** 有 MixinBooter（或带它的 Cleanroom）时由它注册配置 */
    static boolean hasMixinBooter() {
        return MixinSetup.class.getClassLoader().getResource("zone/rong/mixinbooter/ILateMixinLoader.class") != null;
    }

    /** 有没有 Mixin（SpongeForge 自带或 MixinBooter 带的）；都没有时（纯 Forge 且没装 MixinBooter）什么都不注册 */
    static boolean hasMixin() {
        return MixinSetup.class.getClassLoader().getResource("org/spongepowered/asm/mixin/Mixins.class") != null;
    }
}
