package moe.qingu.youran.server.net.pay;

import moe.qingu.youran.api.pay.PaymentRequest;
import moe.qingu.youran.api.pay.PaymentRequestHandler;
import moe.qingu.youran.api.pay.PaymentRequestHandler.Reply;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 付款请求按账户类型分给处理者（服主 11-18）：玩家账户给 YouranMod 自己的处理者（付款页的 id.yr 确认框，{@link PayService}），
 * 虚拟账户给它所属模组登记的 {@link PaymentRequestHandler}（{@code 模组 ID:名字} 里的模组 ID）。
 * 请求只是通知：没有处理者时什么都不做，回“等这个账户的主人确认”，收款单照旧开着，以后谁主动调付款入口都能付。主线程上调用。
 */
final class PayRequests {
    private PayRequests() {
    }

    static CompletableFuture<Reply> dispatch(PaymentRequest req, PaymentRequestHandler players, Function<String, PaymentRequestHandler> mods) {
        PaymentRequestHandler h;
        if (req.account.isPlayer()) {
            h = players;
        } else {
            String key = req.account.virtual;
            h = mods.apply(key.substring(0, key.indexOf(':')));
        }
        if (h == null) {
            return CompletableFuture.completedFuture(waiting(req));
        }
        try {
            CompletableFuture<Reply> f = h.handle(req);
            return f == null ? CompletableFuture.completedFuture(waiting(req)) : f;
        } catch (RuntimeException e) {
            Ledger.LOG.error("pay.yr：{} 的付款请求处理者出错（收款单 {} 照旧开着）", req.account, req.order.id, e);
            return CompletableFuture.completedFuture(waiting(req));
        }
    }

    /** 没有处理者（或处理者没给结果）：什么都没做 */
    static Reply waiting(PaymentRequest req) {
        return Reply.pending("这笔付款要等账户“" + (req.accountName == null ? req.account.id() : req.accountName) + "”的主人确认。"
                + "确认以前这张账单还能付。");
    }
}
