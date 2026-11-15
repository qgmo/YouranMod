/**
 * 电脑的外设（设计：外设一律走统一的外设接口；外设接口放在 YouranMod，服主 11-15）。
 *
 * <p>一块外设就是一个物品：汉卡、软驱、定位模块、以后的时钟卡、网卡……物品实现 {@link moe.qingu.youran.api.peripheral.Peripheral}
 * （方便起见继承 {@link moe.qingu.youran.api.peripheral.PeripheralItem}）。电脑（YouranSCP 里接着 079 服务的电脑、家具电脑、
 * 以后别的模组的电脑）把外设物品装进自己的外设槽，实现 {@link moe.qingu.youran.api.peripheral.PeripheralHost} 报出“装了哪些”；
 * 别处（如 YouranMod 的网络）用 {@link moe.qingu.youran.api.peripheral.Peripherals} 查某个位置的电脑装没装某种外设、有没有某项能力。
 *
 * <p>两类外设用同一套接口：
 * <ul>
 *     <li><b>接进模拟器的</b>（汉卡、软驱）：种类编号交给电脑的模拟器（YouranSCP 告诉 079 服务），占哪些 I/O 端口、中断、内存窗口
 *     由模拟器那边的实现声明（079 服务的 {@code periph} 包），物品这边不管；</li>
 *     <li><b>只是“装了就有某项能力”的</b>（定位模块：没有 I/O 端口，能力由 YouranMod 的网络读取）：在
 *     {@link moe.qingu.youran.api.peripheral.Peripheral#peripheralCapabilities} 里报能力名（如 {@link moe.qingu.youran.api.peripheral.Peripherals#CAP_LOCATION}），
 *     不跑模拟器的电脑（家具电脑）也一样实现 {@code PeripheralHost}。</li>
 * </ul>
 *
 * <p>约定：同一台电脑里同一种外设只装一块；外设只能在关机时插拔（同真机的扩展卡；由电脑那边管）；拆下电脑时外设随电脑物品的 NBT 走。
 * 服务端、客户端两个包都带这个包。
 */
package moe.qingu.youran.api.peripheral;
