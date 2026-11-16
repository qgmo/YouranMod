package moe.qingu.youran.api.lang;

import moe.qingu.youran.item.ItemChipDesign;
import moe.qingu.youran.item.ItemPcbFilm;
import moe.qingu.youran.item.ItemPhotomask;
import moe.qingu.youran.logic.litho.ChipModel;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 名字带参数的物品（掩模、设计图、底片）：服务端按各语言拼出名字（MineBay 的搜索、排序、订单记录用） */
class FormattedItemNameTest {
    @BeforeAll
    static void boot() {
        Bootstrap.register();
    }

    @AfterEach
    void after() {
        ServerLang.resetForTest();
    }

    private static void table(String... localeKeyValue) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < localeKeyValue.length; i += 3) {
            m.put(localeKeyValue[i] + "|" + localeKeyValue[i + 1], localeKeyValue[i + 2]);
        }
        ServerLang.resetForTest();
        ServerLang.start(() -> new LangSource() {
            @Override
            public String find(String locale, String key) {
                return m.get(locale + "|" + key);
            }

            @Override
            public String summary() {
                return "测试";
            }
        }, false);
    }

    @Test
    void masksDesignsFilms() {
        table("zh_cn", "item.photomask.name", "%s 掩模", "zh_cn", "youranmod.chip.model.z80", "Z80",
                "zh_cn", "item.chip_design.name", "%s 芯片设计图", "zh_cn", "youranmod.chip.model.yr2000", "YR-2000",
                "zh_tw", "item.photomask.name", "%s 光罩",
                "zh_cn", "item.pcb_film.name", "%s线路底片", "zh_cn", "youranmod.board.pc", "个人电脑");
        ItemPhotomask mask = new ItemPhotomask();
        mask.setTranslationKey("photomask");
        ItemChipDesign design = new ItemChipDesign();
        design.setTranslationKey("chip_design");
        assertEquals("Z80 掩模", ServerLang.findItemName("zh_cn", mask.make(ChipModel.Z80)));
        assertEquals("YR-2000 芯片设计图", ServerLang.findItemName("zh_cn", design.make(ChipModel.YR2000)));
        assertEquals("youranmod.chip.model.z80 光罩", ServerLang.findItemName("zh_tw", mask.make(ChipModel.Z80)), "参数查不到时用键名（服务端英文也没有）");
        ItemPcbFilm film = new ItemPcbFilm();
        assertEquals("个人电脑线路底片", ServerLang.findItemName("zh_cn", new ItemStack(film, 1, 1)));
    }
}
