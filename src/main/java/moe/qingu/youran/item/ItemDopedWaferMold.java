package moe.qingu.youran.item;

import moe.qingu.youran.logic.purity.WaferMold;

/**
 * 掺杂硅晶圆铸模（服主 11-15，芯片线第 7 步）：匠魂“盘”铸模在电弧炉里掺上硼与萤石粉。金的、粘土的各一种物品；
 * 浇注混合气做硅晶圆时消耗，晶圆记下铸模材质。
 */
public class ItemDopedWaferMold extends ItemWithTooltip {
    public final WaferMold mold;

    public ItemDopedWaferMold(WaferMold mold) {
        super("doped_wafer_mold_" + mold.id, 16, mold == WaferMold.GOLD
                ? "掺了硼和萤石粉的金铸模，浇注三氯氢硅氢气混合气做成硅晶圆，用一次就没了"
                : "掺了硼和萤石粉的粘土铸模，浇注三氯氢硅氢气混合气做成硅晶圆，用一次就没了");
        this.mold = mold;
    }
}
