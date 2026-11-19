package themythofcrawl.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.excel.PathMatch;

/**
 * Proactive Proxy interception rule evaluated BEFORE a browser request leaves Burp Proxy to the server.
 * Lets users configure path/branch scope + individual HTTP method toggles (GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS)
 * to either BLOCK_SPOOF (hold at Proxy &amp; return synthetic JSON 200/201/204), BLOCK_DROP (drop connection),
 * or ALLOW (explicit pass-through exception overriding lower-priority rules).
 */
public final class ActionInterceptRule {

    public static final List<String> ALL_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    public static final String DEFAULT_SPOOF_BODY = "{\"status\":\"held_by_jcrawl\",\"ok\":true,\"server_id\":\"kept\"}";

    public enum Effect {
        INTERCEPT_WAIT("Chặn chờ duyệt (Đợi tôi bấm Gửi)", "Chặn đứng request tại Proxy và hiện ngay trong Hàng chờ Chặn để bạn xem/sửa rồi bấm Gửi lên Server hoặc Hủy"),
        BLOCK_SPOOF("Chặn & Trả giả lập", "Chặn trước khi gửi lên Server, ghi nhận request vào Extension và trả JSON giả cho trình duyệt"),
        BLOCK_DROP("Chặn & Drop kết nối", "Chặn và ngắt kết nối ngay tại Proxy (không gửi lên Server), vẫn ghi nhận request vào Extension"),
        ALLOW("Cho qua (Ngoại lệ)", "Cho phép Request đi thẳng lên Server bình thường, ưu tiên vượt qua các luật chặn bên dưới");

        public final String label;
        public final String description;

