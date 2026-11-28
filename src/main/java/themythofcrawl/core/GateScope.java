package themythofcrawl.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * V_11.6: extra "which requests does the Chặn gate hold?" filters on top of Mọi host / 1 host / 1 host + path / nhánh:
 * Burp Target scope, a host / domain list (wildcards, ports), an exclude list, path patterns and "only APIs of the
 * Excel list". All empty / off = no extra filter (old behaviour).
 */
public record GateScope(boolean burpScope, List<String> hosts, List<String> exclude, List<String> paths, boolean excelOnly) {

    public GateScope {
        hosts = clean(hosts);
        exclude = clean(exclude);
        paths = clean(paths);
    }

    public static GateScope of(Config c) {
        return new GateScope(c.gateBurpScope, lines(c.gateHosts), lines(c.gateExclude), lines(c.gatePaths), c.gateExcelOnly);
    }

    public boolean active() {
        return burpScope || excelOnly || !hosts.isEmpty() || !exclude.isEmpty() || !paths.isEmpty();
    }

    /** What the caller knows about a request. */
    public interface Facts {
        boolean inBurpScope();

        boolean inExcel();
    }

    /** Null when the gate may hold this request, otherwise why it goes straight through. */
    public String rejects(String host, int port, String path, Facts f) {
        if (!active()) {
            return null;
        }
        if (matchesHost(exclude, host, port)) {
            return "nằm trong danh sách loại trừ";
        }
        boolean hostListed = !hosts.isEmpty() && matchesHost(hosts, host, port);
        if (burpScope || !hosts.isEmpty()) {
            boolean ok = hostListed || burpScope && f != null && f.inBurpScope();
            if (!ok) {
                return burpScope && hosts.isEmpty() ? "ngoài Scope của Burp"
                        : burpScope ? "ngoài Scope Burp và không có trong danh sách host" : "không có trong danh sách host";
            }
        }
        if (!paths.isEmpty() && !matchesPath(paths, path)) {
            return "path không khớp mẫu";
        }
        if (excelOnly && (f == null || !f.inExcel())) {
            return "không có trong file Excel";
        }
        return null;
    }

    /** "Chặn chỉ khi: trong Scope Burp hoặc *.shop.vn · path /api/* · trừ cdn.*". */
    public String sentence() {
        if (!active()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        List<String> where = new ArrayList<>();
        if (burpScope) {
            where.add("trong Scope Burp");
        }
        if (!hosts.isEmpty()) {
            where.add(String.join(", ", hosts));
        }
        if (!where.isEmpty()) {
            parts.add(String.join(" hoặc ", where));
        }
        if (!paths.isEmpty()) {
            parts.add("path " + String.join(", ", paths));
        }
        if (excelOnly) {
            parts.add("chỉ API trong Excel");
        }
        if (!exclude.isEmpty()) {
            parts.add("trừ " + String.join(", ", exclude));
        }
        return "Chỉ khi: " + String.join(" · ", parts);
    }

    // ------------------------------------------------------------------ matching
    static boolean matchesHost(List<String> list, String host, int port) {
        String h = Text.nz(host).toLowerCase(Locale.ROOT);
        for (String e : list) {
            String p = e.toLowerCase(Locale.ROOT);
            int schemeEnd = p.indexOf("://");
            if (schemeEnd >= 0) {
                p = p.substring(schemeEnd + 3);
            }
            int slash = p.indexOf('/');
            if (slash >= 0) {
                p = p.substring(0, slash);
            }
            Integer wantPort = null;
            int colon = p.lastIndexOf(':');
            if (colon > 0 && p.substring(colon + 1).matches("\\d+")) {
                wantPort = Integer.parseInt(p.substring(colon + 1));
                p = p.substring(0, colon);
            }
            if (wantPort != null && wantPort != port) {
                continue;
            }
            if (glob(p).matcher(h).matches()) {
                return true;
            }
            // "*.shop.vn" also covers the bare "shop.vn"
            if (p.startsWith("*.") && h.equals(p.substring(2))) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesPath(List<String> list, String path) {
        String p = Text.stripQuery(Text.nz(path));
        for (String e : list) {
            String t = e.startsWith("/") ? e : "/" + e;
            if (!t.contains("*")) {
                if (p.equals(t) || p.startsWith(t.endsWith("/") ? t : t + "/") || p.startsWith(t) && t.endsWith("/")) {
                    return true;
                }
                continue;
            }
            StringBuilder rx = new StringBuilder("^");
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (ch == '*') {
                    boolean dbl = i + 1 < t.length() && t.charAt(i + 1) == '*';
                    rx.append(dbl ? ".*" : "[^/]*");
                    if (dbl) {
                        i++;
                    }
                } else {
                    rx.append(Pattern.quote(String.valueOf(ch)));
                }
            }
            String r = rx.toString();
            // "/api/*" also takes deeper paths when it is the last segment ("/api/a/b")
            if (t.endsWith("/*")) {
                r = r.substring(0, r.length() - "[^/]*".length()) + ".*";
            }
            if (Pattern.compile(r + "$", Pattern.CASE_INSENSITIVE).matcher(p).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern glob(String g) {
        StringBuilder rx = new StringBuilder("^");
        for (char ch : g.toCharArray()) {
            rx.append(ch == '*' ? ".*" : Pattern.quote(String.valueOf(ch)));
        }
        return Pattern.compile(rx.append('$').toString());
    }

    public static List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        for (String l : Text.nz(raw).split("[\\r\\n,;]+")) {
            if (!l.isBlank()) {
                out.add(l.trim());
            }
        }
        return out;
    }

    private static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String s : in) {
                if (s != null && !s.isBlank() && !out.contains(s.trim())) {
                    out.add(s.trim());
                }
            }
        }
        return List.copyOf(out);
    }
}
