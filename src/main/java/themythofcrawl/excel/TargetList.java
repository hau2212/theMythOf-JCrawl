package themythofcrawl.excel;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import themythofcrawl.util.Json;

/** API list imported from the user's Excel file: answers "which row does METHOD /path belong to?". */
public final class TargetList {
    public static final String ANY = "ANY";
    private static final List<String> HTTP_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT");

    /** A path parameter is any bracketed token whatever its name, a whole ":name" segment, or "*". */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{[^{}/]*\\}\\}|\\$\\{[^{}/]*\\}|\\{[^{}/]*\\}|\\[[^\\[\\]/]*\\]|\\([^()/]*\\)|<[^<>/]*>"
                    + "|%[A-Za-z_][A-Za-z0-9_\\-]*%|\\*");

    private static final Map<String, List<String>> HEADER_ALIASES = Map.of(
            "stt", List.of("stt", "no", "no.", "#", "so thu tu", "so tt", "index", "idx", "order", "id"),
            "method", List.of("method", "protocol", "phuong thuc", "http method", "verb", "methods", "request method"),
            "path", List.of("path", "url", "endpoint", "api", "uri", "duong dan", "link", "route", "api path"),
            "position", List.of("position", "vi tri", "location", "vi tri tim thay", "man hinh", "screen",
                    "feature", "chuc nang", "nhanh", "branch"));

    /** One row of the file. */
    public static final class Entry {
        public final String stt;
        public final List<String> methods;
        public final String path;
        public final String position;
        public final int row;
        final Pattern exact;
        final Pattern prefixed;
        final int literal;

        public Entry(String stt, List<String> methods, String path, String position, int row) {
            this.stt = stt;
            this.methods = List.copyOf(methods);
            this.path = path;
            this.position = position == null ? "" : position.trim();
            this.row = row;
            Template t = compile(path);
            this.exact = t.exact;
            this.prefixed = t.prefixed;
            this.literal = t.literal;
        }

        public String methodLabel() {
            return String.join(", ", methods);
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stt", stt);
            m.put("methods", new ArrayList<>(methods));
            m.put("path", path);
            m.put("position", position);
            m.put("row", row);
            return m;
        }
    }

    private record Template(Pattern exact, Pattern prefixed, int literal) {
    }

    private final List<Entry> entries;
    private final String source;
    private final String sheet;
    private final List<String> headers;
    private final Map<String, List<Entry>> byMethod = new LinkedHashMap<>();

    public TargetList(List<Entry> entries, String source, String sheet, List<String> headers) {
        this.entries = List.copyOf(entries);
        this.source = source == null ? "" : source;
        this.sheet = sheet == null ? "" : sheet;
        this.headers = headers == null || headers.size() < 4
                ? List.of("STT", "PROTOCOL", "PATH", "POSITION") : List.copyOf(headers);
        for (Entry e : this.entries) {
            for (String m : e.methods) {
                byMethod.computeIfAbsent(m, k -> new ArrayList<>()).add(e);
            }
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public String source() {
        return source;
    }

    public String sheet() {
        return sheet;
    }

    public List<String> headers() {
        return headers;
    }

    /** Best row: exact beats prefixed, then more literal segments, then file order; null if none. */
    public Entry match(String method, String path, boolean allowPrefix) {
        String p = normalizePath(path);
        List<Entry> candidates = new ArrayList<>(byMethod.getOrDefault(
                method == null ? "" : method.toUpperCase(Locale.ROOT), List.of()));
        candidates.addAll(byMethod.getOrDefault(ANY, List.of()));
        Entry best = null;
        int[] bestKey = null;
        for (Entry e : candidates) {
            int kind;
            if (e.exact.matcher(p).matches()) {
                kind = 2;
            } else if (allowPrefix && e.prefixed.matcher(p).matches()) {
                kind = 1;
            } else {
                continue;
            }
            int[] key = {kind, e.literal, -e.row};
            if (bestKey == null || compare(key, bestKey) > 0) {
                best = e;
                bestKey = key;
            }
        }
        return best;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    /** "https://h/a/b/?q#f" -> "/a/b"; placeholders untouched. */
    public static String normalizePath(String raw) {
        String p = raw == null ? "" : raw.trim();
        String low = p.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://")) {
            int idx = p.indexOf('/', p.indexOf("://") + 3);
            p = idx >= 0 ? p.substring(idx) : "/";
        }
        int cut = p.length();
        for (char sep : new char[] {'?', '#'}) {
            int i = p.indexOf(sep);
            if (i >= 0) {
                cut = Math.min(cut, i);
            }
        }
        p = p.substring(0, cut).trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    static Template compile(String rawPath) {
        String path = normalizePath(rawPath);
        StringBuilder body = new StringBuilder();
        int literal = 0;
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            body.append('/');
            if (seg.startsWith(":") && seg.length() > 1) {
                body.append("[^/]+");
                continue;
            }
            Matcher m = PLACEHOLDER.matcher(seg);
            int pos = 0;
            boolean param = false;
            while (m.find()) {
                body.append(Pattern.quote(seg.substring(pos, m.start())));
                body.append("*".equals(m.group()) ? "[^/]*" : "[^/]+");
                pos = m.end();
                param = true;
            }
            body.append(Pattern.quote(seg.substring(pos)));
            if (!param) {
                literal++;
            }
        }
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return new Template(Pattern.compile("^" + body + "/?$", flags),
                Pattern.compile("^(?:/[^/]+)*" + body + "/?$", flags), literal);
    }

    public static List<String> parseMethods(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String w : raw.trim().split("[\\s,;/|]+")) {
                String u = w.trim().toUpperCase(Locale.ROOT);
                if (HTTP_METHODS.contains(u) && !out.contains(u)) {
                    out.add(u);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(ANY);
        }
        return out;
    }

    /** Lower-case, accent-free form used to recognise headers ("Vị trí" -> "vi tri"). */
    static String fold(Object value) {
        String t = String.valueOf(value).trim().toLowerCase(Locale.ROOT).replace('đ', 'd');
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return String.join(" ", t.replace('_', ' ').trim().split("\\s+"));
    }

    private static String cellText(List<Object> row, Integer col) {
        if (col == null || col >= row.size() || row.get(col) == null) {
            return null;
        }
        Object v = row.get(col);
        if (v instanceof Double d && d == Math.rint(d)) {
            return Long.toString(d.longValue());
        }
        return v.toString();
    }

    /** Rows of a sheet -> list. Columns found by header name (first 15 rows) or by position A-D. */
    public static TargetList fromRows(List<List<Object>> rows, String source, String sheet) {
        int headerRow = -1;
        Map<String, Integer> cols = new LinkedHashMap<>();
        List<String> headerTexts = new ArrayList<>();
        for (int r = 0; r < Math.min(15, rows.size()) && headerRow < 0; r++) {
            Map<String, Integer> found = new LinkedHashMap<>();
            List<Object> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (row.get(c) == null) {
                    continue;
                }
                String folded = fold(row.get(c));
                for (Map.Entry<String, List<String>> alias : HEADER_ALIASES.entrySet()) {
                    if (!found.containsKey(alias.getKey()) && alias.getValue().contains(folded)) {
                        found.put(alias.getKey(), c);
                    }
                }
            }
            if (found.containsKey("path")) {
                headerRow = r;
                cols = found;
                for (Object v : row) {
                    headerTexts.add(v == null ? "" : v.toString());
                }
            }
        }
        if (headerRow < 0) {
            cols.put("stt", 0);
            cols.put("method", 1);
            cols.put("path", 2);
            cols.put("position", 3);
        }
        List<Entry> entries = new ArrayList<>();
        for (int r = headerRow + 1; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            String path = cellText(row, cols.get("path"));
            if (path == null || path.isBlank()) {
                continue;
            }
            path = path.trim();
            if (!path.contains("/") && !path.toLowerCase(Locale.ROOT).startsWith("http")) {
                continue;
            }
            String stt = cellText(row, cols.get("stt"));
            if (stt == null || stt.isBlank()) {
                stt = Integer.toString(entries.size() + 1);
            }
            entries.add(new Entry(stt.trim(), parseMethods(cellText(row, cols.get("method"))), path,
                    cellText(row, cols.get("position")), r + 1));
        }
        List<String> headers = new ArrayList<>();
        String[][] wanted = {{"stt", "STT"}, {"method", "PROTOCOL"}, {"path", "PATH"}, {"position", "POSITION"}};
        for (String[] w : wanted) {
            Integer c = cols.get(w[0]);
            String text = headerRow >= 0 && c != null && c < headerTexts.size() ? headerTexts.get(c) : "";
            headers.add(text.isBlank() ? w[1] : text);
        }
        return new TargetList(entries, source, sheet, headers);
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("sheet", sheet);
        m.put("headers", new ArrayList<>(headers));
        List<Object> list = new ArrayList<>();
        for (Entry e : entries) {
            list.add(e.toJson());
        }
        m.put("entries", list);
        return Json.write(m);
    }

    public static TargetList fromJson(String text) {
        Map<String, Object> m = Json.parseObject(text);
        List<Entry> entries = new ArrayList<>();
        int i = 0;
        for (Object o : Json.list(m, "entries")) {
            i++;
            if (!(o instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) o;
            List<String> methods = new ArrayList<>();
            for (Object x : Json.list(e, "methods")) {
                methods.add(String.valueOf(x));
            }
            if (methods.isEmpty()) {
                methods.add(ANY);
            }
            entries.add(new Entry(Json.str(e, "stt", Integer.toString(i)), methods, Json.str(e, "path", "/"),
                    Json.str(e, "position", ""), (int) Json.num(e, "row", i)));
        }
        List<String> headers = new ArrayList<>();
        for (Object h : Json.list(m, "headers")) {
            headers.add(String.valueOf(h));
        }
        return new TargetList(entries, Json.str(m, "source", ""), Json.str(m, "sheet", ""), headers);
    }
}
