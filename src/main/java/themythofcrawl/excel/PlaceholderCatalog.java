package themythofcrawl.excel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * User-manageable catalog of Excel path placeholders such as {@code {session}}, {@code {id}},
 * {@code {uuid}}, {@code {token}}, {@code {slug}}, {@code {date}}, or custom regex-backed tokens.
 * Persisted in workspace preferences and used by {@link PathMatch} for exact verification &amp; extraction.
 */
public final class PlaceholderCatalog {

    public static final String ID_LIKE_REGEX = "(?=[^/]*\\d)[^/]+"
            + "|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            + "|[0-9a-fA-F]{16,}"
            + "|(?=[^/]*[A-Z])(?=[^/]*[a-z])[A-Za-z0-9_\\-]{16,}";

    /** {orderId}, {user_id}, :uuid, {guid}... are IDs too. */
    public static boolean idLikeName(String name) {
        String raw = sanitizeName(name);
        String n = raw.toLowerCase(Locale.ROOT);
        return n.equals("id") || n.equals("uuid") || n.equals("guid") || n.endsWith("_id") || n.endsWith("-id")
                || raw.matches(".*[a-z0-9]Id") || raw.matches(".*[a-z0-9]ID");
    }

    public enum MatchKind {
        ANY_SEGMENT("Bất kỳ chuỗi (không chứa /)", "[^/]+"),
        /**
         * V_10.4: values that LOOK like an ID: contain a digit (15, a1b2, ORD-2024), a UUID, a long hex string or a long
         * random mixed-case token. Plain words (new, old, export, me, search) do not match, so /api/a/b/old is not
         * mapped to /api/a/b/{id}. Heuristics as in API spec inference tools: type checks + "words are not IDs".
         */
        ID_LIKE("Trông giống ID (có chữ số, UUID, hex, chuỗi ngẫu nhiên dài)", ID_LIKE_REGEX),
        NUMERIC("Chỉ chữ số (0-9)", "\\d+"),
        UUID("UUID chuẩn (8-4-4-4-12)", "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
        RANDOM_TOKEN("Chuỗi ngẫu nhiên / Session (>= 6 ký tự)", "[A-Za-z0-9._~\\-]{6,}"),
        ALPHANUMERIC("Chữ & số (A-Z, 0-9, _, -)", "[A-Za-z0-9_\\-]+"),
        CUSTOM_REGEX("Regex tùy chỉnh", "[^/]+");

        public final String label;
        public final String defaultRegex;

        MatchKind(String label, String defaultRegex) {
            this.label = label;
            this.defaultRegex = defaultRegex;
        }

        public static MatchKind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return ANY_SEGMENT;
            }
            for (MatchKind k : values()) {
                if (k.name().equalsIgnoreCase(raw.trim())) {
                    return k;
                }
            }
            return ANY_SEGMENT;
        }
    }

    public record PlaceholderDef(
            String name,
            MatchKind kind,
            String customRegex,
            String description,
            boolean builtIn) {

        public String token() {
            return "{" + name + "}";
        }

        /** Safe non-capturing regex body matching this placeholder inside a URL path segment. */
        public String effectiveRegex() {
            String rx = (kind == MatchKind.CUSTOM_REGEX && customRegex != null && !customRegex.isBlank())
                    ? customRegex.trim()
                    : (kind == null ? "[^/]+" : kind.defaultRegex);
            try {
                Pattern.compile(rx);
                return "(?:" + rx + ")";
            } catch (RuntimeException ignored) {
                return "[^/]+";
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("kind", (kind == null ? MatchKind.ANY_SEGMENT : kind).name());
            m.put("customRegex", Text.nz(customRegex));
            m.put("description", Text.nz(description));
            m.put("builtIn", builtIn);
            return m;
        }

        public static PlaceholderDef fromMap(Map<String, Object> m) {
            String n = sanitizeName(Json.str(m, "name", ""));
            if (n.isEmpty()) {
                return null;
            }
            return new PlaceholderDef(
                    n,
                    MatchKind.parse(Json.str(m, "kind", "ANY_SEGMENT")),
                    Json.str(m, "customRegex", ""),
                    Json.str(m, "description", ""),
                    Json.bool(m, "builtIn", false));
        }
    }

    private final Map<String, PlaceholderDef> defs = new LinkedHashMap<>();

    public PlaceholderCatalog() {
        seedDefaults();
    }

    private void seedDefaults() {
        defs.clear();
        putBuiltIn("id", MatchKind.ID_LIKE, "ID bản ghi: chỉ khớp giá trị trông giống ID (15, a1b2, UUID...), không khớp từ thường như new / old");
        putBuiltIn("session", MatchKind.ANY_SEGMENT, "Mã phiên ngẫu nhiên (VD: /api/a/{session}/c)");
        putBuiltIn("token", MatchKind.ANY_SEGMENT, "Token động trong đường dẫn URL");
        putBuiltIn("uuid", MatchKind.UUID, "Định dạng UUID 36 ký tự");
        putBuiltIn("num", MatchKind.NUMERIC, "Chỉ khớp chữ số nguyên (0-9)");
        putBuiltIn("slug", MatchKind.ALPHANUMERIC, "Định danh dạng chữ-số-gạch ngang");
        putBuiltIn("any", MatchKind.ANY_SEGMENT, "Khớp bất kỳ đoạn đường dẫn nào");
    }

    private void putBuiltIn(String name, MatchKind kind, String desc) {
        defs.put(name.toLowerCase(Locale.ROOT), new PlaceholderDef(name, kind, "", desc, true));
    }

    public static String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.startsWith("{") || s.startsWith("[") || s.startsWith("<") || s.startsWith(":")) {
            s = s.substring(1).trim();
        }
        while (s.endsWith("}") || s.endsWith("]") || s.endsWith(">")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        s = s.replaceAll("[^A-Za-z0-9_\\-]", "");
        return s;
    }

    public synchronized List<PlaceholderDef> all() {
        return Collections.unmodifiableList(new ArrayList<>(defs.values()));
    }

    public synchronized PlaceholderDef byName(String name) {
        String key = sanitizeName(name).toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : defs.get(key);
    }

    public synchronized PlaceholderDef upsert(String rawName, MatchKind kind, String customRegex, String description) {
        String clean = sanitizeName(rawName);
        if (clean.isEmpty()) {
            return null;
        }
        String key = clean.toLowerCase(Locale.ROOT);
        PlaceholderDef existing = defs.get(key);
        boolean builtIn = existing != null && existing.builtIn();
        PlaceholderDef def = new PlaceholderDef(
                clean,
                kind == null ? MatchKind.ANY_SEGMENT : kind,
                Text.nz(customRegex).trim(),
                Text.nz(description).trim(),
                builtIn);
        defs.put(key, def);
        return def;
    }

    public synchronized boolean remove(String rawName) {
        String key = sanitizeName(rawName).toLowerCase(Locale.ROOT);
        PlaceholderDef cur = defs.get(key);
        if (cur == null || cur.builtIn()) {
            return false;
        }
        defs.remove(key);
        return true;
    }

    public synchronized String regexForToken(String tokenName) {
        PlaceholderDef d = byName(tokenName);
        if (d == null) {
            return idLikeName(tokenName) ? "(?:" + ID_LIKE_REGEX + ")" : "[^/]+";
        }
        return d.effectiveRegex();
    }

    public synchronized String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 2);
        List<Object> list = new ArrayList<>();
        for (PlaceholderDef d : defs.values()) {
            list.add(d.toMap());
        }
        root.put("placeholders", list);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadJson(String json) {
        seedDefaults();
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Map<String, Object> root = Json.parseObject(json);
            for (Object o : Json.list(root, "placeholders")) {
                if (o instanceof Map<?, ?> raw) {
                    PlaceholderDef d = PlaceholderDef.fromMap((Map<String, Object>) raw);
                    if (d != null) {
                        String key = d.name().toLowerCase(Locale.ROOT);
                        boolean builtIn = defs.containsKey(key) && defs.get(key).builtIn();
                        if (builtIn && key.equals("id") && d.kind() == MatchKind.ANY_SEGMENT && Json.num(root, "version", 1) < 2) {
                            continue;   // V_10.4: old default "{id} = anything" upgrades to ID_LIKE
                        }
                        defs.put(key, new PlaceholderDef(d.name(), d.kind(), d.customRegex(), d.description(), builtIn));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Keep defaults on malformed JSON
        }
    }
}
