package themythofcrawl.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * V_11.5: who is logged in, as a fingerprint. Reads the JWTs a workspace sent (Authorization: Bearer, cookies, any
 * header) and takes the user claim (sub / username / email / user_id...). Only a hash of host + claim leaves the
 * machine: never the token, never the user name. Two web accounts with the same fingerprint are the same login.
 */
public final class Identity {
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.([A-Za-z0-9_-]{5,})\\.[A-Za-z0-9_-]*");
    private static final List<String> CLAIMS = List.of("sub", "preferred_username", "username", "user_name", "email", "upn", "user_id",
            "userId", "uid", "unique_name", "name");

    private Identity() {
    }

    /** The user claim of a JWT payload, or null. */
    static String claim(String jwtPayloadB64) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(jwtPayloadB64);
            Map<String, Object> m = Json.parseObject(new String(raw, StandardCharsets.UTF_8));
            for (String c : CLAIMS) {
                Object v = m.get(c);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return c + "=" + String.valueOf(v).trim().toLowerCase(Locale.ROOT);
                }
            }
        } catch (RuntimeException ignored) {
            // not a JSON JWT
        }
        return null;
    }

    public static String fingerprint(String host, String claim) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((Text.nz(host).toLowerCase(Locale.ROOT) + "|" + claim)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString((host + "|" + claim).hashCode());
        }
    }

    /** Fingerprints of the logins found in a request (headers only). */
    public static Set<String> ofRequest(String host, String request) {
        Set<String> out = new LinkedHashSet<>();
        if (request == null) {
            return out;
        }
        int end = request.indexOf("\r\n\r\n");
        String head = end < 0 ? request : request.substring(0, end);
        Matcher m = JWT.matcher(head);
        while (m.find()) {
            String c = claim(m.group(1));
            if (c != null) {
                out.add(fingerprint(host, c));
            }
        }
        return out;
    }

    /** Fingerprints of an account workspace (its most recent requests; at most {@code max} read). */
    public static Set<String> of(ProjectData.LocalAccount l, int max) {
        Set<String> out = new LinkedHashSet<>();
        List<ProjectData.ApiFact> apis = new ArrayList<>(l.apis());
        apis.sort((a, b) -> Long.compare(b.createdMs(), a.createdMs()));
        int n = 0;
        for (ProjectData.ApiFact a : apis) {
            if (n++ >= max) {
                break;
            }
            byte[] b = l.image(a.gid(), "orig.req");
            if (b != null) {
                out.addAll(ofRequest(a.host(), new String(b, 0, Math.min(b.length, 16_384), StandardCharsets.ISO_8859_1)));
            }
        }
        return out;
    }
}
