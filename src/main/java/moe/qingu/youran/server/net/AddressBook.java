package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DimensionNetwork;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 开服时由分配表算好的地址（不可变，两个线程都读） */
final class AddressBook {
    final NetworkLayout layout;
    /** 维度 ID → 序号与划分 */
    final Map<Integer, Dim> byDimension;
    /** 维度序号 → 同上 */
    final Map<Integer, Dim> byIndex;
    /** 机房登记名 → 序号（这次登记成功的） */
    final Map<String, Long> datacenters;
    /** 主机登记名 → 地址（这次登记成功的） */
    final Map<String, Ipv6Address> hosts;

    static final class Dim {
        final int dimension;
        final int index;
        final DimensionNetwork network;

        Dim(int dimension, int index, DimensionNetwork network) {
            this.dimension = dimension;
            this.index = index;
            this.network = network;
        }
    }

    AddressBook(NetworkLayout layout, Map<Integer, Dim> byDimension, Map<String, Long> datacenters, Map<String, Ipv6Address> hosts) {
        this.layout = layout;
        this.byDimension = Collections.unmodifiableMap(new LinkedHashMap<>(byDimension));
        Map<Integer, Dim> idx = new LinkedHashMap<>();
        for (Dim d : byDimension.values()) {
            idx.put(d.index, d);
        }
        this.byIndex = Collections.unmodifiableMap(idx);
        this.datacenters = Collections.unmodifiableMap(new LinkedHashMap<>(datacenters));
        this.hosts = Collections.unmodifiableMap(new LinkedHashMap<>(hosts));
    }

    /** 维度 (x, y, z) 的 0 号地址；没有为 null */
    Ipv6Address coordinateAddress(int dimension, int x, int y, int z) {
        Dim d = byDimension.get(dimension);
        if (d == null) {
            return null;
        }
        long bits = d.network.encode(x, y, z, 0);
        return bits < 0 ? null : layout.coordinateAddress(d.index, bits);
    }
}
