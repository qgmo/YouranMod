package moe.qingu.youran.server.net;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 地址分配表（随存档保存，见 {@link NetworkSaveData}）：/48 的 40 位全局 ID、维度序号、机房序号、主机号。
 * 纯逻辑，不碰 Minecraft；开服时在主线程上算一次，网络线程只读算好的结果。
 * <p>
 * 规则（服主 11-14）：用“模组 ID + 名字”登记，第一次按顺序分一个空闲的（最小的空号），同一个名字以后拿到的不变；
 * 指定了具体编号的先处理，和别的名字已占的冲突时拒绝（记一条问题）。这次没登记的名字也留着它的编号（以后再登记还是它）。
 */
public final class AllocationTable {
    /** -1 表示还没有（第一次开服时生成） */
    public long globalId = -1;
    /** 维度 ID → 维度序号 */
    public final Map<Integer, Integer> dimensions = new LinkedHashMap<>();
    /** 机房登记名 → 机房序号 */
    public final Map<String, Long> datacenters = new LinkedHashMap<>();
    /** 主机登记名 → 所在机房与主机号 */
    public final Map<String, Host> hosts = new LinkedHashMap<>();
    /** 改过（要存盘） */
    public boolean dirty;

    public static final class Host {
        public final String datacenter;
        public final long hostPart;

        public Host(String datacenter, long hostPart) {
            this.datacenter = datacenter;
            this.hostPart = hostPart;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Host && ((Host) o).datacenter.equals(datacenter) && ((Host) o).hostPart == hostPart;
        }

        @Override
        public int hashCode() {
            return datacenter.hashCode() * 31 + Long.hashCode(hostPart);
        }
    }

    /** 一条登记（机房或主机） */
    public static final class Request {
        public final String key;
        /** 指定的编号；-1 表示按顺序分 */
        public final long fixed;
        /** 主机所在的机房（机房的登记为 null） */
        public final String datacenter;

        public Request(String key, long fixed, String datacenter) {
            this.key = key;
            this.fixed = fixed;
            this.datacenter = datacenter;
        }
    }

    // ---- 维度 ----

    /**
     * 按配置清单的顺序给有全局 Wi-Fi 的维度分序号（0–255，最小的空号）。返回这次联网的维度 → 序号；分不到的写进 problems。
     */
    public Map<Integer, Integer> assignDimensions(int[] wifi, List<String> problems) {
        Map<Integer, Integer> active = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>(dimensions.values());
        for (int dim : wifi) {
            if (active.containsKey(dim)) {
                problems.add("维度 " + dim + " 在清单里写了两次");
                continue;
            }
            Integer idx = dimensions.get(dim);
            if (idx == null) {
                idx = lowestFree(used, 0, 255);
                if (idx < 0) {
                    problems.add("维度序号（256 个）已经分完，维度 " + dim + " 没有地址");
                    continue;
                }
                dimensions.put(dim, idx);
                used.add(idx);
                dirty = true;
            }
            active.put(dim, idx);
        }
        return active;
    }

    // ---- 机房 ----

    /** 分机房序号；返回这次登记成功的 名字 → 序号 */
    public Map<String, Long> assignDatacenters(List<Request> requests, List<String> problems) {
        Map<String, Long> result = new LinkedHashMap<>();
        Map<Long, String> owners = new TreeMap<>();
        for (Map.Entry<String, Long> e : datacenters.entrySet()) {
            owners.put(e.getValue(), e.getKey());
        }
        Set<String> seen = new HashSet<>();
        // 先处理指定了编号的
        for (Request r : requests) {
            if (r.fixed < 0) {
                continue;
            }
            if (!seen.add(r.key)) {
                problems.add("机房 " + r.key + " 登记了两次，后一次不算");
                continue;
            }
            String owner = owners.get(r.fixed);
            if (owner != null && !owner.equals(r.key)) {
                problems.add("机房 " + r.key + " 指定的序号 " + r.fixed + " 已经分给了 " + owner + "，拒绝");
                continue;
            }
            Long old = datacenters.get(r.key);
            if (old != null && old != r.fixed) {
                problems.add("机房 " + r.key + " 的序号从 " + old + " 改成指定的 " + r.fixed);
                owners.remove(old);
            }
            if (old == null || old != r.fixed) {
                datacenters.put(r.key, r.fixed);
                owners.put(r.fixed, r.key);
                dirty = true;
            }
            result.put(r.key, r.fixed);
        }
        for (Request r : requests) {
            if (r.fixed >= 0) {
                continue;
            }
            if (!seen.add(r.key)) {
                problems.add("机房 " + r.key + " 登记了两次，后一次不算");
                continue;
            }
            Long idx = datacenters.get(r.key);
            if (idx == null) {
                idx = lowestFree(owners.keySet(), 0, 0xFFFFFFFFL);
                if (idx < 0) {
                    problems.add("机房序号已经分完，" + r.key + " 没有分到");
                    continue;
                }
                datacenters.put(r.key, idx);
                owners.put(idx, r.key);
                dirty = true;
            }
            result.put(r.key, idx);
        }
        return result;
    }

