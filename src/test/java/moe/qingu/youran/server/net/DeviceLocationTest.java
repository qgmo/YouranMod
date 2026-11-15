package moe.qingu.youran.server.net;

import moe.qingu.youran.api.network.DefaultDimensionNetwork;
import moe.qingu.youran.api.network.DeviceHandle;
import moe.qingu.youran.api.network.DeviceLocation;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkLayout;
import net.minecraft.entity.player.EntityPlayerMP;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 按地址查设备位置（给 YouranAuth 的 portal.yr 反代用）：按地址结构算坐标，再查设备表 */
class DeviceLocationTest {
    final NetworkLayout layout = NetworkLayout.fromGlobalId(0x0102030405L);
    final AddressBook book;
    final DeviceTable devices;

    DeviceLocationTest() {
        Map<Integer, AddressBook.Dim> dims = new LinkedHashMap<>();
        dims.put(0, new AddressBook.Dim(0, 0, DefaultDimensionNetwork.INSTANCE));
        dims.put(-1, new AddressBook.Dim(-1, 1, DefaultDimensionNetwork.INSTANCE));
        dims.put(7, new AddressBook.Dim(7, 5, DefaultDimensionNetwork.INSTANCE));
        book = new AddressBook(layout, dims, Collections.singletonMap("youranmod:main", 0L),
                Collections.singletonMap("youranmod:router", layout.hostAddress(0, 1)));
        devices = new DeviceTable(book, new DeviceTable.Hooks() {
            @Override
            public boolean isLoaded(int dimension, int x, int y, int z) {
                return true;
            }

            @Override
            public Ipv6Address resolverAddress() {
                return null;
            }

            @Override
            public Ipv6Address ntpAddress() {
                return null;
            }

            @Override
            public EntityPlayerMP player(UUID id) {
                return null;
            }

            @Override
            public void fromDevice(DeviceTable.Device d, byte[] packet) {
            }
        });
    }

    @Test
    void structureGivesTheCoordinates() {
        assertEquals(new DeviceLocation(0, 12, 64, -30), book.locate(book.coordinateAddress(0, 12, 64, -30)));
        assertEquals(new DeviceLocation(-1, -524288, 0, 524287), book.locate(book.coordinateAddress(-1, -524288, 0, 524287)));
        assertEquals(new DeviceLocation(7, 1, 255, -1), book.locate(book.coordinateAddress(7, 1, 255, -1)));
        assertEquals("7 1 255 -1", book.locate(book.coordinateAddress(7, 1, 255, -1)).toString());
        Ipv6Address a = book.coordinateAddress(0, 12, 64, -30);
        // 同一格的 1–255 号（以后的路由器）不是设备地址
        assertNull(book.locate(new Ipv6Address(a.high(), a.low() | 5)));
        // 没分配的维度序号、机房、玩家临时地址、别的 /48
        assertNull(book.locate(layout.coordinateAddress(9, DefaultDimensionNetwork.INSTANCE.encode(0, 0, 0, 0))));
        assertNull(book.locate(layout.hostAddress(0, 0x53)));
        assertNull(book.locate(layout.playerAddress(1234)));
        assertNull(book.locate(Ipv6Address.parse("fd99::1")));
        assertNull(book.locate(null));
    }

    @Test
    void onlyRegisteredDevices() {
        Ipv6Address a = book.coordinateAddress(0, 3, 70, 9);
        assertNull(devices.locate(book, a), "那一格上没有设备");
        DeviceHandle d = devices.register(0, 3, 70, 9);
        assertEquals(a, d.address());
        assertEquals(new DeviceLocation(0, 3, 70, 9), devices.locate(book, a));
        d.remove();
        assertNull(devices.locate(book, a), "拿掉以后就不是了");
    }
}
