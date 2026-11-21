package themythofcrawl.rolereq;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import themythofcrawl.core.HttpBytes;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.Role;
import themythofcrawl.model.RuleMode;

/**
 * V_7.0: tells which role's account produced a request that went through the Proxy, by comparing the
 * request's headers / cookies with each role's rules (the token / session values the role sets).
 * <ul>
 *   <li>A rule that sets a header / cookie to the exact value found in the request scores +2.</li>
 *   <li>The same header / cookie present with ANOTHER value scores -2 (a different account).</li>
 *   <li>The best positive score wins. With no positive match, a role made only of REMOVE rules whose
 *       removed headers / cookies are all absent (Guest / chưa đăng nhập) is chosen.</li>
 * </ul>
 * Pure and thread-safe.
 */
public final class CrawlRoleDetector {
    private CrawlRoleDetector() {
    }

    /** The detected role name, or {@code null} when no role matches this request. */
    public static String detect(byte[] request, List<Role> roles) {
        if (request == null || roles == null || roles.isEmpty()) {
            return null;
        }
        Map<String, String> headers = headers(request);
        Map<String, String> cookies = cookies(headers.get("cookie"));
        String best = null;
        int bestScore = 0;
        String guest = null;
        for (Role role : roles) {
            if (role == null || !role.enabled() || role.rules().isEmpty()) {
                continue;
            }
            int score = 0;
            boolean onlyRemoves = true;
            boolean removesSatisfied = true;
            for (HeaderRule r : role.rules()) {
                String name = r.name().toLowerCase(Locale.ROOT);
                String want = r.value() == null ? "" : r.value().trim();
                RuleMode mode = r.mode();
                switch (mode) {
                    case REPLACE, ADD -> {
                        onlyRemoves = false;
                        if (want.isEmpty()) {
                            continue;
                        }
                        String have = headers.get(name);
                        if (have != null) {
                            score += sameValue(have, want) ? 2 : (mode == RuleMode.REPLACE ? -2 : 0);
                        }
                    }
                    case COOKIE_SET -> {
                        onlyRemoves = false;
                        String cookieName = r.name();
                        String cookieValue = want;
                        int eq = want.indexOf('=');
                        if (eq > 0 && !cookies.containsKey(cookieName)) {
                            cookieName = want.substring(0, eq).trim();
                            cookieValue = want.substring(eq + 1).trim();
                        }
                        String have = cookies.get(cookieName);
                        if (have != null && !cookieValue.isEmpty()) {
                            score += have.equals(cookieValue) ? 2 : -2;
                        }
                    }
                    case REMOVE -> {
                        if (headers.containsKey(name)) {
                            removesSatisfied = false;
                        }
                    }
                    case COOKIE_REMOVE -> {
                        if (cookies.containsKey(r.name()) || ("cookie".equals(name) && headers.containsKey("cookie"))) {
                            removesSatisfied = false;
                        }
                    }
                    default -> { }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = role.name();
            }
            if (onlyRemoves && removesSatisfied && guest == null) {
                guest = role.name();
            }
        }
        return best != null ? best : guest;
    }

    private static boolean sameValue(String have, String want) {
        String h = have.trim();
        if (h.equals(want)) {
            return true;
        }
        // "Bearer <tok>" vs "<tok>" written either way in the role
        String hs = stripScheme(h);
        String ws = stripScheme(want);
        return !ws.isEmpty() && hs.equals(ws);
    }

    private static String stripScheme(String v) {
        String t = v.trim();
        int sp = t.indexOf(' ');
        if (sp > 0 && sp < 16 && t.substring(0, sp).chars().allMatch(Character::isLetter)) {
            return t.substring(sp + 1).trim();
        }
        return t;
    }

    static Map<String, String> headers(byte[] request) {
        Map<String, String> out = new HashMap<>();
        int end = HttpBytes.bodyOffset(request);
        String head = new String(request, 0, Math.max(0, Math.min(end, request.length)), StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r?\n");
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c <= 0) {
                continue;
            }
            String n = lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT);
            String v = lines[i].substring(c + 1).trim();
            out.merge(n, v, (a, b) -> "cookie".equals(n) ? a + "; " + b : a);
        }
        return out;
    }

    static Map<String, String> cookies(String header) {
        Map<String, String> out = new HashMap<>();
        if (header == null) {
            return out;
        }
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.putIfAbsent(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
