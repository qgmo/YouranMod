package moe.qingu.youran.api.pay;

/** pay.yr 拒绝了一个操作：{@link #code} 是给程序看的代号，{@link #getMessage()} 是给人看的说明（中文） */
public final class PayException extends Exception {
    private static final long serialVersionUID = 1L;

    /** 代号（也是 HTTP 接口里 {@code error} 的值） */
    public final String code;

    public PayException(String code, String message) {
        super(message);
        this.code = code;
    }
}
