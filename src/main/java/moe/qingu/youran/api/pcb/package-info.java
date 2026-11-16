/**
 * 主板、ROM 的数据（服主 11-16 的电脑组装）：主板物品带着装上的芯片（品质数据）与 ROM 的内容，组装电脑时带进电脑方块。
 * 电脑那边（YouranSCP）开机时用 {@link moe.qingu.youran.api.pcb.RomContent} 比 ROM 内容是不是正版，用 {@link moe.qingu.youran.api.pcb.BoardNbt}
 * 读主板上的芯片。服务端、客户端两个包都带这个包；只用原版的 NBT 类，没有 YouranMod 物品以外的依赖。
 */
package moe.qingu.youran.api.pcb;
