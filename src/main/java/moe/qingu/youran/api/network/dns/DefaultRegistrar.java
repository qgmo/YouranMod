package moe.qingu.youran.api.network.dns;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import moe.qingu.youran.api.network.DomainNames;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.packet.DnsMessage;
import moe.qingu.youran.network.NetworkBackend;
import moe.qingu.youran.network.NetworkHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * 默认注册局：先到先得，两种身份都收，注册信息存在一个 JSON 文件里（一般在世界存档的 {@code youranmod/dns/}，见 {@link #inWorldSave}），
 * 带 whois。{@code .yr} 用它。
 * <p>
 * 名字归第一个注册它的模组（以注册局身份注册的，算那个注册局所属的模组）；同一个模组再注册是更新（换 DNS 服务器、换身份），
 * 别的模组注册同一个名字失败。只收区下面正好一层的名字（区是 {@code yr} 时收 {@code minebay.yr}，不收 {@code a.minebay.yr}）。
 * 注册成功后把这个名字在 {@code dns} 上的区里委派给申请人交的 DNS 服务器。只会返回成功或失败，不会等待。
 * <p>
 * 要加限制时继承它、覆盖 {@link #refuse}（例如 YouranMod 的 {@code .com} 只收以模组身份注册的）。
 */
public class DefaultRegistrar implements Registrar {
    private static final Logger LOG = LogManager.getLogger("YouranMod");

    private final String modId;
    private final String zone;
    private final DnsServer dns;
    private final File file;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new TreeMap<>();

    private static final class Entry {
        final String name;
        final String owner;
        final boolean registrarIdentity;
        final Registrar subRegistrar;
        final List<Ipv6Address> servers;
        final long registeredAt;

        Entry(String name, String owner, boolean registrarIdentity, Registrar subRegistrar, List<Ipv6Address> servers, long registeredAt) {
            this.name = name;
            this.owner = owner;
            this.registrarIdentity = registrarIdentity;
            this.subRegistrar = subRegistrar;
            this.servers = servers;
            this.registeredAt = registeredAt;
        }
    }

    /**
     * @param modId 这个注册局所属的模组
     * @param zone  管的区（如 {@code yr}，或以注册局身份注册到的 {@code example.yr}）
     * @param dns   这个区的权威 DNS（{@code zone} 由注册局加进去；委派写在这里）
     * @param file  注册信息存在哪；文件坏了时改名成 {@code .bad-<时间>} 留着、从空的开始，并记日志
     */
    public DefaultRegistrar(String modId, String zone, DnsServer dns, File file) {
        this(modId, zone, dns, file, System::currentTimeMillis);
    }

    DefaultRegistrar(String modId, String zone, DnsServer dns, File file, LongSupplier clock) {
        if (modId == null || modId.isEmpty() || dns == null || file == null) {
            throw new IllegalArgumentException("modId、dns、file 不能为空");
        }
        this.modId = modId;
        this.zone = DnsServer.name(zone);
        this.dns = dns;
        this.file = file;
        this.clock = clock;
        load();
        dns.addZone(this.zone);
        for (Entry e : entries.values()) {
            dns.delegate(e.name, e.servers);
        }
    }

    /**
     * 存在世界存档 {@code youranmod/dns/<区>.json} 的默认注册局（区名就是文件名，如 {@code yr.json}）。
     * 网络没在运行时抛出 {@link IllegalStateException}
     */
    public static DefaultRegistrar inWorldSave(String modId, String zone, DnsServer dns) {
        NetworkBackend b = NetworkHooks.backend();
        File world = b == null ? null : b.worldDirectory();
        if (world == null) {
            throw new IllegalStateException("网络没在运行，找不到世界存档");
        }
        String z = DnsServer.name(zone);
        return new DefaultRegistrar(modId, z, dns, new File(new File(new File(world, "youranmod"), "dns"), (z.isEmpty() ? "root" : z) + ".json"));
    }

    @Override
    public String modId() {
        return modId;
    }

    /** 管的区 */
    public String zone() {
        return zone;
    }

    /**
     * 拒绝的理由；不拒绝返回 null（默认什么都收）。在注册前、名字和地址检查过以后调用。
     *
     * @param name              要注册的名字（A-label）
     * @param owner             申请人所属的模组
     * @param registrarIdentity 是不是以注册局身份申请的
     */
    protected String refuse(String name, String owner, boolean registrarIdentity) {
        return null;
    }

    @Override
    public Registration register(String name, Registrar registrar, Collection<Ipv6Address> servers) {
        if (registrar == null) {
            throw new IllegalArgumentException("registrar 不能为 null");
        }
        return doRegister(name, registrar.modId(), registrar, servers);
    }

    @Override
    public Registration register(String name, String modId, Collection<Ipv6Address> servers) {
        if (modId == null || modId.isEmpty()) {
            throw new IllegalArgumentException("modId 不能为空");
        }
        return doRegister(name, modId, null, servers);
    }

    private Registration doRegister(String name, String owner, Registrar sub, Collection<Ipv6Address> servers) {
        String n;
        try {
            n = DnsMessage.canonical(DomainNames.toAscii(name));
        } catch (IllegalArgumentException e) {
            return Registration.failure(e.getMessage());
        }
        String label = zone.isEmpty() ? n : n.endsWith("." + zone) ? n.substring(0, n.length() - zone.length() - 1) : null;
        if (label == null || label.isEmpty() || label.contains(".")) {
            return Registration.failure(n + " 不是 " + (zone.isEmpty() ? "根" : "." + zone) + " 下面一层的名字");
        }
        List<Ipv6Address> list;
        try {
            list = DnsServer.checkServers(servers, false);
        } catch (IllegalArgumentException e) {
            return Registration.failure(e.getMessage());
        }
        String why = refuse(n, owner, sub != null);
        if (why != null) {
            return Registration.failure(why);
        }
        Entry fresh;
        synchronized (this) {
            Entry old = entries.get(n);
            if (old != null && !old.owner.equals(owner)) {
                return Registration.failure(n + " 已经被 " + old.owner + " 注册了");
            }
            fresh = new Entry(n, owner, sub != null, sub, list, old != null ? old.registeredAt : clock.getAsLong());
            entries.put(n, fresh);
            try {
                save();
            } catch (IOException e) {
                if (old == null) {
                    entries.remove(n);
                } else {
                    entries.put(n, old);
                }
                LOG.error("游戏内网络：注册局 .{} 存不下注册信息 {}", zone, file, e);
                return Registration.failure("注册局存不下注册信息");
            }
        }
        dns.delegate(n, list);
        return Registration.success(whoisOf(fresh));
    }

    @Override
    public Whois whois(String name) {
        String n = DomainNames.tryToAscii(name);
        if (n == null) {
            return null;
        }
        Entry best = null;
        synchronized (this) {
            for (Entry e : entries.values()) {
                if (DnsMessage.isUnder(n, e.name)) {
                    best = e;
                    break;
                }
            }
        }
        if (best == null) {
            return null;
        }
        if (best.subRegistrar != null && !n.equals(best.name)) {
            Whois deeper = best.subRegistrar.whois(n);
            if (deeper != null) {
                return deeper;
            }
        }
        return whoisOf(best);
    }

    /** 已注册的全部名字 */
    public synchronized List<Whois> list() {
        List<Whois> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            out.add(whoisOf(e));
        }
        return out;
    }

    private Whois whoisOf(Entry e) {
        return new Whois(e.name, this, e.owner, e.registrarIdentity, e.subRegistrar, e.servers, e.registeredAt);
    }

    // ---- 存档 ----

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JsonObject o = new JsonParser().parse(text).getAsJsonObject();
            for (JsonElement el : o.getAsJsonArray("names")) {
                JsonObject x = el.getAsJsonObject();
                String n = x.get("name").getAsString();
                List<Ipv6Address> servers = new ArrayList<>();
                for (JsonElement s : x.getAsJsonArray("servers")) {
                    servers.add(Ipv6Address.parse(s.getAsString()));
                }
                entries.put(n, new Entry(n, x.get("owner").getAsString(), x.get("registrar").getAsBoolean(), null,
                        DnsServer.checkServers(servers, false), x.get("registered").getAsLong()));
            }
        } catch (IOException | RuntimeException e) {
            entries.clear();
            File bad = new File(file.getPath() + ".bad-" + clock.getAsLong());
            boolean moved = file.renameTo(bad);
            LOG.error("游戏内网络：注册局 .{} 的注册信息 {} 读不了，{}，从空的开始：{}", zone, file,
                    moved ? "改名成 " + bad.getName() + " 留着" : "也改不了名", e.toString());
        }
    }

    private void save() throws IOException {
        JsonArray names = new JsonArray();
        for (Entry e : entries.values()) {
            JsonObject x = new JsonObject();
            x.addProperty("name", e.name);
            x.addProperty("owner", e.owner);
            x.addProperty("registrar", e.registrarIdentity);
            JsonArray s = new JsonArray();
            for (Ipv6Address a : e.servers) {
                s.add(new JsonPrimitive(a.toString()));
            }
            x.add("servers", s);
            x.addProperty("registered", e.registeredAt);
            names.add(x);
        }
        JsonObject o = new JsonObject();
        o.addProperty("zone", zone);
        o.add("names", names);
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("建不了目录 " + dir);
        }
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