    // ---- 主机 ----

    /**
     * 分主机号（每个机房里 1–0xFFFFFFFF；0 不用：子网路由器任播地址）。{@code activeDatacenters} 是这次登记成功的机房；
     * 登记在别的机房里的主机拒绝。返回这次登记成功的 名字 → 主机。
     */
    public Map<String, Host> assignHosts(List<Request> requests, Set<String> activeDatacenters, List<String> problems) {
        Map<String, Host> result = new LinkedHashMap<>();
        Map<String, Map<Long, String>> owners = new LinkedHashMap<>();
        for (Map.Entry<String, Host> e : hosts.entrySet()) {
            owners.computeIfAbsent(e.getValue().datacenter, k -> new TreeMap<>()).put(e.getValue().hostPart, e.getKey());
        }
        Set<String> seen = new HashSet<>();
        for (int pass = 0; pass < 2; pass++) {
            for (Request r : requests) {
                boolean fixed = r.fixed >= 0;
                if (fixed != (pass == 0)) {
                    continue;
                }
                if (!seen.add(r.key)) {
                    problems.add("主机 " + r.key + " 登记了两次，后一次不算");
                    continue;
                }
                if (!activeDatacenters.contains(r.datacenter)) {
                    problems.add("主机 " + r.key + " 所在的机房 " + r.datacenter + " 没有登记或没分到序号，拒绝");
                    continue;
                }
                Map<Long, String> dcOwners = owners.computeIfAbsent(r.datacenter, k -> new TreeMap<>());
                Host old = hosts.get(r.key);
                Host want;
                if (fixed) {
                    String owner = dcOwners.get(r.fixed);
                    if (owner != null && !owner.equals(r.key)) {
                        problems.add("主机 " + r.key + " 指定的地址 ::" + Long.toHexString(r.fixed) + "（机房 " + r.datacenter
                                + "）已经分给了 " + owner + "，拒绝");
                        continue;
                    }
                    want = new Host(r.datacenter, r.fixed);
                } else if (old != null && old.datacenter.equals(r.datacenter)) {
                    want = old;
                } else {
                    long part = lowestFree(dcOwners.keySet(), 1, 0xFFFFFFFFL);
                    if (part < 0) {
                        problems.add("机房 " + r.datacenter + " 的地址已经分完，" + r.key + " 没有分到");
                        continue;
                    }
                    want = new Host(r.datacenter, part);
                }
                if (old != null && !old.equals(want)) {
                    problems.add("主机 " + r.key + " 的地址从机房 " + old.datacenter + " ::" + Long.toHexString(old.hostPart)
                            + " 改成 " + want.datacenter + " ::" + Long.toHexString(want.hostPart));
                    Map<Long, String> o = owners.get(old.datacenter);
                    if (o != null) {
                        o.remove(old.hostPart);
                    }
                }
                if (!want.equals(old)) {
                    hosts.put(r.key, want);
                    dirty = true;
                }
                dcOwners.put(want.hostPart, r.key);
                result.put(r.key, want);
            }
        }
        return result;
    }

    /** {@code used} 里没有的、在 [min, max] 里最小的数；没有返回 -1 */
    static int lowestFree(Set<Integer> used, int min, int max) {
        for (int i = min; i <= max; i++) {
            if (!used.contains(i)) {
                return i;
            }
        }
        return -1;
    }

    static long lowestFree(Set<Long> used, long min, long max) {
        // used 是 TreeMap 的 keySet（有序）时一遍就够；别的集合也对，只是慢一点
        long candidate = min;
        for (long v : used instanceof java.util.SortedSet ? used : new java.util.TreeSet<>(used)) {
            if (v < candidate) {
                continue;
            }
            if (v > candidate) {
                break;
            }
            candidate++;
        }
        return candidate <= max ? candidate : -1;
    }

    public Map<Integer, Integer> dimensionsView() {
        return Collections.unmodifiableMap(dimensions);
    }
}
