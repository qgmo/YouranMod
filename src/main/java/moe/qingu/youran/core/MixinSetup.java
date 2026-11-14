package moe.qingu.youran.core;

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
    /** 修补 IC2 的类（装罐机反应配方）：没装 IC2 时不注册（目标类不存在） */
    public static final String IC2_CONFIG = "mixins.youranmod.ic2.json";
    /** IC2 的主类，用来认出它的 jar */
    static final String IC2_MAIN_CLASS = "ic2/core/IC2.class";

    private MixinSetup() {
    }

    /** 要在 late 时机（模组上了类路径之后）注册的配置：装了 IC2 时是 {@link #IC2_CONFIG}（每次调用时现查） */
    public static List<String> lateConfigs() {
        ClassLoader loader = MixinSetup.class.getClassLoader();
        return configs(loader.getResource(IC2_CONFIG) != null && loader.getResource(IC2_MAIN_CLASS) != null);
    }

    static List<String> configs(boolean ic2) {
        return ic2 ? Collections.singletonList(IC2_CONFIG) : Collections.<String>emptyList();
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
