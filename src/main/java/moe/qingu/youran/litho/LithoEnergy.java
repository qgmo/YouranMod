package moe.qingu.youran.litho;

import moe.qingu.youran.logic.litho.LithoNumbers;
import net.minecraftforge.energy.EnergyStorage;

/** 光刻机的电（FE；IC2 的电经工业线缆自动换成 FE，本服 1 EU = 2 FE）：外面只能往里充，不能抽 */
public class LithoEnergy extends EnergyStorage {
    public LithoEnergy() {
        super(LithoNumbers.ENERGY_CAPACITY, LithoNumbers.ENERGY_MAX_INPUT, 0);
    }

    /** 机器自己用电：够就扣掉、返回 true */
    public boolean consume(int amount) {
        if (energy < amount) {
            return false;
        }
        energy -= amount;
        return true;
    }

    public void set(int value) {
        energy = Math.max(0, Math.min(capacity, value));
    }
}
