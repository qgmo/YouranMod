/**
 * 游戏内网站的证书签发服务（{@code ca.yr}，类似 Let's Encrypt）：网站要 HTTPS 时自己来申请，证明自己管着这些名字后拿到证书，
 * 到期前自己续。用服务器配的中间 CA 签发（没配时服务不开）；和 DNS 注册无关，注册了域名不会自动有证书。
 * <ul>
 * <li>{@link moe.qingu.youran.api.network.ca.CertificateClient}：给模组用的助手，申请、认证、续期都自动做，给出 HTTPS 用的 TLS；</li>
 * <li>{@link moe.qingu.youran.api.network.ca.CertificateStore}：证书与私钥存在哪（调用方自己定；{@code directory(目录)} 存成 PEM 文件），
 * 给了它助手就把证书存下、开服时还能用就直接用；</li>
 * <li>{@link moe.qingu.youran.api.network.ca.CertificateAuthority}：Java 接口（异步），由 {@code YouranNetworkApi.certificateAuthority()} 得到；</li>
 * <li>{@link moe.qingu.youran.api.network.ca.CertificateRequest}：证书请求（CSR，PKCS#10）的生成与读取、验签；</li>
 * <li>{@link moe.qingu.youran.api.network.ca.CertificateOrder}、{@link moe.qingu.youran.api.network.ca.Challenge}：订单与认证要求；</li>
 * <li>{@link moe.qingu.youran.api.network.ca.HttpChallenges}：HTTP 认证要回的内容（网站的 80 口回）；
 * {@link moe.qingu.youran.api.network.ca.TxtRecords}：DNS 认证加删 TXT 的办法。</li>
 * </ul>
 *
 * <h2>认证</h2>
 * 每个名字两种认证都给出，通知已就绪时选一种（整张订单用同一种）。证书服务在 {@code ca.yr} 那台主机上像普通客户端一样走真的网络核对：
 * <ul>
 * <li><b>HTTP</b>（{@code "http"}）：经递归解析器查名字的 AAAA，GET {@code http://<名字>/.well-known/acme-challenge/<令牌>}（80 口，
 * 不跟跳转），要回 200，正文去掉首尾空白后等于 {@code content}；10 秒内要回完。</li>
 * <li><b>DNS</b>（{@code "dns"}）：经递归解析器查 {@code _acme-challenge.<名字>} 的 TXT，要有一条等于 {@code value}。
 * 解析器按 TTL 缓存（记录 300 秒、查无此名 60 秒）：TXT 加好以后再通知；同一个名字刚认证失败过，隔 5 分钟以上再试。</li>
 * </ul>
 * 全部名字都通过才签；有一个不过整张订单失败（{@code error} 写是哪个名字、为什么），重新申请一张。
 *
 * <h2>HTTP 接口</h2>
 * 在 {@code http://ca.yr/} 与 {@code https://ca.yr/}（游戏内网络上）。请求、回应的正文都是 JSON（UTF-8），请求正文最多 16 KiB。
 * 每个来源地址每秒最多 2 个请求（最多攒 30 个）；新订单每个来源最多一下申请 10 张，之后每 6 分钟补一张。
 * <p>
 * <b>1. 申请</b>：{@code POST /orders}
 * <pre>{@code
 * {"names": ["qing.yr", "www.qing.yr"],
 *  "csr": "-----BEGIN CERTIFICATE REQUEST-----\n...\n-----END CERTIFICATE REQUEST-----\n"}
 * }</pre>
 * {@code names}：1–10 个名字，可以写 Unicode（转成 A-label），不能有通配符，要在中间 CA 的域名约束之内（现在只许 {@code .yr}）。
 * {@code csr}：证书请求（PKCS#10），PEM（{@code openssl req -new} 的输出）或它的 DER 的 base64，DER 最多 8 KiB。
 * 用要放进证书的那把私钥签名（证明握着私钥），签出的证书只用里面的公钥，名字以 {@code names} 为准。要求：
 * <ul>
 * <li>签名要对；签名算法收 SHA256/384withECDSA（公钥 EC P-256、P-384）与 SHA256/384/512withRSA（RSA 2048–4096 位），
 * 不收 SHA-1；</li>
 * <li>subjectAltName 的 DNS 名字（转成 A-label、不分大小写）要和 {@code names} 正好是同一组（只收 DNS 名字，不收 IP 等）；</li>
 * <li>主题里有 CN 时，CN 要是其中一个名字（可以不写 CN）。</li>
 * </ul>
 * 例：
 * <pre>{@code
 * openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out qing.key
 * openssl req -new -key qing.key -subj "/CN=qing.yr" -addext "subjectAltName=DNS:qing.yr,DNS:www.qing.yr" -out qing.csr
 * }</pre>
 * 回 {@code 201} 和订单（见下）。
 * <p>
 * <b>2. 取认证要求</b>（也用来查状态）：{@code GET /orders/{id}}，回 {@code 200} 和订单：
 * <pre>{@code
 * {"id": "订单号",
 *  "status": "pending",
 *  "names": ["qing.yr"],
 *  "expires": "2026-11-17T13:00:00Z",
 *  "challenges": [
 *    {"name": "qing.yr",
 *     "http": {"url": "http://qing.yr/.well-known/acme-challenge/令牌", "token": "令牌", "content": "要回的内容"},
 *     "dns": {"name": "_acme-challenge.qing.yr", "value": "TXT 的值"}}],
 *  "method": "http",
 *  "error": "qing.yr：http://qing.yr/.well-known/acme-challenge/令牌 回了 HTTP 404",
 *  "certificate": "/orders/订单号/certificate"}
 * }</pre>
 * {@code status}：{@code pending}（等认证）、{@code processing}（核对中）、{@code valid}（已签发）、{@code invalid}（失败）。
 * {@code method} 在通知就绪以后才有；{@code error} 只在 {@code invalid} 时有；{@code certificate} 只在 {@code valid} 时有。
 * 订单从申请起 1 小时后过期（之后 404），只在内存里，关服就没了。
 * <p>
 * <b>3. 通知已就绪</b>：{@code POST /orders/{id}/ready}，正文 {@code {"method": "http"}} 或 {@code {"method": "dns"}}。
 * 回 {@code 202} 和订单（一般是 {@code processing}）。只能通知一次；之后隔一两秒 {@code GET /orders/{id}} 查，
 * 直到 {@code valid} 或 {@code invalid}（最多 30 秒）。
 * <p>
 * <b>4. 取证书</b>：{@code GET /orders/{id}/certificate}（订单 {@code valid} 以后），回 {@code 200}：
 * <pre>{@code
 * {"certificate": "-----BEGIN CERTIFICATE-----\n（叶子）...\n-----END CERTIFICATE-----\n-----BEGIN CERTIFICATE-----\n（中间 CA）...",
 *  "serial": "十六进制序列号",
 *  "notBefore": "2026-11-17T11:00:00Z",
 *  "notAfter": "2027-02-15T12:00:00Z"}
 * }</pre>
 * {@code certificate} 是整条链的 PEM（叶子在前，后面是中间 CA，不含根），握手时整条发出去。有效 90 天；剩 30 天时重新走一遍 1–4 续期
 * （建议每次换新密钥）。
 * <p>
 * <b>出错</b>：回 4xx / 5xx 和 {@code {"error": "代码", "message": "给人看的原因"}}。代码：
 * {@code badRequest}（400，请求写得不对）、{@code badCsr}（400，证书请求读不了、签名不对、算法不收、名字与 {@code names} 不一致）、
 * {@code badKey}（400，证书请求里的公钥不收）、{@code rejectedName}（403，名字在域名约束之外）、
 * {@code notFound}（404，没有这张订单或这个接口）、{@code wrongState}（409，已经通知过就绪、证书还没签好）、
 * {@code rateLimited}（429，太频繁）、{@code unavailable}（503，服务没开：服务器没配中间 CA）、{@code httpError}（其他 HTTP 错误）。
 */
package moe.qingu.youran.api.network.ca;
