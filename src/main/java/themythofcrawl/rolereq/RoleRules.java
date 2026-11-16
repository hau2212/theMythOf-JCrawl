package themythofcrawl.rolereq;

import java.util.List;

import themythofcrawl.model.HeaderRule;
// === BEGIN ANTIGRAVITY CHANGES ===
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

import themythofcrawl.core.HttpBytes;
import themythofcrawl.model.RuleMode;
// === END ANTIGRAVITY CHANGES ===

/**
 * Builds the request of each role (and of the "API gốc" profile) from the captured original and the
 * role's rules (header / cookie / JWT), as the Python extension does. The whole extension uses
 * {@code RoleRules.INSTANCE}: viewers, sending, Repeater, exports.
 *
 * <p>Contract (see {@link RoleRequestBuilder}): pure and thread-safe, never modifies {@code original},
 * never throws for a bad rule, keeps the request line and the body. Behaviour is pinned by
 * {@code RoleRulesTest} (ported from tests/test_themythofcrawl.py of hau2212/theMythOfCrawl).
 */
public final class RoleRules implements RoleRequestBuilder {
    public static final RoleRequestBuilder INSTANCE = new RoleRules();

    private RoleRules() {
    }

    @Override
    public byte[] build(byte[] original, List<HeaderRule> rules) {
        // === BEGIN ANTIGRAVITY CHANGES ===
        if (original == null || original.length == 0 || rules == null || rules.isEmpty()) {
            return original;
        }
        int bodyOffset = HttpBytes.bodyOffset(original);
        int headEnd = headerEndOffset(original, bodyOffset);
        if (headEnd <= 0) {
            return original;
        }
        String head = new String(original, 0, headEnd, StandardCharsets.ISO_8859_1);
        List<String> lines = Arrays.asList(head.split("\r?\n", -1));
        if (lines.isEmpty() || lines.get(0).isEmpty()) {
            return original;
        }
        List<String> outLines = applyHeaderRules(lines, rules);
        byte[] headBytes = (String.join("\r\n", outLines) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        int bodyLen = Math.max(0, original.length - bodyOffset);
        ByteArrayOutputStream out = new ByteArrayOutputStream(headBytes.length + bodyLen);
        out.write(headBytes, 0, headBytes.length);
        if (bodyLen > 0) {
            out.write(original, bodyOffset, bodyLen);
        }
        return out.toByteArray();
        // === END ANTIGRAVITY CHANGES ===
    }

    // === BEGIN ANTIGRAVITY CHANGES ===
    private static int headerEndOffset(byte[] msg, int bodyOffset) {
        int end = bodyOffset;
        if (end >= 3 && msg[end - 1] == '\n' && msg[end - 2] == '\r' && msg[end - 3] == '\n') {
            end -= 3;
            if (end > 0 && msg[end - 1] == '\r') {
                end--;
            }
        } else if (end >= 2 && msg[end - 1] == '\n' && msg[end - 2] == '\n') {
            end -= 2;
            if (end > 0 && msg[end - 1] == '\r') {
                end--;
            }
        } else if (end >= 2 && msg[end - 1] == '\n' && msg[end - 2] == '\r') {
            end -= 2;
        } else if (end >= 1 && msg[end - 1] == '\n') {
            end -= 1;
        }
        return end;
    }

    /** Header names: trimmed, no CR/LF/colon/whitespace. Returns '' if invalid. */
    public static String sanitizeHeaderName(String name) {
        return HeaderRule.cleanName(name);
    }

    /** Values: CR/LF removed so a rule can never produce a malformed request. */
    public static String sanitizeHeaderValue(String value) {
        return HeaderRule.cleanValue(value);
    }

    public static RuleMode normalizeMode(Object mode, Object value) {
        return RuleMode.of(mode, value);
    }

    static String headerNameOf(String line) {
        if (line == null) {
            return null;
        }
        int idx = line.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        return line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
    }

    record CookiePair(String key, String segment) {
    }

    /** Extract ordered (key, raw_segment) pairs across all Cookie headers. */
    static List<CookiePair> parseCookiePairs(List<String> headers) {
        List<CookiePair> pairs = new ArrayList<>();
        if (headers == null) {
            return pairs;
        }
        for (String h : headers) {
            if (!"cookie".equals(headerNameOf(h))) {
                continue;
            }
            int colon = h.indexOf(':');
            String val = h.substring(colon + 1);
            for (String seg : val.split(";", -1)) {
                String s = seg.trim();
                if (s.isEmpty()) {
                    continue;
                }
                int eq = s.indexOf('=');
                String k = eq >= 0 ? s.substring(0, eq).trim() : s;
                pairs.add(new CookiePair(k, s));
            }
        }
        return pairs;
    }

    /**
     * Modify a single cookie key inside the Cookie header while preserving all
     * other cookies on the request.
     */
    public static List<String> applyCookieRule(List<String> headers, String cookieName, String cookieValue,
                                               boolean remove) {
        List<String> safeHeaders = headers == null ? List.of() : headers;
        String cname = sanitizeHeaderName(cookieName);
        if (cname.isEmpty()) {
            return new ArrayList<>(safeHeaders);
        }
        String cval = sanitizeHeaderValue(cookieValue);
        List<CookiePair> pairs = parseCookiePairs(safeHeaders);
        boolean hasCookieHdr = false;
        for (String h : safeHeaders) {
            if ("cookie".equals(headerNameOf(h))) {
                hasCookieHdr = true;
                break;
            }
        }
        List<String> newSegs = new ArrayList<>();
        if (remove) {
            for (CookiePair pair : pairs) {
                if (!pair.key().equals(cname)) {
                    newSegs.add(pair.segment());
                }
            }
        } else {
            String targetSeg = cname + "=" + cval;
            boolean replaced = false;
            for (CookiePair pair : pairs) {
                if (pair.key().equals(cname)) {
                    if (!replaced) {
                        newSegs.add(targetSeg);
                        replaced = true;
                    }
                } else {
                    newSegs.add(pair.segment());
                }
            }
            if (!replaced) {
                newSegs.add(targetSeg);
            }
        }
        if (newSegs.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String h : safeHeaders) {
                if (!"cookie".equals(headerNameOf(h))) {
                    out.add(h);
                }
            }
            return out;
        }
        String cookieLine = "Cookie: " + String.join("; ", newSegs);
        if (!hasCookieHdr) {
            List<String> out = new ArrayList<>(safeHeaders);
            out.add(cookieLine);
            return out;
        }
        List<String> out = new ArrayList<>();
        boolean emitted = false;
        for (String h : safeHeaders) {
            if ("cookie".equals(headerNameOf(h))) {
                if (!emitted) {
                    out.add(cookieLine);
                    emitted = true;
                }
            } else {
                out.add(h);
            }
        }
        return out;
    }

