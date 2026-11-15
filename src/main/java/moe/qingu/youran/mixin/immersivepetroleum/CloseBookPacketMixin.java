package moe.qingu.youran.mixin.immersivepetroleum;

import blusunrize.immersiveengineering.api.MultiblockHandler;
import moe.qingu.youran.compat.immersivepetroleum.IpRejects;
import moe.qingu.youran.logic.ip.ManualBookmark;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 合上沉浸工程手册的包（{@code CloseBookPacket}）在主线程写手册 NBT 之前先查名字（审查 IP-L2）：
 * 名字（最后看的多方块结构）要长度、字符合理（{@link ManualBookmark}），并且真有这个多方块结构；不合格的不写，手册不变。
 * 名字为空（看的不是多方块页面，清掉记录）照旧。
 */
@Mixin(targets = "flaxbeard.immersivepetroleum.common.network.CloseBookPacket$DoSync", remap = false)
public abstract class CloseBookPacketMixin {
    @Shadow
    private EntityPlayer p;
    @Shadow
    private String name;

    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void youran$checkName(CallbackInfo ci) {
        if (p == null || name == null) {
            return;
        }
        if (!ManualBookmark.plausible(name) || !youran$registered(name)) {
            IpRejects.reject(p.getName(), "手册书签包", "多方块名字长 " + name.length() + " 个字，不合理或没有这个多方块结构");
            ci.cancel();
        }
    }

    @Unique
    private static boolean youran$registered(String name) {
        for (MultiblockHandler.IMultiblock mb : MultiblockHandler.getMultiblocks()) {
            if (name.equals(mb.getUniqueName())) {
                return true;
            }
        }
        return false;
    }
}
