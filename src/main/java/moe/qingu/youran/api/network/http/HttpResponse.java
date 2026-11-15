package moe.qingu.youran.api.network.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * {@link HttpServer} 要回的响应。首部按顺序原样写出（同名的可以有多条，如多个 {@code Set-Cookie}）；
 * {@code Content-Length}、{@code Connection}、{@code Transfer-Encoding} 由服务器自己写（每条连接一个请求，回完就关），这里给的会被忽略。
 */
public final class HttpResponse {
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ROOT);

    public final int status;
    public final String reason;
    public final HttpHeaders headers;
    public final byte[] body;

    public HttpResponse(int status, String reason, HttpHeaders headers, byte[] body) {
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("状态码不对：" + status);
        }
        if (reason == null || !HttpHeaders.isValidValue(reason)) {
            throw new IllegalArgumentException("原因短语里有不允许的字符");
        }
        this.status = status;
        this.reason = reason;
        this.headers = headers == null ? new HttpHeaders() : headers.copy();
        this.body = body == null ? new byte[0] : body;
    }

    /** 一个带正文的响应（{@code contentType} 如 {@code text/plain; charset=utf-8}） */
    public static HttpResponse of(int status, String contentType, byte[] body) {
        HttpHeaders h = new HttpHeaders();
        if (contentType != null) {
            h.add("Content-Type", contentType);
        }
        return new HttpResponse(status, reasonPhrase(status), h, body);
    }

    /** 纯文本（UTF-8） */
    public static HttpResponse text(int status, String text) {
        return of(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    /** XHTML（UTF-8） */
    public static HttpResponse xhtml(int status, String page) {
        return of(status, "application/xhtml+xml; charset=utf-8", page.getBytes(StandardCharsets.UTF_8));
    }

    /** 加一条首部（返回新的响应） */
    public HttpResponse withHeader(String name, String value) {
        HttpHeaders h = headers.copy();
        h.add(name, value);
        return new HttpResponse(status, reason, h, body);
    }

    /** 常见状态码的原因短语；不认识的给空串 */
    public static String reasonPhrase(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 303: return "See Other";
            case 304: return "Not Modified";
            case 307: return "Temporary Redirect";
            case 308: return "Permanent Redirect";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Content Too Large";
            case 414: return "URI Too Long";
            case 415: return "Unsupported Media Type";
            case 422: return "Unprocessable Content";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "";
        }
    }

    /** 没有正文的状态（1xx、204、304） */
    public static boolean bodyless(int status) {
        return status / 100 == 1 || status == 204 || status == 304;
    }

    /** 首部里由服务器自己管的，不照抄 */
    static boolean managed(String name) {
        String n = HttpHeaders.lower(name);
        return n.equals("content-length") || n.equals("connection") || n.equals("transfer-encoding") || n.equals("keep-alive");
    }

    /** 写成字节（HTTP/1.1，{@code Connection: close}）；{@code headOnly} 时不带正文（HEAD 请求） */
    public byte[] encode(boolean headOnly, ZonedDateTime now) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        if (!headers.contains("Date")) {
            sb.append("Date: ").append(HTTP_DATE.format(now.withZoneSameInstant(ZoneOffset.UTC))).append("\r\n");
        }
        for (HttpHeaders.Field f : headers) {
            if (!managed(f.name)) {
                sb.append(f.name).append(": ").append(f.value).append("\r\n");
            }
        }
        boolean noBody = bodyless(status);
        if (!noBody) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (headOnly || noBody || body.length == 0) {
            return head;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(head.length + body.length);
        out.write(head, 0, head.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }
}
