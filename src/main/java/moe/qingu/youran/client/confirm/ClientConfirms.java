package moe.qingu.youran.client.confirm;

import moe.qingu.youran.YouranMod;
import moe.qingu.youran.api.network.ConfirmRequest;
import moe.qingu.youran.api.network.ConfirmText;
import moe.qingu.youran.net.ConfirmMessages;
import moe.qingu.youran.net.YouranNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.List;

/**
 * 客户端的系统确认框（{@code api.network.SystemConfirm}）：服务端叫弹框时，画在当前界面（如家具电脑、SCP 电脑的界面）<b>上面</b>，
 * 不是电脑屏幕里的东西；开着时拦下所有鼠标、键盘输入，下面的界面（含网页、电脑里的程序）收不到。没开界面时开一个只有这个框的界面。
 * 按钮出现 {@value ConfirmDialog#ARM_MILLIS} 毫秒后才能点（防误点）；Esc 等于拒绝。只在客户端主线程上改状态。
 */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YouranMod.MODID)
public final class ClientConfirms {
    private static ConfirmDialog current;

    private ClientConfirms() {
    }

    /** Netty 线程上收到：转到主线程弹框（新的顶掉旧的；服务端保证同一时间只有一个） */
    public static void show(long id, ConfirmText title, String site, List<ConfirmText> items, int seconds, ConfirmRequest.Purpose purpose) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            current = new ConfirmDialog(id, title, site, items, seconds, purpose, System.currentTimeMillis());
            ensureHost(mc);
        });
    }

    /** 服务端说作废了 */
    public static void close(long id) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (current != null && current.id == id) {
                dismiss();
            }
        });
    }

    /**
     * 给别的模组的客户端：现在有没有系统确认框开着（单独一个界面，或盖在别的界面上）。开着时鼠标、键盘都归这个框；
     * 模拟输入的东西（例如 YouranSCP 的 SCP-6468 连点）据此不去点它，也不去点它下面的界面。只在客户端主线程上调用。
     */
    public static boolean isShowing() {
        return current != null;
    }

    /** 现在开着的框（测试、界面用）；没有为 null */
    public static ConfirmDialog current() {
        return current;
    }

    static void answer(ConfirmDialog d, boolean approve) {
        if (d != current) {
            return;
        }
        YouranNetwork.CHANNEL.sendToServer(new ConfirmMessages.Answer(d.id, approve));
        dismiss();
    }

    private static void dismiss() {
        current = null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof GuiConfirmHost) {
            mc.displayGuiScreen(null);
        }
    }

    /** 没开界面时开一个只放这个框的界面（好拦输入、放开鼠标） */
    private static void ensureHost(Minecraft mc) {
        if (current != null && mc.currentScreen == null && mc.world != null) {
            mc.displayGuiScreen(new GuiConfirmHost());
        }
    }

    private static boolean overlaying(GuiScreen screen) {
        return current != null && screen != null && !(screen instanceof GuiConfirmHost);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (overlaying(event.getGui())) {
            current.draw(Minecraft.getMinecraft(), event.getGui().width, event.getGui().height, event.getMouseX(), event.getMouseY());
        }
    }

    /** 盖在别的界面上时：鼠标都归这个框 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        GuiScreen gui = event.getGui();
        if (!overlaying(gui)) {
            return;
        }
        event.setCanceled(true);
        if (Mouse.getEventButtonState() && Mouse.getEventButton() == 0) {
            Minecraft mc = Minecraft.getMinecraft();
            int x = Mouse.getEventX() * gui.width / mc.displayWidth;
            int y = gui.height - Mouse.getEventY() * gui.height / mc.displayHeight - 1;
            ConfirmDialog d = current;
            Boolean choice = d.click(mc, gui.width, gui.height, x, y);
            if (choice != null) {
                answer(d, choice);
            }
        }
    }

    /** 盖在别的界面上时：键盘都归这个框（Esc 等于拒绝） */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyboard(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!overlaying(event.getGui())) {
            return;
        }
        event.setCanceled(true);
        if (Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_ESCAPE) {
            answer(current, false);
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || current == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            current = null;
            return;
        }
        if (current.expired(System.currentTimeMillis())) {
            // 服务端也会按超时结束；这里只把框拿掉
            dismiss();
            return;
        }
        ensureHost(mc);
    }

    @SubscribeEvent
    public static void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(() -> current = null);
    }
}
