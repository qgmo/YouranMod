package moe.qingu.youran.api.network.dns;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一次注册的结果（注册局 {@link Registrar#register} 与顶级域登记处 {@link DnsRegistry#register} 返回）。三种状态：
 * <ul>
 * <li>{@link Status#SUCCESS 成功}：{@link #whois()} 是注册后的信息；</li>
 * <li>{@link Status#FAILURE 失败}：注册局拒绝了，{@link #reason()} 是原因；</li>
 * <li>{@link Status#PENDING 等待}：注册局有自己的流程，暂时定不下来。可以 {@link #cancel()} 取消，
 * 也可以用 {@link #whenDone} 等结果。拿到“等待”说明这个注册局不能用通用的办法随便注册。</li>
 * </ul>
 * 等待中的结果由注册局用 {@link #succeed} / {@link #fail} 定下来，定下来以后不再变。线程安全。
 */
public final class Registration {
    public enum Status {
        SUCCESS, FAILURE, PENDING
    }

    /** 取消等待时的失败原因 */
    public static final String CANCELLED = "已取消";

    private Status status;
    private Whois whois;
    private String reason;
    private final Runnable onCancel;
    private List<Consumer<Registration>> callbacks = new ArrayList<>();

    private Registration(Status status, Whois whois, String reason, Runnable onCancel) {
        this.status = status;
        this.whois = whois;
        this.reason = reason;
        this.onCancel = onCancel;
    }

    /** 成功 */
    public static Registration success(Whois whois) {
        if (whois == null) {
            throw new IllegalArgumentException("whois 不能为 null");
        }
        return new Registration(Status.SUCCESS, whois, null, null);
    }

    /** 失败（注册局拒绝） */
    public static Registration failure(String reason) {
        return new Registration(Status.FAILURE, null, reason == null ? "" : reason, null);
    }

    /**
     * 等待：注册局之后用 {@link #succeed} / {@link #fail} 定下来。{@code onCancel} 是申请人取消时注册局要做的事（撤掉申请），可以为 null；
     * 在调用 {@link #cancel()} 的线程上调用。
     */
    public static Registration pending(Runnable onCancel) {
        return new Registration(Status.PENDING, null, null, onCancel);
    }

    public synchronized Status status() {
        return status;
    }

    /** 成功时的注册信息；不是成功时为 null */
    public synchronized Whois whois() {
        return whois;
    }

    /** 失败的原因；不是失败时为 null */
    public synchronized String reason() {
        return reason;
    }

    /**
     * 注册局用：把等待中的定为成功。已经定下来（包括已取消）时什么都不做，返回 false。回调在调用这个方法的线程上调用。
     */
    public boolean succeed(Whois whois) {
        if (whois == null) {
            throw new IllegalArgumentException("whois 不能为 null");
        }
        return settle(Status.SUCCESS, whois, null, false);
    }

    /** 注册局用：把等待中的定为失败。已经定下来时返回 false */
    public boolean fail(String reason) {
        return settle(Status.FAILURE, null, reason == null ? "" : reason, false);
    }

    /**
     * 申请人用：取消等待。还在等待时定为失败（原因 {@value #CANCELLED}），通知注册局、调用回调，返回 true；已经定下来时返回 false。
     */
    public boolean cancel() {
        return settle(Status.FAILURE, null, CANCELLED, true);
    }

    /** 定下来（成功或失败）时调用 {@code callback}；已经定下来时马上在当前线程上调用 */
    public void whenDone(Consumer<Registration> callback) {
        synchronized (this) {
            if (status == Status.PENDING) {
                callbacks.add(callback);
                return;
            }
        }
        callback.accept(this);
    }

    private boolean settle(Status s, Whois w, String r, boolean cancelling) {
        List<Consumer<Registration>> run;
        synchronized (this) {
            if (status != Status.PENDING) {
                return false;
            }
            status = s;
            whois = w;
            reason = r;
            run = callbacks;
            callbacks = null;
        }
        if (cancelling && onCancel != null) {
            onCancel.run();
        }
        for (Consumer<Registration> c : run) {
            c.accept(this);
        }
        return true;
    }

    /** 写日志用：“成功 …”“失败：原因”“等待” */
    @Override
    public synchronized String toString() {
        switch (status) {
            case SUCCESS:
                return "成功（" + whois + "）";
            case FAILURE:
                return "失败：" + reason;
            default:
                return "等待注册局处理";
        }
    }
}
