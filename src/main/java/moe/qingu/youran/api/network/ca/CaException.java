package moe.qingu.youran.api.network.ca;

/** 证书签发服务拒绝了这次调用（future 以它异常结束）。{@link #code} 说明是哪一种，{@link #getMessage()} 是给人看的原因 */
public final class CaException extends Exception {
    public enum Code {
        /** 服务没开（服务器没配中间 CA、中间 CA 读不了或已过期）；HTTP 503 */
        UNAVAILABLE("unavailable", 503),
        /** 请求写得不对（缺字段、名字写法不对、名字太多）；HTTP 400 */
        BAD_REQUEST("badRequest", 400),
        /** 名字不在中间 CA 的域名约束之内，签了浏览器也不认；HTTP 403 */
        REJECTED_NAME("rejectedName", 403),
        /** 公钥不对（不是 SubjectPublicKeyInfo、类型或长度不收）；HTTP 400 */
        BAD_KEY("badKey", 400),
        /** 这个来源申请得太频繁，或订单太多；HTTP 429 */
        RATE_LIMITED("rateLimited", 429),
        /** 没有这张订单（订单号不对或已过期）；HTTP 404 */
        NOT_FOUND("notFound", 404),
        /** 订单现在的状态不能这样做（已经通知过就绪、还没签好就取证书）；HTTP 409 */
        WRONG_STATE("wrongState", 409);

        /** HTTP 接口里 {@code error} 的写法 */
        public final String id;
        /** HTTP 接口回的状态码 */
        public final int httpStatus;

        Code(String id, int httpStatus) {
            this.id = id;
            this.httpStatus = httpStatus;
        }
    }

    public final Code code;

    public CaException(Code code, String message) {
        super(message);
        this.code = code;
    }
}