    /**
     * headerLines: index 0 is the request line ("GET /x HTTP/1.1").
     * rules: applied in order (REPLACE / ADD / REMOVE on whole headers; COOKIE_SET / COOKIE_REMOVE on Cookie keys).
     * Returns a new list; the request line is never modified.
     */
    public static List<String> applyHeaderRules(List<String> headerLines, List<HeaderRule> rules) {
        List<String> lines = new ArrayList<>();
        if (headerLines != null) {
            for (String l : headerLines) {
                lines.add(l == null ? "" : l);
            }
        }
        if (lines.isEmpty()) {
            return lines;
        }
        String requestLine = lines.get(0);
        List<String> headers = new ArrayList<>(lines.subList(1, lines.size()));
        if (rules != null) {
            for (HeaderRule rule : rules) {
                if (rule == null) {
                    continue;
                }
                String name = sanitizeHeaderName(rule.name());
                if (name.isEmpty()) {
                    continue;
                }
                String rawValue = rule.value();
                RuleMode mode = normalizeMode(rule.mode(), rawValue);
                if (mode == RuleMode.COOKIE_REMOVE) {
                    headers = applyCookieRule(headers, name, "", true);
                    continue;
                }
                if (mode == RuleMode.COOKIE_SET) {
                    headers = applyCookieRule(headers, name, rawValue, false);
                    continue;
                }
                String lname = name.toLowerCase(Locale.ROOT);
                if (mode == RuleMode.REMOVE) {
                    List<String> filtered = new ArrayList<>();
                    for (String h : headers) {
                        if (!lname.equals(headerNameOf(h))) {
                            filtered.add(h);
                        }
                    }
                    headers = filtered;
                    continue;
                }
                String newLine = name + ": " + sanitizeHeaderValue(rawValue);
                if (mode == RuleMode.ADD) {
                    headers.add(newLine);
                    continue;
                }
                List<String> out = new ArrayList<>();
                boolean replaced = false;
                for (String h : headers) {
                    if (lname.equals(headerNameOf(h))) {
                        if (!replaced) {
                            out.add(newLine);
                            replaced = true;
                        }
                    } else {
                        out.add(h);
                    }
                }
                if (!replaced) {
                    out.add(newLine);
                }
                headers = out;
            }
        }
        List<String> result = new ArrayList<>(headers.size() + 1);
        result.add(requestLine);
        result.addAll(headers);
        return result;
    }

    public static String formatJwtBearer(String token) {
        return formatJwtBearer(token, "Bearer");
    }

    /** Normalize a raw JWT or Bearer string into 'Bearer <token>' (or [REMOVE]). */
    public static String formatJwtBearer(String token, String prefix) {
        String t = sanitizeHeaderValue(token);
        if (t.isEmpty()) {
            return "";
        }
        if (RuleMode.REMOVE_TOKEN.equalsIgnoreCase(t)) {
            return RuleMode.REMOVE_TOKEN;
        }
        if (t.toLowerCase(Locale.ROOT).startsWith("authorization:")) {
            int colon = t.indexOf(':');
            t = t.substring(colon + 1).trim();
        }
        String pfx = prefix == null ? "Bearer" : prefix.trim();
        if (!pfx.isEmpty() && !t.toLowerCase(Locale.ROOT).startsWith(pfx.toLowerCase(Locale.ROOT) + " ")) {
            return pfx + " " + t;
        }
        return t;
    }
    // === END ANTIGRAVITY CHANGES ===
}
