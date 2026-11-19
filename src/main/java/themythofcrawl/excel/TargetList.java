package themythofcrawl.excel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/** The API list from the user's Excel file: answers 'which row does METHOD /path belong to?'. */
public final class TargetList {
    public enum QueryMode { IGNORE, NAMES, VALUES }

    private static final Map<String, Set<String>> ALIASES = Map.of(
            "stt", Set.of("stt", "no", "no.", "#", "so thu tu", "so tt", "index", "idx", "order", "id"),
            "method", Set.of("method", "protocol", "phuong thuc", "http method", "verb", "methods", "request method"),
            "path", Set.of("path", "url", "endpoint", "api", "uri", "duong dan", "link", "route", "api path"),
            "position", Set.of("position", "vi tri", "location", "vi tri tim thay", "man hinh", "screen", "feature",
                    "chuc nang", "nhanh", "branch"));
    private static final String[] FIELDS = {"stt", "method", "path", "position"};

    private final List<TargetEntry> entries;
    private final String source;
    private final String sheet;
    private final List<String> headers;
    private final Map<String, List<TargetEntry>> byMethod = new HashMap<>();

    public TargetList(List<TargetEntry> entries, String source, String sheet, List<String> headers) {
        this.entries = List.copyOf(entries);
        this.source = Text.nz(source);
        this.sheet = Text.nz(sheet);
        this.headers = headers == null ? List.of("STT", "PROTOCOL", "PATH", "POSITION") : List.copyOf(headers);
        for (TargetEntry e : this.entries) {
            for (String m : e.methods()) {
                byMethod.computeIfAbsent(m, k -> new ArrayList<>()).add(e);
            }
        }
    }

    public List<TargetEntry> entries() { return entries; }
    public String source() { return source; }
    public String sheet() { return sheet; }
    public List<String> headers() { return headers; }
    public int size() { return entries.size(); }

    /** Identity used to keep the hit counters of this file across reloads. */
    public String signature() {
        return source + "|" + sheet + "|" + entries.size();
    }

    /** Returns a copy of this TargetList with the specified row's path updated (preserving STT, methods, position). */
    public TargetList withReplacedPath(int rowNo, String newPath) {
        List<TargetEntry> next = new ArrayList<>(entries.size());
        for (TargetEntry e : entries) {
            next.add(e.rowNo() == rowNo ? e.withPath(newPath) : e);
        }
        return new TargetList(next, source, sheet, headers);
    }

