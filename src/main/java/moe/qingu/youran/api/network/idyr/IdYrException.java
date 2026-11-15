package moe.qingu.youran.api.network.idyr;

/** id.yr 回了错误（{@link #code} 是它的 {@code error}，如 {@code invalid_token}、{@code insufficient_scope}），或回的看不懂 */
public final class IdYrException extends RuntimeException {
    public final int httpStatus;
    public final String code;

    public IdYrException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    /** 访问令牌失效了（过期、作废）：要重新登录 */
    public boolean tokenExpired() {
        return httpStatus == 401 || "invalid_token".equals(code);
    }

    /** 令牌没有这项能力（登录时没要、或玩家没同意）：要重新登录并要这项范围 */
    public boolean insufficientScope() {
        return "insufficient_scope".equals(code);
    }
}
