package moe.qingu.youran.server.net.pay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * pay.yr 的账本文件（世界存档目录下 {@code youranmod/pay/}）：先写日志、再改内存（write-ahead）。
 * <ul>
 * <li>{@code journal.log}：一行一条，{@code <序号> <CRC32> <JSON>}，每条写完都 {@code fsync}。读的时候遇到第一条坏的（写到一半崩了）就停，
 * 把它和后面的内容挪到 {@code journal.bad-<时间>}，日志截到最后一条好的。</li>
 * <li>{@code state.json}：快照（带写它时的序号）。写临时文件、{@code fsync}、原子改名、再清空日志；中途崩了，下次读快照后
 * 只重放序号更大的日志，不会重复。</li>
 * </ul>
 * 不是线程安全的，由 {@link Ledger} 加锁。
 */
final class PayStore {
    static final String JOURNAL = "journal.log";
    static final String STATE = "state.json";

    private final Path dir;
    private FileChannel journal;
    private long seq;
    private int entriesSinceSnapshot;

    /** 读出来的东西 */
    static final class Loaded {
        /** 快照；没有为 null */
        final JsonObject snapshot;
        /** 快照之后的日志（按顺序） */
        final List<JsonObject> entries;
        /** 日志里坏掉、挪走的字节数（0 为没有） */
        final long badBytes;

        Loaded(JsonObject snapshot, List<JsonObject> entries, long badBytes) {
            this.snapshot = snapshot;
            this.entries = entries;
            this.badBytes = badBytes;
        }
    }

    /** 测试用：下一次 {@link #append} 时出的事（只出一次） */
    enum Fault {
        NONE,
        /** 写不进去（磁盘满）：什么都没写，抛 IOException */
        FAIL_BEFORE_WRITE,
        /** 写了半行就死了（抛 {@link Crash}，不截回去） */
        CRASH_HALF_WRITTEN,
        /** 写完、fsync 以后，返回之前死了（抛 {@link Crash}：内存没改，盘上有这条） */
        CRASH_AFTER_FSYNC
    }

    /** 测试用：模拟进程在这里死掉 */
    static final class Crash extends RuntimeException {
        Crash(String m) {
            super(m);
        }
    }

    volatile Fault fault = Fault.NONE;

    PayStore(Path dir) {
        this.dir = dir;
    }

    Path dir() {
        return dir;
    }

    /** 打开：读快照与日志，日志尾巴坏了就截掉 */
    Loaded open() throws IOException {
        Files.createDirectories(dir);
        JsonObject snap = null;
        Path state = dir.resolve(STATE);
        if (Files.exists(state)) {
            snap = new JsonParser().parse(new String(Files.readAllBytes(state), StandardCharsets.UTF_8)).getAsJsonObject();
            seq = snap.get("seq").getAsLong();
        }
        long snapSeq = seq;
        Path jp = dir.resolve(JOURNAL);
        List<JsonObject> entries = new ArrayList<>();
        long good = 0;
        long bad = 0;
        if (Files.exists(jp)) {
            byte[] all = Files.readAllBytes(jp);
            int pos = 0;
            while (pos < all.length) {
                int nl = indexOf(all, (byte) '\n', pos);
                if (nl < 0) {
                    break;
                }
                JsonObject e = parseLine(new String(all, pos, nl - pos, StandardCharsets.UTF_8));
                if (e == null) {
                    break;
                }
                long s = e.get("seq").getAsLong();
                if (s > snapSeq) {
                    if (s != seq + 1) {
                        // 序号断了：当作坏尾巴
                        break;
                    }
                    entries.add(e);
                    seq = s;
                }
                pos = nl + 1;
                good = pos;
            }
            if (good < all.length) {
                bad = all.length - good;
                Path badFile = dir.resolve("journal.bad-" + System.currentTimeMillis());
                Files.write(badFile, java.util.Arrays.copyOfRange(all, (int) good, all.length));
            }
        }
        journal = FileChannel.open(jp, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        journal.truncate(good);
        journal.position(good);
        journal.force(true);
        entriesSinceSnapshot = entries.size();
        return new Loaded(snap, entries, bad);
    }

    /** 一行：序号、CRC32、JSON；不对返回 null */
    static JsonObject parseLine(String line) {
        int a = line.indexOf(' ');
        int b = a < 0 ? -1 : line.indexOf(' ', a + 1);
        if (b < 0) {
            return null;
        }
        try {
            long s = Long.parseLong(line.substring(0, a));
            String json = line.substring(b + 1);
            if (!crc(json).equals(line.substring(a + 1, b))) {
                return null;
            }
            JsonObject o = new JsonParser().parse(json).getAsJsonObject();
            if (!o.has("seq") || o.get("seq").getAsLong() != s) {
                return null;
            }
            return o;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String crc(String json) {
        CRC32 c = new CRC32();
        c.update(json.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", c.getValue());
    }

    /** 写一条（给它编号），fsync 以后才返回；写不进去抛 IOException（调用方不改内存） */
    long append(JsonObject entry) throws IOException {
        long s = seq + 1;
        entry.addProperty("seq", s);
        String json = entry.toString();
        byte[] line = (s + " " + crc(json) + " " + json + "\n").getBytes(StandardCharsets.UTF_8);
        long start = journal.position();
        Fault f = fault;
        fault = Fault.NONE;
        try {
            if (f == Fault.FAIL_BEFORE_WRITE) {
                throw new IOException("测试：写不进去");
            }
            ByteBuffer buf = ByteBuffer.wrap(line, 0, f == Fault.CRASH_HALF_WRITTEN ? line.length / 2 : line.length);
            while (buf.hasRemaining()) {
                journal.write(buf);
            }
            journal.force(false);
            if (f != Fault.NONE) {
                throw new Crash("测试：" + f);
            }
        } catch (IOException e) {
            // 写到一半：截回去，免得后面的接在半行上
            try {
                journal.truncate(start);
                journal.position(start);
            } catch (IOException ignored) {
                // 下次打开时按坏尾巴处理
            }
            entry.remove("seq");
            throw e;
        }
        seq = s;
        entriesSinceSnapshot++;
        return s;
    }

    long seq() {
        return seq;
    }

    int entriesSinceSnapshot() {
        return entriesSinceSnapshot;
    }

    /** 写快照（{@code state} 里会加上当前序号），然后清空日志 */
    void snapshot(JsonObject state) throws IOException {
        state.addProperty("seq", seq);
        byte[] data = state.toString().getBytes(StandardCharsets.UTF_8);
        Path tmp = dir.resolve(STATE + ".tmp");
        try (FileChannel c = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) {
                c.write(buf);
            }
            c.force(true);
        }
        Files.move(tmp, dir.resolve(STATE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDir();
        journal.truncate(0);
        journal.position(0);
        journal.force(true);
        entriesSinceSnapshot = 0;
    }

    private void forceDir() {
        try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) {
            d.force(true);
        } catch (IOException | RuntimeException ignored) {
            // 有的系统不能打开目录 fsync；改名本身是原子的
        }
    }

    void close() {
        if (journal != null) {
            try {
                journal.close();
            } catch (IOException ignored) {
                // 关服时
            }
            journal = null;
        }
    }

    private static int indexOf(byte[] a, byte b, int from) {
        for (int i = from; i < a.length; i++) {
            if (a[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /** 测试用：日志文件的全部内容 */
    byte[] journalBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Files.readAllBytes(dir.resolve(JOURNAL)));
        return out.toByteArray();
    }
}
