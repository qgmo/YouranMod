package moe.qingu.youran.server.net.service.captcha;

/** {@code captcha.yr} 的首页：给建站的玩家看怎么用 */
final class CaptchaPages {
    static final String INDEX = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\" lang=\"zh-CN\">\n"
            + "<head>\n"
            + "<meta http-equiv=\"Content-Type\" content=\"application/xhtml+xml; charset=UTF-8\" />\n"
            + "<title>captcha.yr 人机验证</title>\n"
            + "<style type=\"text/css\">body{font-family:sans-serif;margin:16px;color:#202124}"
            + "pre{background:#f1f3f4;border:1px solid #dadce0;padding:6px}td{padding:2px 8px;border-bottom:1px solid #dadce0}</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "<h1>captcha.yr 人机验证</h1>\n"
            + "<p>任何网站都能用的图片验证码。网站在自己的表单里放验证码图片和输入框，提交后由网站的后台向这里核对。</p>\n"
            + "<table>\n"
            + "<tr><td>POST /new</td><td>出一道题，正文是题号（一行纯文本）</td></tr>\n"
            + "<tr><td>GET /img/题号.png</td><td>这道题的图片（160×60），放在表单里给访客看</td></tr>\n"
            + "<tr><td>POST /verify</td><td>表单 id=题号&amp;answer=访客填的答案，正文 ok 或 fail</td></tr>\n"
            + "</table>\n"
            + "<p>答案 5 位，不分大小写。每道题只能核对一次（核对过就作废，不论对错），5 分钟内有效。出题、取图、核对都按来源地址限制频率。</p>\n"
            + "<pre>&lt;img src=\"https://captcha.yr/img/题号.png\" width=\"160\" height=\"60\" alt=\"验证码\" /&gt;\n"
            + "&lt;input type=\"hidden\" name=\"captcha_id\" value=\"题号\" /&gt;\n"
            + "&lt;input type=\"text\" name=\"captcha\" maxlength=\"5\" /&gt;</pre>\n"
            + "</body>\n"
            + "</html>\n";

    private CaptchaPages() {
    }
}
