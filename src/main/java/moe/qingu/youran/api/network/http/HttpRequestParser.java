package moe.qingu.youran.api.network.http;

import java.nio.charset.StandardCharsets;

/**
 * 请求的解析（RFC 9112），纯逻辑：给目前收到的字节，说“还不完整 / 好了 / 不合格（回哪个状态码）”。
 * <ul>
 * <li>首部（到空行为止）超过上限回 431；请求行、首部行格式不对回 400（含首部名与冒号之间有空白、折行）；</li>
 * <li>HTTP/1.1 必须有且只有一个 Host；</li>
 * <li>不收 {@code Transfer-Encoding}（分块上传）：回 501，正文只认 {@code Content-Length}，几条不一致或不是数字回 400，超过上限回 413。
 * 这样转发时不会和后端对正文边界的理解不一致（请求走私）。</li>
 * </ul>
 */
final class HttpRequestParser {
    /** 解析结果 */
    static final class Result {
        /** 还要更多字节 */
        static final Result MORE = new Result(0, null, null, null, null, null);

        /** 不合格时的状态码；合格为 0 */
        final int error;
        final String method;
        final String target;
        final String version;
        final HttpHeaders headers;
        final byte[] body;

        Result(int error, String method, String target, String version, HttpHeaders headers, byte[] body) {
            this.error = error;
            this.method = method;
            this.target = target;
            this.version = version;
            this.headers = headers;
            this.body = body;
        }

        static Result error(int status) {
            return new Result(status, null, null, null, null, null);
        }

        boolean complete() {
            return this != MORE && error == 0;
        }
    }

    private HttpRequestParser() {
    }

    static Result parse(byte[] b, int length, int maxHeader, int maxBody) {
        // 请求行前面的空行忽略（RFC 9112 第 2.2 节）
        int start = 0;
        while (start + 1 < length && b[start] == '\r' && b[start + 1] == '\n') {
            start += 2;
        }
        int end = indexOfHeaderEnd(b, start, length);
        if (end < 0) {
            return length - start > maxHeader ? Result.error(431) : Result.MORE;
        }
        if (end - start > maxHeader) {
            return Result.error(431);
        }
        String head = new String(b, start, end - start, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n", -1);
        String[] parts = lines[0].split(" ", -1);
        if (parts.length != 3) {
            return Result.error(400);
        }
        String method = parts[0];
        String target = parts[1];
        String version = parts[2];
        if (!HttpHeaders.isToken(method) || !validTarget(target)) {
            return Result.error(400);
        }
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            return Result.error(version.startsWith("HTTP/") ? 505 : 400);
        }
        HttpHeaders headers = new HttpHeaders();
        int hosts = 0;
        long contentLength = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                // 折行（obs-fold）不收
                return Result.error(400);
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return Result.error(400);
            }
            String name = line.substring(0, colon);
            String rawValue = line.substring(colon + 1);
            String value = rawValue.trim();
            if (!HttpHeaders.isToken(name) || !validValue(rawValue)) {
                return Result.error(400);
            }
            headers.add(name, value);
            String n = HttpHeaders.lower(name);
            if (n.equals("host")) {
                hosts++;
            } else if (n.equals("transfer-encoding")) {
                return Result.error(501);
            } else if (n.equals("content-length")) {
                long v = parseLength(value);
                if (v < 0 || (contentLength >= 0 && v != contentLength)) {
                    return Result.error(400);
                }
                contentLength = v;
            }
        }
        if (version.equals("HTTP/1.1") ? hosts != 1 : hosts > 1) {
            return Result.error(400);
        }
        if (contentLength > maxBody) {
            return Result.error(413);
        }
        int bodyStart = end + 4;
        long need = contentLength < 0 ? 0 : contentLength;
        if (length - bodyStart < need) {
            return Result.MORE;
        }
        byte[] body = new byte[(int) need];
        System.arraycopy(b, bodyStart, body, 0, body.length);
        return new Result(0, method, target, version, headers, body);
    }

    /** origin-form（以 / 开头），只有可见 ASCII */
    static boolean validTarget(String t) {
        if (t.isEmpty() || t.charAt(0) != '/') {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** 首部值（RFC 9110 field-value）：可见字符、空格、制表符、obs-text */
    static boolean validValue(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** Content-Length：一个或多个十进制数字（最多 18 位）；不是返回 -1 */
    static long parseLength(String v) {
        if (v.isEmpty() || v.length() > 18) {
            return -1;
        }
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) < '0' || v.charAt(i) > '9') {
                return -1;
            }
        }
        return Long.parseLong(v);
    }

    static int indexOfHeaderEnd(byte[] b, int from, int length) {
        for (int i = Math.max(0, from); i + 3 < length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
