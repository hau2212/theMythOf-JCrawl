package themythofcrawl.excel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import themythofcrawl.core.Text;

/**
 * Normalisation and templates used on both sides of the Excel comparison. A path parameter is any
 * bracketed token whatever its name: {id} [id] (id) &lt;id&gt; ${id} {{id}} %id%, a whole ':name' segment, or '*'.
 */
public final class PathMatch {
    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[^{}/]*\\}\\}|\\$\\{[^{}/]*\\}|\\{[^{}/]*\\}|\\[[^\\[\\]/]*\\]|"
            + "\\([^()/]*\\)|<[^<>/]*>|%[A-Za-z_][A-Za-z0-9_\\-]*%|\\*");

    private PathMatch() {
    }

    /**
     * 'https://h/a/b;jsessionid=1/?q#f' -> '/a/b': host, query and fragment dropped, %XX decoded (except
     * %2F), ';matrix' parameters removed, '//' collapsed, trailing '/' removed. Placeholders are kept.
     */
    public static String normalize(String raw) {
        String p = Text.nz(raw).trim();
        String low = p.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://")) {
            int idx = p.indexOf('/', p.indexOf("://") + 3);
            p = idx >= 0 ? p.substring(idx) : "/";
        }
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int h = p.indexOf('#');
        if (h >= 0) {
            p = p.substring(0, h);
        }
        p = Text.pctDecode(p.trim());
        List<String> segs = new ArrayList<>();
        for (String seg : p.split("/")) {
            int semi = seg.indexOf(';');
            if (semi >= 0) {
                seg = seg.substring(0, semi);
            }
            if (!seg.isEmpty()) {
                segs.add(seg);
            }
        }
        return "/" + String.join("/", segs);
    }

    public static List<String> segments(String raw) {
        List<String> out = new ArrayList<>();
        for (String s : normalize(raw).split("/")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Host (lower case, no port) and declared query parameters of an Excel PATH cell. */
    public record HostQuery(String host, List<String[]> query) {
    }

    public static HostQuery hostAndQuery(String raw) {
        String text = Text.nz(raw).trim();
        String host = null;
        String low = text.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://")) {
            String rest = text.substring(text.indexOf("://") + 3);
            rest = rest.split("/", 2)[0].split("\\?", 2)[0];
            rest = rest.substring(rest.lastIndexOf('@') + 1).split(":", 2)[0].toLowerCase(Locale.ROOT);
            host = rest.isEmpty() ? null : rest;
        }
        List<String[]> query = new ArrayList<>();
        int qi = text.indexOf('?');
        if (qi >= 0) {
            String qs = text.substring(qi + 1).split("#", 2)[0];
            for (String part : qs.split("&")) {
                if (part.isEmpty()) {
                    continue;
                }
                int eq = part.indexOf('=');
                String name = Text.pctDecode(eq >= 0 ? part.substring(0, eq) : part).trim();
                if (!name.isEmpty()) {
                    query.add(new String[] {name, eq >= 0 ? Text.pctDecode(part.substring(eq + 1)) : null});
                }
            }
        }
        return new HostQuery(host, query);
    }

    /** name -> values of a request's query string. */
    public static Map<String, List<String>> parseQuery(String path) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String p = Text.nz(path);
        int qi = p.indexOf('?');
        if (qi < 0) {
            return out;
        }
        for (String part : p.substring(qi + 1).split("#", 2)[0].split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String name = Text.pctDecode(eq >= 0 ? part.substring(0, eq) : part);
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(Text.pctDecode(eq >= 0 ? part.substring(eq + 1) : ""));
        }
        return out;
    }

    /** Regex of one documented segment and whether it is literal (no parameter). */
    public record Segment(String regex, boolean literal) {
    }

    public static Segment segment(String seg, boolean idsAsParams) {
        if (seg.startsWith(":") && seg.length() > 1) {
            return new Segment("[^/]+", false);
        }
        if (idsAsParams && Text.isIdSegment(seg)) {
            return new Segment("[^/]+", false);
        }
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        boolean param = false;
        Matcher m = PLACEHOLDER.matcher(seg);
        while (m.find()) {
            sb.append(Pattern.quote(seg.substring(pos, m.start())));
            sb.append(m.group().equals("*") ? "[^/]*" : "[^/]+");
            pos = m.end();
            param = true;
        }
        sb.append(Pattern.quote(seg.substring(pos)));
        return new Segment(sb.toString().replace("\\Q\\E", ""), !param);
    }

    /** Exact and prefixed (any leading base path) patterns + number of literal segments. */
    public record Template(Pattern exact, Pattern prefixed, int literal) {
    }

    public static Template template(String rawPath, boolean idsAsParams) {
        List<String> parts = new ArrayList<>();
        int literal = 0;
        for (String seg : segments(rawPath)) {
            Segment s = segment(seg, idsAsParams);
            parts.add(s.regex());
            literal += s.literal() ? 1 : 0;
        }
        String body = parts.isEmpty() ? "" : "/" + String.join("/", parts);
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return new Template(Pattern.compile("^" + body + "/?$", flags),
                Pattern.compile("^(?:/[^/]+)*" + body + "/?$", flags), literal);
    }

    public static final String ANY_METHOD = "ANY";
    public static final List<String> HTTP_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD",
            "OPTIONS", "TRACE", "CONNECT");

    /** 'GET' / 'get, post' / 'GET/POST' / '' -> methods, or [ANY]. */
    public static List<String> methods(Object raw) {
        List<String> out = new ArrayList<>();
        for (String w : Text.nz(raw).split("[\\s,;/|]+")) {
            String u = w.trim().toUpperCase(Locale.ROOT);
            if (HTTP_METHODS.contains(u) && !out.contains(u)) {
                out.add(u);
            }
        }
        if (out.isEmpty()) {
            out.add(ANY_METHOD);
        }
        return out;
    }

    public static boolean hasPlaceholder(String value) {
        return value != null && PLACEHOLDER.matcher(value).find();
    }

    public static String bareHost(String host) {
        String h = Text.nz(host).toLowerCase(Locale.ROOT).trim();
        if (h.startsWith("[") && h.contains("]")) {
            return h.substring(0, h.indexOf(']') + 1);
        }
        return h.split(":", 2)[0];
    }
}
