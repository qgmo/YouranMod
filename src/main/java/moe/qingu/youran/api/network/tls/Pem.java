package moe.qingu.youran.api.network.tls;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PEM（RFC 7468）的读写：只认 {@code -----BEGIN 标签-----} 包着的 Base64 */
public final class Pem {
    private static final Pattern BLOCK = Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]*?)-----END \\1-----");

    private Pem() {
    }

    /** 一段 PEM */
    public static final class Block {
        public final String label;
        public final byte[] der;

        Block(String label, byte[] der) {
            this.label = label;
            this.der = der;
        }
    }

    public static List<Block> parse(String text) {
        List<Block> out = new ArrayList<>();
        Matcher m = BLOCK.matcher(text);
        while (m.find()) {
            out.add(new Block(m.group(1), Base64.getMimeDecoder().decode(m.group(2))));
        }
        return out;
    }

    /** 读出所有证书（按出现顺序） */
    public static List<X509Certificate> certificates(String text) throws CertificateException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> out = new ArrayList<>();
        for (Block b : parse(text)) {
            if (b.label.equals("CERTIFICATE")) {
                out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(b.der)));
            }
        }
        return out;
    }

    public static String encode(String label, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + b64 + "\n-----END " + label + "-----\n";
    }

    public static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
