package themythofcrawl.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Readable preview (header + payload) of the JWTs found in a text, for the role editor. Read-only. */
public final class Jwt {
    private static final Pattern JWT = Pattern.compile("(eyJ[A-Za-z0-9_\\-]+\\.eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]*)");
    private static final String[] CLAIMS = {"sub", "user", "username", "email", "role", "roles", "scope", "tenant",
        "iss", "exp", "iat"};

    private Jwt() {
    }

    private static String decode(String seg) {
        String s = seg.trim().replace('-', '+').replace('_', '/');
        int pad = s.length() % 4;
        if (pad > 0) {
            s += "=".repeat(4 - pad);
        }
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    public static String preview(String text) {
        Matcher m = JWT.matcher(Text.nz(text));
        List<String> blocks = new ArrayList<>();
        int idx = 0;
        while (m.find()) {
            idx++;
            String[] parts = m.group(1).split("\\.");
            try {
                String hdr = decode(parts[0]);
                String pay = decode(parts[1]);
                String hdrPretty;
                String payPretty;
                Map<String, Object> payObj = Map.of();
                try {
                    hdrPretty = Json.pretty(Json.parse(hdr));
                } catch (RuntimeException e) {
                    hdrPretty = hdr;
                }
                try {
                    payObj = Json.parseObject(pay);
                    payPretty = Json.pretty(payObj);
                } catch (RuntimeException e) {
                    payPretty = pay;
                }
                List<String> summary = new ArrayList<>();
                for (String k : CLAIMS) {
                    if (payObj.containsKey(k)) {
                        summary.add(k + "=" + payObj.get(k));
                    }
                }
                String head = "JWT #" + idx + (summary.isEmpty() ? "" : "  [" + String.join(", ", summary) + "]");
                blocks.add(head + "\nHeader:\n" + hdrPretty + "\nPayload:\n" + payPretty);
            } catch (RuntimeException e) {
                blocks.add("JWT #" + idx + ": decode error (" + e.getMessage() + ")");
            }
        }
        return blocks.isEmpty() ? "No JWT (eyJ...) found in this role's rules." : String.join("\n\n", blocks);
    }
}
