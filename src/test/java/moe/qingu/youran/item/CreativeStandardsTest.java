package moe.qingu.youran.item;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.litho.LampGas;
import moe.qingu.youran.logic.litho.LampSpec;
import moe.qingu.youran.logic.litho.LithoNumbers;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 创造物品栏的标准变种：能直接用；没有 NBT 的物品语义没动 */
class CreativeStandardsTest {
    /** 标准汞灯：有气体（收得了汞）、汞满、没烧坏；没有 NBT 的灯还是没装好（gas 为空） */
    @Test
    void lampIsAssembledAndFull() {
        LampSpec s = LampNbt.read(CreativeStandards.lamp());
        assertEquals(LampGas.ARGON, s.gas);
        assertEquals(LithoNumbers.LAMP_MERCURY, s.mercury);
        assertFalse(s.burntOut());
        assertEquals(0, s.used);
        assertEquals(null, LampNbt.read(null).gas, "没有 NBT 的灯照旧是没装好的");
    }

    /** 每个型号的标准芯片都不报废、有三项指标和固定的序列号 */
    @Test
    void chipsAreGood() {
        for (ChipModel m : ChipModel.values()) {
            NBTTagCompound t = CreativeStandards.chip(m);
            assertEquals(m.id, t.getString(ChipModel.TAG));
            assertFalse(t.getBoolean(ChipNbt.SCRAPPED), m.id + " 不该报废");
            assertTrue(t.getInteger(ChipNbt.MAX_FREQ) > 0, m.id);
            assertTrue(t.getLong(ChipNbt.LIFETIME) > 0, m.id);
            assertEquals(ChipNbt.serial(m, 0), t.getString(ChipNbt.SERIAL));
        }
    }

    /** 标准主板装齐了：两种主板都 complete，ROM 是正版掩模 */
    @Test
    void boardsAreComplete() {
        for (BoardType t : new BoardType[]{BoardType.COMPUTER, BoardType.PC}) {
            NBTTagCompound b = CreativeStandards.board(t);
            assertTrue(BoardNbt.complete(b), t.name);
            assertNotNull(BoardNbt.romContent(b), t.name);
        }
    }

    /** 标准 EPROM 是空白的（没有 Rom），带 2764 芯片与小窗 */
    @Test
    void epromIsBlank() {
        NBTTagCompound t = CreativeStandards.eprom();
        assertFalse(t.hasKey(ItemEprom.TAG_ROM));
        assertEquals("2764", t.getCompoundTag(ItemEprom.TAG_CHIP).getString(ChipModel.TAG));
        assertTrue(t.hasKey(ItemEprom.TAG_WINDOW));
    }

    /** 标准晶圆够做芯片：9 个 9 + 金铸模 */
    @Test
    void waferIsGradeNine() {
        NBTTagCompound t = CreativeStandards.wafer();
        assertEquals(9, WaferNbt.nines(t));
        assertEquals("gold", WaferNbt.mold(t).id);
    }
}
