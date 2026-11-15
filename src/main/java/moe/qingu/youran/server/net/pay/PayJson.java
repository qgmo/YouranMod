package moe.qingu.youran.server.net.pay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import moe.qingu.youran.api.pay.Amount;
import moe.qingu.youran.api.pay.PayItem;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** 账本里物品、金额的 JSON 写法 */
final class PayJson {
    private PayJson() {
    }

    static JsonObject item(PayItem i) {
        JsonObject o = new JsonObject();
        o.addProperty("id", i.id);
        if (i.meta != 0) {
            o.addProperty("meta", i.meta);
        }
        if (i.nbt != null) {
            o.addProperty("nbt", i.nbt);
        }
        if (i.caps != null) {
            o.addProperty("caps", i.caps);
        }
        return o;
    }

    static PayItem item(JsonObject o) {
        return new PayItem(o.get("id").getAsString(), o.has("meta") ? o.get("meta").getAsInt() : 0,
                str(o, "nbt"), str(o, "caps"));
    }

    /** 物品 → 个数 */
    static JsonArray items(Map<PayItem, Long> m) {
        JsonArray a = new JsonArray();
        for (Map.Entry<PayItem, Long> e : m.entrySet()) {
            JsonObject o = item(e.getKey());
            o.addProperty("n", e.getValue());
            a.add(o);
        }
        return a;
    }

    static Map<PayItem, Long> items(JsonElement e) {
        Map<PayItem, Long> m = new LinkedHashMap<>();
        if (e == null || e.isJsonNull()) {
            return m;
        }
        for (JsonElement x : e.getAsJsonArray()) {
            JsonObject o = x.getAsJsonObject();
            m.merge(item(o), o.get("n").getAsLong(), Math::addExact);
        }
        return m;
    }

    static JsonObject amount(Amount a) {
        JsonObject o = new JsonObject();
        o.addProperty("yuan", a.yuan);
        if (!a.items.isEmpty()) {
            o.add("items", items(a.items));
        }
        return o;
    }

    static Amount amount(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return Amount.ZERO;
        }
        JsonObject o = e.getAsJsonObject();
        return Amount.of(o.has("yuan") ? o.get("yuan").getAsLong() : 0, new TreeMap<>(items(o.get("items"))));
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static long num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    static void put(JsonObject o, String k, String v) {
        if (v != null) {
            o.addProperty(k, v);
        }
    }
}
