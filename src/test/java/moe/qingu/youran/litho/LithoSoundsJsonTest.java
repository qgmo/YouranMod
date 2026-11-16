package moe.qingu.youran.litho;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sounds.json：光刻机的五个声音事件都有条目、字幕键，字幕在三份语言文件里；只引用别的模组或原版的声音，不带自己的 ogg */
class LithoSoundsJsonTest {
    private static final String[] EVENTS = {"litho.hum", "litho.duv", "litho.step", "litho.chime", "litho.alarm"};

    @Test
    void everyEventHasEntriesAndSubtitles() throws IOException {
        JsonObject root = new JsonParser().parse(new String(Files.readAllBytes(Paths.get("src/main/resources/assets/youranmod/sounds.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
        for (String e : EVENTS) {
            assertTrue(root.has(e), e);
            JsonObject ev = root.getAsJsonObject(e);
            assertTrue(ev.getAsJsonArray("sounds").size() > 0, e);
            String sub = ev.get("subtitle").getAsString();
            for (String lang : new String[]{"en_us", "zh_cn", "zh_tw"}) {
                List<String> lines = Files.readAllLines(Paths.get("src/main/resources/assets/youranmod/lang/" + lang + ".lang"), StandardCharsets.UTF_8);
                assertTrue(lines.stream().anyMatch(l -> l.startsWith(sub + "=")), lang + " 缺字幕 " + sub);
            }
            for (JsonElement s : ev.getAsJsonArray("sounds")) {
                String name = s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString();
                assertTrue(name.contains(":"), "要写明域：" + name);
                assertFalse(Files.exists(Paths.get("src/main/resources/assets/youranmod/sounds")), "不带自己的声音文件");
            }
        }
    }
}
