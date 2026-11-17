/**
 * 芯片品质接进电脑（服主 11-17）：CPU 温度与“最近 1 分钟温度积分”（{@link moe.qingu.youran.api.chip.CpuThermal}）、调频与运行错误率
 * （{@link moe.qingu.youran.api.chip.CpuClock}）、寿命与过热的后果（{@link moe.qingu.youran.api.chip.ChipWear}）、电脑里那片 CPU 的品质
 * （{@link moe.qingu.youran.api.chip.CpuChip}）、各项数（{@link moe.qingu.youran.api.chip.ChipNumbers}，服务端配置 {@code chip_runtime}）。
 * YouranSCP 的普通电脑（Z80）、个人电脑（8088）与家具电脑（YR-2000）共用。纯逻辑，只用原版的 NBT 类；服务端、客户端两个包都带。
 */
package moe.qingu.youran.api.chip;
