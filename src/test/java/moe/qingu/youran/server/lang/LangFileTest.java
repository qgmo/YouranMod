package moe.qingu.youran.server.lang;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LangFileTest {
    @Test
    void likeLanguageMap() {
        Map<String, String> m = LangFile.parse("﻿# 注释\n\nitem.a.name=甲=乙\r\nbad line\n=没有键\nitem.b.name= 前面有空格\n");
        assertEquals("甲=乙", m.get("item.a.name"), "按第一个 = 分开");
        assertEquals(" 前面有空格", m.get("item.b.name"), "值不去空白，同游戏");
        assertFalse(m.containsKey(""));
        assertEquals(2, m.size());
    }

    @Test
    void numericFormatsBecomeStrings() {
        Map<String, String> m = LangFile.parse("a=%d 个\nb=%1$d / %2$.2f\nc=100%%\n");
        assertEquals("%s 个", m.get("a"));
        assertEquals("%1$s / %2$s", m.get("b"));
        assertEquals("100%%", m.get("c"));
    }

    @Test
    void escapesOnlyWithMarker() {
        assertEquals("a\\nb", LangFile.parse("k=a\\nb\n").get("k"));
        Map<String, String> m = LangFile.parse("#PARSE_ESCAPES\nk=a\\nb\\u4e2d\\q\n");
        assertEquals("a\nb中\\q", m.get("k"));
    }
}
