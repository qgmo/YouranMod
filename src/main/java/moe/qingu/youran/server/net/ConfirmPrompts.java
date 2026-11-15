package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 系统确认框的服务端账本（只在主线程上用；纯逻辑，时间、玩家在不在线、发给客户端都经 {@link Env}，单元测试换成假的）。
 * 每个玩家同一时间最多一个；到时限按拒绝；操作者变了作废；只收被问的玩家、题号对得上的回应。
 */
final class ConfirmPrompts {
    /** 外面的东西 */
    interface Env {
        long nowMillis();

        boolean isOnline(UUID player);

        /** 叫客户端弹框 */
        void show(UUID player, long id, ConfirmRequest request, int secondsLeft);

        /** 叫客户端把框关掉（服务端这边已经结束：超时、作废） */
        void close(UUID player, long id);
    }

    static final class Pending {
        final long id;
        final UUID player;
        final String name;
        final ConfirmRequest request;
        final long deadline;
        /** 现在该被问的人（设备的当前操作者，或玩家自己；不在了为 null） */
        final Supplier<UUID> operatorNow;
        final CompletableFuture<ConfirmResult> future = new CompletableFuture<>();

        Pending(long id, UUID player, String name, ConfirmRequest request, long deadline, Supplier<UUID> operatorNow) {
            this.id = id;
            this.player = player;
            this.name = name;
            this.request = request;
            this.deadline = deadline;
            this.operatorNow = operatorNow;
        }
    }

    private final Env env;
    private final Random random;
    private final Map<UUID, Pending> byPlayer = new HashMap<>();

    ConfirmPrompts(Env env, Random random) {
        this.env = env;
        this.random = random;
    }

    /**
     * 问 {@code player}（现在的操作者）。{@code operatorNow} 每 tick 查一次：不再是他时作废。
     */
    CompletableFuture<ConfirmResult> open(UUID player, String name, Supplier<UUID> operatorNow, ConfirmRequest request) {
        if (player == null || !env.isOnline(player) || !player.equals(operatorNow.get())) {
            return CompletableFuture.completedFuture(new ConfirmResult(ConfirmResult.Outcome.NO_OPERATOR, null, null));
        }
        if (byPlayer.containsKey(player)) {
            return CompletableFuture.completedFuture(new ConfirmResult(ConfirmResult.Outcome.BUSY, player, name));
        }
        long id;
        do {
            id = random.nextLong();
        } while (id == 0);
        Pending p = new Pending(id, player, name, request, env.nowMillis() + request.timeoutSeconds * 1000L, operatorNow);
        byPlayer.put(player, p);
        env.show(player, id, request, request.timeoutSeconds);
        return p.future;
    }

    /** 每 tick：超时、操作者变了、下线的结束掉 */
    void tick() {
        if (byPlayer.isEmpty()) {
            return;
        }
        long now = env.nowMillis();
        for (Pending p : new ArrayList<>(byPlayer.values())) {
            if (!stillValid(p)) {
                finish(p, ConfirmResult.Outcome.OPERATOR_CHANGED, true);
            } else if (now >= p.deadline) {
                finish(p, ConfirmResult.Outcome.TIMEOUT, true);
            }
        }
    }

    private boolean stillValid(Pending p) {
        return env.isOnline(p.player) && p.player.equals(p.operatorNow.get());
    }

    /**
     * 客户端的回应。只认被问的这个玩家、题号对得上的；对不上（别人的、过时的、伪造的）返回 false，什么也不改。
     * 回应来时操作者已经变了（还没等到下一 tick）按作废算。
     */
    boolean answer(UUID from, long id, boolean approve) {
        Pending p = from == null ? null : byPlayer.get(from);
        if (p == null || p.id != id || id == 0) {
            return false;
        }
        if (!stillValid(p)) {
            finish(p, ConfirmResult.Outcome.OPERATOR_CHANGED, true);
        } else if (env.nowMillis() >= p.deadline) {
            finish(p, ConfirmResult.Outcome.TIMEOUT, true);
        } else {
            finish(p, approve ? ConfirmResult.Outcome.APPROVED : ConfirmResult.Outcome.DENIED, false);
        }
        return true;
    }

    /** 玩家下线 */
    void playerLeft(UUID player) {
        Pending p = byPlayer.get(player);
        if (p != null) {
            finish(p, ConfirmResult.Outcome.OPERATOR_CHANGED, false);
        }
    }

    /** 关服：都按“不可用”结束 */
    void clear() {
        for (Pending p : new ArrayList<>(byPlayer.values())) {
            finish(p, ConfirmResult.Outcome.UNAVAILABLE, true);
        }
    }

    private void finish(Pending p, ConfirmResult.Outcome outcome, boolean tellClient) {
        if (byPlayer.get(p.player) == p) {
            byPlayer.remove(p.player);
        }
        if (tellClient) {
            env.close(p.player, p.id);
        }
        p.future.complete(new ConfirmResult(outcome, p.player, p.name));
    }

    /** 测试与命令用 */
    Pending pending(UUID player) {
        return byPlayer.get(player);
    }

    List<Pending> all() {
        return new ArrayList<>(byPlayer.values());
    }
}
