package moe.qingu.youran.server.net.location;

/** location.yr 的说明页（XHTML 1.0 Strict + CSS 2.1，没有脚本；给建站的玩家看） */
final class LocationPages {
    private LocationPages() {
    }

    static String index(String keyId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\"><head><title>location.yr 定位模块</title>"
                + "<style type=\"text/css\">body{font-family:sans-serif;margin:12px;background:#fff;color:#222}"
                + "h1{font-size:18px;color:#235}pre{background:#eef;padding:6px}td{padding:2px 6px;vertical-align:top}</style></head><body>"
                + "<h1>location.yr：定位模块的公钥与核对</h1>"
                + "<p>装了定位模块的电脑可以给网站一份<b>位置证明</b>：服务器按设备表填好维度与坐标，用私钥签名。网站用公钥当场核对，不用登录。</p>"
                + "<p>当前公钥编号：<b>" + keyId + "</b>（<a href=\"/key\">/key</a>，PEM）</p>"
                + "<h2>网站怎么要位置</h2>"
                + "<p>在表单里放一个特殊的输入框（家具电脑的浏览器认得它）：</p>"
                + "<pre>&lt;input type=\"x-yr-location\" name=\"loc\" value=\"你发的一次性随机数\" /&gt;</pre>"
                + "<p>提交时浏览器先弹出确认框（在电脑屏幕之外，网页点不到），操作者同意后服务器签名，浏览器把证明全文放进这个字段提交；"
                + "没装定位模块、被拒绝等情况下字段的值是 <code>error:no_locator</code>、<code>error:denied</code> 这样的代号。</p>"
                + "<h2>证明的格式</h2>"
                + "<pre>youran-location-proof v1\ndimension=0\nx=120\ny=64\nz=-35\ndevice=fd12:3456:789a::c:4000:1e00\ntime=1763200000\n"
                + "site=shop.yr\nnonce=3q2-7wEXAMPLE\nkey=" + keyId + "\nsignature=MEUCIQ…</pre>"
                + "<p>签的是从第一行到 key= 那一行（含）的 UTF-8 字节，每行以 LF 结束（收到的若是 CRLF 先换回 LF）；ECDSA P-256 + SHA-256，签名是 DER 的 base64。</p>"
                + "<h2>核对</h2><table>"
                + "<tr><td>1</td><td>签名对（公钥编号也要对上）</td></tr>"
                + "<tr><td>2</td><td>site= 是你自己的域名</td></tr>"
                + "<tr><td>3</td><td>device= 等于这次请求的来源地址（换一台电脑用不了）</td></tr>"
                + "<tr><td>4</td><td>time= 是最近的（例如 5 分钟内）</td></tr>"
                + "<tr><td>5</td><td>nonce= 是你发的、没用过（用过就作废）</td></tr></table>"
                + "<p>自己算不了 ECDSA 的程序可以 <code>POST /verify</code>（表单 <code>proof=</code>、可选 <code>site=</code>、<code>device=</code>），"
                + "回 <code>ok</code> 或 <code>fail 原因</code>；第 5 条仍要自己核对。</p>"
                + "</body></html>\n";
    }
}
