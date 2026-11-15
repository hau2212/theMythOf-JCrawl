package themythofcrawl.core;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Minimal reading of raw HTTP messages: status code, body offset, one header. */
public final class HttpBytes {
    private HttpBytes() {
    }

    /** Index of the first body byte (after the blank line), or the message length. */
    public static int bodyOffset(byte[] msg) {
        if (msg == null) {
            return 0;
        }
        for (int i = 0; i + 1 < msg.length; i++) {
            if (msg[i] == '\n') {
                if (msg[i + 1] == '\n') {
                    return i + 2;
                }
                if (msg[i + 1] == '\r' && i + 2 < msg.length && msg[i + 2] == '\n') {
                    return i + 3;
                }
            }
        }
        return msg.length;
    }

    public static Integer statusCode(byte[] resp) {
        if (resp == null || resp.length < 12) {
            return null;
        }
        int end = 0;
        while (end < resp.length && end < 512 && resp[end] != '\r' && resp[end] != '\n') {
            end++;
        }
        String[] parts = new String(resp, 0, end, StandardCharsets.ISO_8859_1).split(" ");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            return null;
        }
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int bodyLength(byte[] msg) {
        return msg == null ? 0 : msg.length - bodyOffset(msg);
    }

    /** Value of the first header with this name (case-insensitive), or null. */
    public static String header(byte[] msg, String name) {
        if (msg == null) {
            return null;
        }
        String head = new String(msg, 0, bodyOffset(msg), StandardCharsets.ISO_8859_1);
        String want = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : head.split("\r?\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(want)) {
                return line.substring(want.length()).trim();
            }
        }
        return null;
    }
}