        Effect(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public boolean isBlock() {
            return this == INTERCEPT_WAIT || this == BLOCK_SPOOF || this == BLOCK_DROP;
        }

        public boolean isWait() {
            return this == INTERCEPT_WAIT;
        }

        public static Effect parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return BLOCK_SPOOF;
            }
            for (Effect e : values()) {
                if (e.name().equalsIgnoreCase(raw.trim())) {
                    return e;
                }
            }
            return BLOCK_SPOOF;
        }
    }

    public enum PathMatchType {
        WILDCARD("Tiền tố / Wildcard (*)"),
        TEMPLATE("Template ({id})"),
        CONTAINS("Chứa chuỗi"),
        REGEX("Regex");

        public final String label;

        PathMatchType(String label) {
            this.label = label;
        }

        public static PathMatchType parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return WILDCARD;
            }
            for (PathMatchType t : values()) {
                if (t.name().equalsIgnoreCase(raw.trim())) {
                    return t;
                }
            }
            return WILDCARD;
        }
    }

    private final String id;
    private String name;
    private boolean enabled;
    private Effect effect;
    private String branchScope;
    private boolean includeSubBranches;
    private String hostPattern;
    private PathMatchType pathMatchType;
    private String pathPattern;
    private final Set<String> methods;
    private int spoofStatus;
    private String spoofBody;
    private transient int hitCount;

    public ActionInterceptRule(
            String id,
            String name,
            boolean enabled,
            Effect effect,
            String branchScope,
            boolean includeSubBranches,
            String hostPattern,
            PathMatchType pathMatchType,
            String pathPattern,
            Set<String> methods,
            int spoofStatus,
            String spoofBody) {
        this.id = (id == null || id.isBlank()) ? "ir-" + UUID.randomUUID().toString().substring(0, 8) : id.trim();
        this.name = Text.nz(name).trim();
        this.enabled = enabled;
        this.effect = effect == null ? Effect.BLOCK_SPOOF : effect;
        this.branchScope = Text.blank(branchScope) ? "" : Hierarchy.normalize(branchScope);
        this.includeSubBranches = includeSubBranches;
        this.hostPattern = Text.nz(hostPattern).trim();
        this.pathMatchType = pathMatchType == null ? PathMatchType.WILDCARD : pathMatchType;
        this.pathPattern = Text.nz(pathPattern).trim();
        this.methods = new LinkedHashSet<>();
        if (methods != null) {
            for (String m : methods) {
                if (m != null && !m.isBlank()) {
                    this.methods.add(m.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        this.spoofStatus = spoofStatus >= 100 && spoofStatus <= 599 ? spoofStatus : 200;
        this.spoofBody = (spoofBody == null || spoofBody.isBlank()) ? DEFAULT_SPOOF_BODY : spoofBody;
    }

    public ActionInterceptRule copy() {
        ActionInterceptRule c = new ActionInterceptRule(
                id, name, enabled, effect, branchScope, includeSubBranches,
                hostPattern, pathMatchType, pathPattern, methods, spoofStatus, spoofBody);
        c.hitCount = this.hitCount;
        return c;
    }

    public String id() { return id; }
    public String name() { return name.isEmpty() ? defaultDisplayName() : name; }
    public String rawName() { return name; }
    public boolean enabled() { return enabled; }
    public Effect effect() { return effect; }
    public String branchScope() { return branchScope; }
    public boolean includeSubBranches() { return includeSubBranches; }
    public String hostPattern() { return hostPattern; }
    public PathMatchType pathMatchType() { return pathMatchType; }
    public String pathPattern() { return pathPattern; }
    public Set<String> methods() { return Collections.unmodifiableSet(methods); }
    public int spoofStatus() { return spoofStatus; }
    public String spoofBody() { return spoofBody; }
    public int hitCount() { return hitCount; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void incrementHit() { this.hitCount++; }
    public void resetHit() { this.hitCount = 0; }

    public String defaultDisplayName() {
        String p = pathPattern.isEmpty() ? "*" : pathPattern;
        return (effect == Effect.ALLOW ? "Cho qua " : "Chặn ") + methodsLabel() + " " + p;
    }

    public String methodsLabel() {
        if (methods.isEmpty() || methods.size() == ALL_METHODS.size()) {
            return "Mọi Method";
        }
        return String.join(", ", methods);
    }

    public String branchScopeLabel() {
        if (branchScope.isEmpty()) {
            return "Mọi nhánh";
        }
        return branchScope + (includeSubBranches ? " (+ con)" : " (chỉ nhánh này)");
    }

    /**
     * Evaluates whether this rule matches the incoming Proxy request context.
     */
    public boolean matches(String activeBranch, String host, String method, String rawPath) {
        if (!enabled) {
            return false;
        }
        // 1. Method filter
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!methods.isEmpty() && !methods.contains(m)) {
            return false;
        }
        // 2. Branch scope filter
        if (!branchScope.isEmpty()) {
            String cur = Text.blank(activeBranch) ? "" : Hierarchy.normalize(activeBranch);
            if (cur.isEmpty()) {
                return false;
            }
            if (includeSubBranches) {
                if (!cur.equalsIgnoreCase(branchScope)
                        && !cur.toLowerCase(Locale.ROOT).startsWith((branchScope + " > ").toLowerCase(Locale.ROOT))) {
                    return false;
                }
            } else if (!cur.equalsIgnoreCase(branchScope)) {
                return false;
            }
        }
        // 3. Host filter
        if (!hostPattern.isEmpty() && !hostPattern.equals("*")) {
            String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
            String hp = hostPattern.toLowerCase(Locale.ROOT);
            if (hp.contains("*")) {
                if (!wildcardMatches(hp, h)) {
                    return false;
                }
            } else if (!h.contains(hp)) {
                return false;
            }
        }
        // 4. Path pattern filter
        String pat = pathPattern == null ? "" : pathPattern.trim();
        if (pat.isEmpty() || pat.equals("*")) {
            return true;
        }
        String fullPath = rawPath == null ? "/" : rawPath;
        String cleanPath = PathMatch.normalize(fullPath);
        return switch (pathMatchType) {
            case WILDCARD -> matchWildcardPath(pat, cleanPath, fullPath);
            case TEMPLATE -> {
                PathMatch.Template tpl = PathMatch.template(pat, false);
                yield tpl.exact().matcher(cleanPath).matches();
            }
            case CONTAINS -> fullPath.toLowerCase(Locale.ROOT).contains(pat.toLowerCase(Locale.ROOT))
                    || cleanPath.toLowerCase(Locale.ROOT).contains(pat.toLowerCase(Locale.ROOT));
            case REGEX -> {
                try {
                    yield Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(fullPath).find();
                } catch (RuntimeException ignored) {
                    yield false;
                }
            }
        };
    }

    private static boolean matchWildcardPath(String pattern, String cleanPath, String fullPath) {
        String p = pattern.trim();
        if (p.contains("*")) {
            String normPat = p.startsWith("/") ? p : "/" + p;
            return wildcardMatches(normPat.toLowerCase(Locale.ROOT), cleanPath.toLowerCase(Locale.ROOT))
                    || wildcardMatches(p.toLowerCase(Locale.ROOT), fullPath.toLowerCase(Locale.ROOT));
        }
        String normPat = PathMatch.normalize(p).toLowerCase(Locale.ROOT);
        String cp = cleanPath.toLowerCase(Locale.ROOT);
        return cp.equals(normPat) || cp.startsWith(normPat + "/");
    }

    private static boolean wildcardMatches(String wildcard, String target) {
        StringBuilder rx = new StringBuilder("^");
        for (int i = 0; i < wildcard.length(); i++) {
            char ch = wildcard.charAt(i);
            if (ch == '*') {
                rx.append(".*");
            } else {
                rx.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        rx.append("$");
        try {
            return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE).matcher(target).matches();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Builds a valid raw HTTP/1.1 spoof response for BLOCK_SPOOF rules. */
    public String buildSpoofResponse() {
        String reason = switch (spoofStatus) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 403 -> "Forbidden";
            default -> "OK";
        };
        byte[] bodyBytes = spoofStatus == 204
                ? new byte[0]
                : (spoofBody == null ? DEFAULT_SPOOF_BODY : spoofBody).getBytes(StandardCharsets.UTF_8);
        String safeRuleName = name().replaceAll("[\\r\\n]+", " ").trim();
        if (safeRuleName.length() > 64) {
            safeRuleName = safeRuleName.substring(0, 64);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(spoofStatus).append(' ').append(reason).append("\r\n");
        if (spoofStatus != 204) {
            sb.append("Content-Type: application/json; charset=utf-8\r\n");
        }
        sb.append("X-JCrawl-Held: true\r\n");
        sb.append("X-JCrawl-Rule: ").append(safeRuleName).append("\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");
        if (bodyBytes.length > 0) {
            sb.append(new String(bodyBytes, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("enabled", enabled);
        m.put("effect", effect.name());
        m.put("branchScope", branchScope);
        m.put("includeSubBranches", includeSubBranches);
        m.put("hostPattern", hostPattern);
        m.put("pathMatchType", pathMatchType.name());
        m.put("pathPattern", pathPattern);
        m.put("methods", new ArrayList<>(methods));
        m.put("spoofStatus", spoofStatus);
        m.put("spoofBody", spoofBody);
        return m;
    }

    public static ActionInterceptRule fromMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        Set<String> ms = new LinkedHashSet<>();
        for (Object o : Json.list(m, "methods")) {
            if (o != null && !String.valueOf(o).isBlank()) {
                ms.add(String.valueOf(o).trim().toUpperCase(Locale.ROOT));
            }
        }
        return new ActionInterceptRule(
                Json.str(m, "id", ""),
                Json.str(m, "name", ""),
                Json.bool(m, "enabled", true),
                Effect.parse(Json.str(m, "effect", "BLOCK_SPOOF")),
                Json.str(m, "branchScope", ""),
                Json.bool(m, "includeSubBranches", true),
                Json.str(m, "hostPattern", ""),
                PathMatchType.parse(Json.str(m, "pathMatchType", "WILDCARD")),
                Json.str(m, "pathPattern", ""),
                ms,
                (int) Json.num(m, "spoofStatus", 200),
                Json.str(m, "spoofBody", DEFAULT_SPOOF_BODY));
    }

    public static String listToJson(List<ActionInterceptRule> rules) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        List<Object> items = new ArrayList<>();
        if (rules != null) {
            for (ActionInterceptRule r : rules) {
                if (r != null) {
                    items.add(r.toMap());
                }
            }
        }
        root.put("rules", items);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public static List<ActionInterceptRule> listFromJson(String json) {
        List<ActionInterceptRule> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            Map<String, Object> root = Json.parseObject(json);
            for (Object o : Json.list(root, "rules")) {
                if (o instanceof Map<?, ?> raw) {
                    ActionInterceptRule r = fromMap((Map<String, Object>) raw);
                    if (r != null) {
                        out.add(r);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Corrupt JSON ignored safely
        }
        return out;
    }
}
