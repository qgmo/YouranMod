package moe.qingu.youran.core;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.List;

/**
 * MixinBooter 的引导（客户端整合包、单人游戏）：MixinBooter 在所有模组上类路径之后调用，这时 IC2 的类已经找得到。
 * 只有装了 MixinBooter 时才会被加载（没有 MixinBooter 时这个类引用的接口不存在，不能从别处碰它）。
 */
public final class YouranLateMixinLoader implements ILateMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return MixinSetup.lateConfigs();
    }
}
