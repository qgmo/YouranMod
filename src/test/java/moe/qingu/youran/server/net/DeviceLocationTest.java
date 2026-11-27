package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import moe.qingu.youran.api.network.stack.ManualEventLoop;
import moe.qingu.youran.network.NetworkHooks;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 原来测“按地址查设备位置”（{@code deviceLocation}，按地址结构解出坐标）。服主 11-27：网络层不按地址解码坐标（以后可能有非坐标编址的网络），
 * 坐标由电脑模组按自己方块的位置填进位置证明。这里改测留下的方向：一格 → 它的 0 号地址（插网卡时网络给的就是它），以及插着的网卡按格子查。
 */
class DeviceLocationTest {
    final NetworkLayout layout = NetworkLayout.fromGlobalId(0x0102030405L);
    final AddressBook book;
    final NicTable nics;

    DeviceLocationTest() {
        Map<Integer, AddressBook.Dim> dims = new LinkedHashMap<>();
        dims.put(0, new AddressBook.Dim(0, 0, DefaultDimensionNetwork.INSTANCE));
        dims.put(-1, new AddressBook.Dim(-1, 1, DefaultDimensionNetwork.INSTANCE));
        dims.put(7, new AddressBook.Dim(7, 5, DefaultDimensionNetwork.INSTANCE));
        book = new AddressBook(layout, dims, Collections.singletonMap("youranmod:main", 0L),
                Collections.singletonMap("youranmod:router", layout.hostAddress(0, 1)));
        nics = new NicTable(book::coordinateAddress, new ManualEventLoop(), new RejectLog(10_000), (port, p) -> {
        }, 20, 20 * 1280, p -> {
        });
    }

    @Test
    void coordinatesGiveTheAddress() {
        assertEquals(layout.coordinateAddress(0, DefaultDimensionNetwork.INSTANCE.encode(12, 64, -30, 0)), book.coordinateAddress(0, 12, 64, -30));
        assertEquals(layout.coordinateAddress(1, DefaultDimensionNetwork.INSTANCE.encode(-524288, 0, 524287, 0)), book.coordinateAddress(-1, -524288, 0, 524287));
        assertEquals(layout.coordinateAddress(5, DefaultDimensionNetwork.INSTANCE.encode(1, 255, -1, 0)), book.coordinateAddress(7, 1, 255, -1));
        assertNotEquals(book.coordinateAddress(0, 1, 2, 3), book.coordinateAddress(-1, 1, 2, 3), "不同维度不同地址");
        // 超出范围、没有全局 Wi-Fi 的维度没有地址
        assertNull(book.coordinateAddress(0, 524288, 64, 0));
        assertNull(book.coordinateAddress(0, 0, 256, 0));
        assertNull(book.coordinateAddress(9, 0, 64, 0));
    }

    @Test
    void onlyPluggedNics() {
        Ipv6Address a = book.coordinateAddress(0, 3, 70, 9);
        assertNull(nics.at(0, 3, 70, 9), "那一格没插网卡");
        TestNic nic = new TestNic();
        NicTable.Port p = nics.plug(nic, 0, 3, 70, 9);
        NetworkHooks.nicAccess().attach(nic, p);
        assertEquals(a, nic.address());
        assertSame(nic, nics.at(0, 3, 70, 9).nic);
        nics.unplug(p);
        assertNull(nics.at(0, 3, 70, 9), "拔掉以后就没有了");
    }
}
