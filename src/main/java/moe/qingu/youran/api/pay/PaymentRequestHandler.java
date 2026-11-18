package moe.qingu.youran.api.pay;

import java.util.concurrent.CompletableFuture;

/**
 * 处理付款请求（{@link PaymentRequest}）的一方，可选，用 {@link PayApi#setRequestHandler} 按模组 ID 登记：
 * 虚拟账户 {@code 模组 ID:名字} 的请求交给那个模组的处理者。玩家账户的请求由 YouranMod 自己处理，不能登记。
 * <p>
 * 请求只是通知（服主 11-18）：没有登记处理者时什么都不发生，付款页写“等这个账户的主人确认”，收款单照旧开着（还能付、能取消、照常过期），
 * 以后谁主动调 {@link PayApi#pay} 都能付（例如清不听请求，拿到单号后用自己插件的管理接口付）。
 * <p>
 * 在服务端主线程上调用；同意付款就在主线程上调 {@link PayApi#pay}（调用就是授权），可以马上付，也可以先去问人、以后再付。
 */
public interface PaymentRequestHandler {
    CompletableFuture<Reply> handle(PaymentRequest request);

    /** 处理的结果：付款页照它显示 */
    final class Reply {
        public enum Kind {
            /** 付了（{@link #paymentId}） */
            PAID,
            /** 还没定，以后由处理者决定（{@link #message} 写在付款页上） */
            PENDING,
            /** 不付（{@link #message} 是原因） */
            REFUSED,
            /** 把浏览器跳到 {@link #url}（http 或 https）接着处理 */
            REDIRECT
        }

        public final Kind kind;
        public final String message;
        public final String paymentId;
        public final String url;

        private Reply(Kind kind, String message, String paymentId, String url) {
            this.kind = kind;
            this.message = message;
            this.paymentId = paymentId;
            this.url = url;
        }

        public static Reply paid(PaymentInfo payment) {
            return new Reply(Kind.PAID, null, payment.id, null);
        }

        public static Reply pending(String message) {
            return new Reply(Kind.PENDING, message, null, null);
        }

        public static Reply refused(String reason) {
            return new Reply(Kind.REFUSED, reason, null, null);
        }

        public static Reply redirect(String url) {
            return new Reply(Kind.REDIRECT, null, null, url);
        }

        public static CompletableFuture<Reply> refusedNow(String reason) {
            return CompletableFuture.completedFuture(refused(reason));
        }
    }
}
