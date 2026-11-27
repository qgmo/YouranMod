/**
 * 身份模块与操作者证明（服主 11-27）：一键登录不由网络按来源地址认人，而由持有网卡对象的电脑模组替操作者签名，
 * 网站（如 {@code id.yr}）用公钥验签、比对来源地址。见 {@link moe.qingu.youran.api.network.identity.OperatorProofs}。
 */
package moe.qingu.youran.api.network.identity;