    /**
     * Best row for this request. A row only matches if its host (full URL in the file) agrees and, with
     * query mode NAMES / VALUES, if the request carries the row's query parameters. Ranking: exact before
     * prefixed, then rows naming this host, more query constraints, more literal segments, earlier rows.
     */
    public TargetEntry match(String method, String path, boolean allowPrefix, String host, boolean idsAsParams,
                             QueryMode queryMode) {
        String p = PathMatch.normalize(path);
        Map<String, List<String>> params = Text.nz(path).contains("?") ? PathMatch.parseQuery(path) : Map.of();
        String reqHost = Text.blank(host) ? null : PathMatch.bareHost(host);
        List<TargetEntry> candidates = new ArrayList<>(byMethod.getOrDefault(Text.nz(method).toUpperCase(Locale.ROOT), List.of()));
        candidates.addAll(byMethod.getOrDefault(PathMatch.ANY_METHOD, List.of()));
        TargetEntry best = null;
        int[] bestKey = null;
        for (TargetEntry e : candidates) {
            if (e.host() != null && reqHost != null && !e.host().equals(reqHost)) {
                continue;
            }
            PathMatch.Template t = e.patterns(idsAsParams);
            int kind;
            if (t.exact().matcher(p).matches()) {
                kind = 2;
            } else if (allowPrefix && t.prefixed().matcher(p).matches()) {
                kind = 1;
            } else {
                continue;
            }
            boolean useQuery = queryMode != QueryMode.IGNORE && !e.query().isEmpty();
            if (useQuery && !e.queryOk(params, queryMode == QueryMode.NAMES ? "names" : "values")) {
                continue;
            }
            int[] key = {kind, e.host() != null && reqHost != null ? 1 : 0, useQuery ? e.query().size() : 0,
                e.patterns(idsAsParams).literal(), -e.rowNo()};
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

    /** Rows declaring the same method(s) + path as an earlier row: [duplicate, first]. */
    public List<TargetEntry[]> duplicates() {
        Map<String, TargetEntry> seen = new HashMap<>();
        List<TargetEntry[]> out = new ArrayList<>();
        for (TargetEntry e : entries) {
            List<String> ms = new ArrayList<>(e.methods());
            ms.sort(null);
            StringBuilder q = new StringBuilder();
            for (String[] kv : e.query()) {
                q.append(kv[0]).append('=').append(kv[1]).append('&');
            }
            String key = ms + "|" + e.norm().toLowerCase(Locale.ROOT) + "|" + q + "|" + e.host();
            TargetEntry first = seen.putIfAbsent(key, e);
            if (first != null) {
                out.add(new TargetEntry[] {e, first});
            }
        }
        return out;
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("sheet", sheet);
        m.put("headers", headers);
        List<Object> es = new ArrayList<>();
        for (TargetEntry e : entries) {
            es.add(e.toMap());
        }
        m.put("entries", es);
        return Json.write(m);
    }

    @SuppressWarnings("unchecked")
    public static TargetList fromJson(String text) {
        Map<String, Object> m = Json.parseObject(text);
        List<TargetEntry> es = new ArrayList<>();
        List<Object> raw = Json.list(m, "entries");
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> d = (Map<String, Object>) raw.get(i);
            List<String> methods = new ArrayList<>();
            for (Object o : Json.list(d, "methods")) {
                methods.add(String.valueOf(o));
            }
            if (methods.isEmpty()) {
                methods.add(PathMatch.ANY_METHOD);
            }
            Object stt = d.get("stt");
            es.add(new TargetEntry(stt, methods, Json.str(d, "path", "/"), Json.str(d, "position", ""),
                    (int) Json.num(d, "row", i + 1)));
        }
        List<String> headers = new ArrayList<>();
        for (Object o : Json.list(m, "headers")) {
            headers.add(String.valueOf(o));
        }
        return new TargetList(es, Json.str(m, "source", ""), Json.str(m, "sheet", ""), headers.isEmpty() ? null : headers);
    }

    /** Header row index (-1 when none), column of each field, header texts. */
    public record Columns(int headerRow, Map<String, Integer> cols, List<String> headers) {
    }

    public static Columns detectColumns(List<List<Object>> rows) {
        for (int r = 0; r < Math.min(15, rows.size()); r++) {
            List<Object> row = rows.get(r);
            Map<String, Integer> cols = new HashMap<>();
            for (int c = 0; c < row.size(); c++) {
                Object v = row.get(c);
                if (v == null) {
                    continue;
                }
                String folded = Text.fold(v);
                for (String f : FIELDS) {
                    if (!cols.containsKey(f) && ALIASES.get(f).contains(folded)) {
                        cols.put(f, c);
                    }
                }
            }
            if (cols.containsKey("path")) {
                List<String> hs = new ArrayList<>();
                for (Object v : row) {
                    hs.add(v == null ? "" : String.valueOf(v));
                }
                return new Columns(r, cols, hs);
            }
        }
        return new Columns(-1, Map.of("stt", 0, "method", 1, "path", 2, "position", 3),
                List.of("STT", "PROTOCOL", "PATH", "POSITION"));
    }

    /** Rows of a sheet -> list (rows without a path containing '/' or an http URL are skipped). */
    public static TargetList fromRows(List<List<Object>> rows, String source, String sheet) {
        Columns c = detectColumns(rows);
        List<TargetEntry> es = new ArrayList<>();
        for (int r = c.headerRow() + 1; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            Object rawPath = cell(row, c.cols().get("path"));
            if (Text.blank(rawPath)) {
                continue;
            }
            String text = String.valueOf(rawPath).trim();
            if (!text.contains("/") && !text.toLowerCase(Locale.ROOT).startsWith("http")) {
                continue;
            }
            Object stt = cell(row, c.cols().get("stt"));
            if (Text.blank(stt)) {
                stt = (long) es.size() + 1;
            }
            es.add(new TargetEntry(stt, PathMatch.methods(cell(row, c.cols().get("method"))), text,
                    Text.nz(cell(row, c.cols().get("position"))), r + 1));
        }
        List<String> headers = new ArrayList<>();
        String[] defaults = {"STT", "PROTOCOL", "PATH", "POSITION"};
        for (int i = 0; i < FIELDS.length; i++) {
            Integer col = c.cols().get(FIELDS[i]);
            String h = defaults[i];
            if (c.headerRow() >= 0 && col != null && col < c.headers().size() && !c.headers().get(col).isEmpty()) {
                h = c.headers().get(col);
            }
            headers.add(h);
        }
        return new TargetList(es, source, sheet, headers);
    }

    private static Object cell(List<Object> row, Integer c) {
        return c != null && c < row.size() ? row.get(c) : null;
    }
}
