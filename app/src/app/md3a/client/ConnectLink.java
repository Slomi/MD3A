package app.md3a.client;

import java.net.InetAddress;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reader for the Macro Deck 3 connect link (the QR code in the desktop app's network panel).
 * Format: https://connect.macro-deck.app/&lt;digits&gt;, payload version 3.
 * Reference: Macro-Deck repo, engineering/api/connect-link.md.
 */
public final class ConnectLink {

    public static final class Endpoint {
        public final String host;
        public final int port;
        public final boolean tls;

        Endpoint(String host, int port, boolean tls) {
            this.host = host;
            this.port = port;
            this.tls = tls;
        }

        public String baseUrl() {
            String h = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return (tls ? "https://" : "http://") + h + ":" + port;
        }
    }

    public final String instanceName;
    public final List<Endpoint> endpoints;
    /** Pairing code (ASCII digits), empty when the link carries none. */
    public final String token;

    private ConnectLink(String instanceName, List<Endpoint> endpoints, String token) {
        this.instanceName = instanceName;
        this.endpoints = endpoints;
        this.token = token;
    }

    public static boolean looksLikeLink(String text) {
        return text != null && text.trim().toLowerCase(Locale.ROOT).matches("^https?://connect\\.macro-deck\\.app/.*");
    }

    /** Finds a connect link anywhere in a piece of text (e.g. shared from a QR scanner). */
    public static String extract(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)https?://connect\\.macro-deck\\.app/[0-9]+")
                .matcher(text);
        return m.find() ? m.group() : null;
    }

    /** @throws IllegalArgumentException when the link is malformed. */
    public static ConnectLink parse(String link) {
        String s = link.trim();
        int slash = s.toLowerCase(Locale.ROOT).indexOf("connect.macro-deck.app/");
        if (slash < 0) throw new IllegalArgumentException("not a connect link");
        String digits = s.substring(slash + "connect.macro-deck.app/".length());
        int cut = indexOfAny(digits, "?#/");
        if (cut >= 0) digits = digits.substring(0, cut);

        byte[] b = decodeDigits(digits);
        int[] pos = {0};

        int version = u8(b, pos);
        if (version != 3) throw new IllegalArgumentException("unknown payload version " + version);

        String name = utf8(bytes(b, pos, u8(b, pos)));

        int count = u8(b, pos);
        List<Endpoint> endpoints = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int type = u8(b, pos);
            String host;
            try {
                switch (type) {
                    case 0:
                        host = InetAddress.getByAddress(bytes(b, pos, 4)).getHostAddress();
                        break;
                    case 1:
                        host = InetAddress.getByAddress(bytes(b, pos, 16)).getHostAddress();
                        break;
                    case 2:
                        host = new String(bytes(b, pos, u8(b, pos)), StandardCharsets.US_ASCII);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown address type " + type);
                }
            } catch (java.net.UnknownHostException e) {
                throw new IllegalArgumentException(e);
            }
            int port = (u8(b, pos) << 8) | u8(b, pos);
            int flags = u8(b, pos);
            endpoints.add(new Endpoint(host, port, (flags & 1) != 0));
        }

        String token = new String(bytes(b, pos, u8(b, pos)), StandardCharsets.US_ASCII);

        int rest = b.length - pos[0];
        if (rest != 0 && rest != 12) throw new IllegalArgumentException("trailing bytes");

        return new ConnectLink(name, endpoints, token);
    }

    static byte[] decodeDigits(String digits) {
        if (!digits.matches("[0-9]*")) throw new IllegalArgumentException("not digits");
        int n = digits.length();
        int tail = n % 5;
        if (tail != 0 && tail != 3) throw new IllegalArgumentException("bad digit count");
        byte[] out = new byte[(n / 5) * 2 + (tail == 3 ? 1 : 0)];
        int o = 0;
        for (int i = 0; i + 5 <= n; i += 5) {
            int v = Integer.parseInt(digits.substring(i, i + 5));
            if (v > 65535) throw new IllegalArgumentException("group too large");
            out[o++] = (byte) (v >> 8);
            out[o++] = (byte) v;
        }
        if (tail == 3) {
            int v = Integer.parseInt(digits.substring(n - 3));
            if (v > 255) throw new IllegalArgumentException("tail too large");
            out[o] = (byte) v;
        }
        return out;
    }

    private static int u8(byte[] b, int[] pos) {
        if (pos[0] >= b.length) throw new IllegalArgumentException("truncated");
        return b[pos[0]++] & 0xFF;
    }

    private static byte[] bytes(byte[] b, int[] pos, int len) {
        if (pos[0] + len > b.length) throw new IllegalArgumentException("truncated");
        byte[] out = new byte[len];
        System.arraycopy(b, pos[0], out, 0, len);
        pos[0] += len;
        return out;
    }

    private static String utf8(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("bad utf-8");
        }
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
        return -1;
    }
}
