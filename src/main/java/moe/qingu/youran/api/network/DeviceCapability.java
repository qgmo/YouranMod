package moe.qingu.youran.api.network;

/**
 * 设备网卡附带的可选硬件能力（服主 11-15）。由电脑模组在登记设备时（{@link NetworkDevices#register(int, int, int, int, DeviceCapability...)}）
 * 或之后（{@link DeviceHandle#setCapability}）告诉 YouranMod；网站不能假设一定有，要检测（见 {@link YouranNetworkApi#identify}）。
 */
public enum DeviceCapability {
    /**
     * 游戏角色一键登录：这台设备发出的请求可以由身份服务（YouranAuth 的 {@code id.yr}）按来源地址认出当前操作者，
     * 经 {@link SystemConfirm 系统确认框} 同意后登录。
     */
    ONE_CLICK_LOGIN,
    /** 定位模块（位置外设）：可以请 YouranMod 给这台设备的位置签名（{@code api.network.location}） */
    LOCATION
}
