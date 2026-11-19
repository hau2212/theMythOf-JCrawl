package themythofcrawl.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.excel.PathMatch;

/**
 * Unified Action &amp; Proxy Control Policy (V_3.4_beta).
 * <p>
 * Replaces fragmented state (global hold toggle, per-branch wait, per-endpoint hold/wait,
 * proactive intercept rules, per-API action mode overrides) with a single auditable,
 * deterministic policy entity supporting full CRUD, timestamps, hit tracking, and 5 scopes:
 * {@link ScopeType#GLOBAL}, {@link ScopeType#BRANCH}, {@link ScopeType#PATH_PATTERN},
 * {@link ScopeType#API_EXACT}, and {@link ScopeType#METHOD_MATRIX}.
 */
public final class ActionPolicy {

    public static final List<String> ALL_METHODS = ActionInterceptRule.ALL_METHODS;
    public static final String DEFAULT_SPOOF_BODY = ActionInterceptRule.DEFAULT_SPOOF_BODY;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/MM").withZone(ZoneId.systemDefault());

    public enum ScopeType {
        GLOBAL("Toàn cục", "Áp dụng cho toàn bộ Request / API trong Extension", 4),
        METHOD_MATRIX("Nhóm Method", "Áp dụng theo tổ hợp HTTP Method trên mọi đường dẫn", 3),
        BRANCH("Nhánh cây", "Áp dụng cho 1 hoặc nhiều Nhánh cây (có thể gồm nhánh con)", 2),
        PATH_PATTERN("Đường dẫn / Pattern", "Khớp theo Path (Exact / Prefix / Wildcard / Template {id} / Regex)", 1),
        API_EXACT("API cụ thể", "Chỉ áp dụng cho đúng API / endpoint đã chọn", 0);

        public final String label;
        public final String description;
        /** Lower specificity rank wins when two policies share the same priority. */
        public final int specificityRank;

        ScopeType(String label, String description, int specificityRank) {
            this.label = label;
            this.description = description;
            this.specificityRank = specificityRank;
        }

        public static ScopeType parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return PATH_PATTERN;
            }
            for (ScopeType s : values()) {
                if (s.name().equalsIgnoreCase(raw.trim())) {
                    return s;
                }
            }
            return PATH_PATTERN;
        }
    }

    public enum Stage {
        PROXY_GATE("Cổng Proxy", "Đánh giá trước khi request rời Burp Proxy lên Server"),
        REPLAY_EXEC("Gửi / Replay", "Áp dụng khi bấm Gửi hoặc Gửi lại trong Extension"),
        BOTH("Cả Proxy & Replay", "Áp dụng đồng thời tại Cổng Proxy và lúc Gửi lại");

        public final String label;
        public final String description;

        Stage(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public boolean appliesToProxy() {
            return this == PROXY_GATE || this == BOTH;
        }

        public boolean appliesToReplay() {
            return this == REPLAY_EXEC || this == BOTH;
        }

        public static Stage parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return PROXY_GATE;
            }
            for (Stage s : values()) {
                if (s.name().equalsIgnoreCase(raw.trim())) {
                    return s;
                }
            }
            return PROXY_GATE;
        }
    }

    public enum ProxyEffect {
        PASS_NONE("Nguyên bản (Không can thiệp)", "Cho request đi thẳng lên Server bình thường, không chặn hay giả lập"),
        ALLOW_EXPLICIT("Cho qua luôn (ALLOW)", "Cho phép đi thẳng lên Server ngay lập tức, cắt ngang mọi luật chặn bên dưới"),
        INTERCEPT_WAIT("Chặn chờ duyệt (Đợi tôi bấm Gửi)", "Dừng request tại Proxy và đưa vào Hàng chờ Chặn để bạn xem/sửa rồi bấm Gửi hoặc Hủy"),
        HOLD_SPOOF("Giữ ở Proxy (Trả 200 ảo)", "Chặn trước khi tới Server, lưu vào Extension và trả 200 ảo về trình duyệt"),
        BLOCK_DROP("Chặn & Drop kết nối", "Chặn và ngắt kết nối ngay tại Proxy, vẫn ghi nhận request vào Extension");

        public final String label;
        public final String description;

        ProxyEffect(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public boolean isBlock() {
            return this == INTERCEPT_WAIT || this == HOLD_SPOOF || this == BLOCK_DROP;
        }

        public boolean isWait() {
            return this == INTERCEPT_WAIT;
        }

        public ActionInterceptRule.Effect toLegacyEffect() {
            return switch (this) {
                case INTERCEPT_WAIT -> ActionInterceptRule.Effect.INTERCEPT_WAIT;
                case HOLD_SPOOF -> ActionInterceptRule.Effect.BLOCK_SPOOF;
                case BLOCK_DROP -> ActionInterceptRule.Effect.BLOCK_DROP;
                case ALLOW_EXPLICIT -> ActionInterceptRule.Effect.ALLOW;
                case PASS_NONE -> null;
            };
        }

        public static ProxyEffect fromLegacy(ActionInterceptRule.Effect e) {
            if (e == null) {
                return PASS_NONE;
            }
            return switch (e) {
                case INTERCEPT_WAIT -> INTERCEPT_WAIT;
                case BLOCK_SPOOF -> HOLD_SPOOF;
                case BLOCK_DROP -> BLOCK_DROP;
                case ALLOW -> ALLOW_EXPLICIT;
            };
        }

        public static ProxyEffect parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return PASS_NONE;
            }
            for (ProxyEffect p : values()) {
                if (p.name().equalsIgnoreCase(raw.trim())) {
                    return p;
                }
            }
            return PASS_NONE;
        }
    }

    public enum PathMatchType {
        EXACT("Khớp chính xác"),
        PREFIX("Tiền tố (/api/...)"),
        WILDCARD("Wildcard (*)"),
        TEMPLATE("Template ({id})"),
        CONTAINS("Chứa chuỗi"),
        REGEX("Regex");

        public final String label;

        PathMatchType(String label) {
            this.label = label;
        }

        public static PathMatchType fromLegacy(ActionInterceptRule.PathMatchType t) {
            if (t == null) {
                return WILDCARD;
            }
            return switch (t) {
                case WILDCARD -> WILDCARD;
                case TEMPLATE -> TEMPLATE;
                case CONTAINS -> CONTAINS;
                case REGEX -> REGEX;
            };
        }

        public static PathMatchType parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return WILDCARD;
            }
            for (PathMatchType p : values()) {
                if (p.name().equalsIgnoreCase(raw.trim())) {
                    return p;
                }
            }
            return WILDCARD;
        }
    }

    private final String id;
    private String name;
    private String description;
    private boolean enabled;
    private int priority;

    private ScopeType scopeType;
    private final Set<String> targetBranches;
    private boolean includeSubBranches;
    private String hostPattern;
    private PathMatchType pathMatchType;
    private String pathPattern;
    private Integer targetApiGid;
    private final Set<String> methods;

    private Stage stage;
    private ProxyEffect proxyEffect;
    private ActionEngine.ActionMode replayMode;
    private int spoofStatus;
    private String spoofBody;
    private Integer producerGid;
    private String producerJsonPath;

    // Audit metadata
    private long createdAt;
    private long updatedAt;
    private String createdFrom;
    private long lastHitAt;
    private String lastHitSummary;
    private int hitCount;

    public ActionPolicy(
            String id,
            String name,
            String description,
            boolean enabled,
            int priority,
            ScopeType scopeType,
            Collection<String> targetBranches,
            boolean includeSubBranches,
            String hostPattern,
            PathMatchType pathMatchType,
            String pathPattern,
            Integer targetApiGid,
            Collection<String> methods,
            Stage stage,
            ProxyEffect proxyEffect,
            ActionEngine.ActionMode replayMode,
            int spoofStatus,
            String spoofBody,
            Integer producerGid,
            String producerJsonPath,
            long createdAt,
            long updatedAt,
            String createdFrom,
            long lastHitAt,
            String lastHitSummary,
            int hitCount) {
        long now = System.currentTimeMillis();
        this.id = (id == null || id.isBlank()) ? "pol-" + UUID.randomUUID().toString().substring(0, 8) : id.trim();
        this.name = Text.nz(name).trim();
        this.description = Text.nz(description).trim();
        this.enabled = enabled;
        this.priority = priority;
        this.scopeType = scopeType == null ? ScopeType.PATH_PATTERN : scopeType;
        this.targetBranches = new LinkedHashSet<>();
        if (targetBranches != null) {
            for (String b : targetBranches) {
                if (!Text.blank(b)) {
                    this.targetBranches.add(Hierarchy.normalize(b));
                }
            }
        }
        this.includeSubBranches = includeSubBranches;
        this.hostPattern = Text.nz(hostPattern).trim();
        this.pathMatchType = pathMatchType == null ? PathMatchType.WILDCARD : pathMatchType;
        this.pathPattern = Text.nz(pathPattern).trim();
        this.targetApiGid = targetApiGid;
        this.methods = new LinkedHashSet<>();
        if (methods != null) {
            for (String m : methods) {
                if (m != null && !m.isBlank()) {
                    this.methods.add(m.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        this.stage = stage == null ? Stage.PROXY_GATE : stage;
        this.proxyEffect = proxyEffect == null ? ProxyEffect.PASS_NONE : proxyEffect;
        this.replayMode = replayMode == null ? ActionEngine.ActionMode.NONE : replayMode;
        this.spoofStatus = (spoofStatus >= 100 && spoofStatus <= 599) ? spoofStatus : 200;
        this.spoofBody = (spoofBody == null || spoofBody.isBlank()) ? DEFAULT_SPOOF_BODY : spoofBody;
        this.producerGid = producerGid;
        this.producerJsonPath = Text.nz(producerJsonPath).trim();
        this.createdAt = createdAt > 0 ? createdAt : now;
        this.updatedAt = updatedAt > 0 ? updatedAt : this.createdAt;
        this.createdFrom = Text.blank(createdFrom) ? "Thủ công" : createdFrom.trim();
        this.lastHitAt = Math.max(0L, lastHitAt);
        this.lastHitSummary = Text.nz(lastHitSummary).trim();
        this.hitCount = Math.max(0, hitCount);
    }

    public ActionPolicy copy() {
        return new ActionPolicy(
                id, name, description, enabled, priority,
                scopeType, targetBranches, includeSubBranches, hostPattern,
                pathMatchType, pathPattern, targetApiGid, methods,
                stage, proxyEffect, replayMode, spoofStatus, spoofBody,
                producerGid, producerJsonPath,
                createdAt, updatedAt, createdFrom, lastHitAt, lastHitSummary, hitCount);
    }

    public ActionPolicy duplicateWithNewId() {
        long now = System.currentTimeMillis();
        String baseName = name();
        String dupName = baseName.endsWith("(Bản sao)") ? baseName : baseName + " (Bản sao)";
        return new ActionPolicy(
                null, dupName, description, enabled, priority + 1,
                scopeType, targetBranches, includeSubBranches, hostPattern,
                pathMatchType, pathPattern, targetApiGid, methods,
                stage, proxyEffect, replayMode, spoofStatus, spoofBody,
                producerGid, producerJsonPath,
                now, now, "Nhân bản", 0L, "", 0);
    }

    public static ActionPolicy fromLegacyRule(ActionInterceptRule rule, int priority) {
        if (rule == null) {
            return null;
        }
        ScopeType scope;
        List<String> branches = new ArrayList<>();
        if (!Text.blank(rule.branchScope())) {
            branches.add(rule.branchScope());
            scope = ScopeType.BRANCH;
        } else if (!Text.blank(rule.pathPattern())) {
            scope = ScopeType.PATH_PATTERN;
        } else if (!rule.methods().isEmpty() && rule.methods().size() < ALL_METHODS.size()) {
            scope = ScopeType.METHOD_MATRIX;
        } else {
            scope = ScopeType.GLOBAL;
        }
        long now = System.currentTimeMillis();
        return new ActionPolicy(
                rule.id(),
                rule.rawName(),
                "Di trú tự động từ Luật Chặn Proxy",
                rule.enabled(),
                priority,
                scope,
                branches,
                rule.includeSubBranches(),
                rule.hostPattern(),
                PathMatchType.fromLegacy(rule.pathMatchType()),
                rule.pathPattern(),
                null,
                rule.methods(),
                Stage.PROXY_GATE,
                ProxyEffect.fromLegacy(rule.effect()),
                ActionEngine.ActionMode.NONE,
                rule.spoofStatus(),
                rule.spoofBody(),
                null,
                "",
                now,
                now,
                "Di trú v2.4",
                0L,
                "",
                rule.hitCount());
    }

    public ActionInterceptRule toLegacyRule() {
        ActionInterceptRule.Effect eff = proxyEffect.toLegacyEffect();
        if (eff == null) {
            return null;
        }
        String firstBranch = targetBranches.isEmpty() ? "" : targetBranches.iterator().next();
        ActionInterceptRule.PathMatchType legacyPt = switch (pathMatchType) {
            case TEMPLATE -> ActionInterceptRule.PathMatchType.TEMPLATE;
            case CONTAINS -> ActionInterceptRule.PathMatchType.CONTAINS;
            case REGEX -> ActionInterceptRule.PathMatchType.REGEX;
            default -> ActionInterceptRule.PathMatchType.WILDCARD;
        };
        ActionInterceptRule r = new ActionInterceptRule(
                id, name, enabled, eff, firstBranch, includeSubBranches,
                hostPattern, legacyPt, pathPattern, methods, spoofStatus, spoofBody);
        return r;
    }

    // ---- Getters & Mutators
    public String id() { return id; }
    public String name() { return name.isEmpty() ? defaultDisplayName() : name; }
    public String rawName() { return name; }
    public String description() { return description; }
    public boolean enabled() { return enabled; }
    public int priority() { return priority; }
    public ScopeType scopeType() { return scopeType; }
    public Set<String> targetBranches() { return Collections.unmodifiableSet(targetBranches); }
    public boolean includeSubBranches() { return includeSubBranches; }
    public String hostPattern() { return hostPattern; }
    public PathMatchType pathMatchType() { return pathMatchType; }
    public String pathPattern() { return pathPattern; }
    public Integer targetApiGid() { return targetApiGid; }
    public Set<String> methods() { return Collections.unmodifiableSet(methods); }
    public Stage stage() { return stage; }
    public ProxyEffect proxyEffect() { return proxyEffect; }
    public ActionEngine.ActionMode replayMode() { return replayMode; }
    public int spoofStatus() { return spoofStatus; }
    public String spoofBody() { return spoofBody; }
    public Integer producerGid() { return producerGid; }
    public String producerJsonPath() { return producerJsonPath; }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }
    public String createdFrom() { return createdFrom; }
    public long lastHitAt() { return lastHitAt; }
    public String lastHitSummary() { return lastHitSummary; }
    public int hitCount() { return hitCount; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setPriority(int priority) {
        this.priority = Math.max(0, priority);
    }

    public void setProxyEffect(ProxyEffect effect) {
        if (effect != null) {
            this.proxyEffect = effect;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    public void setReplayMode(ActionEngine.ActionMode mode) {
        if (mode != null) {
            this.replayMode = mode;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    public void setTimestamps(long createdAt, long updatedAt) {
        if (createdAt > 0) this.createdAt = createdAt;
        if (updatedAt > 0) this.updatedAt = updatedAt;
    }

    public void recordHit(String summary) {
        this.hitCount++;
        this.lastHitAt = System.currentTimeMillis();
        this.lastHitSummary = Text.nz(summary).trim();
    }

    public void resetHit() {
        this.hitCount = 0;
        this.lastHitAt = 0L;
        this.lastHitSummary = "";
    }

    public static String formatTimestamp(long epochMs) {
        if (epochMs <= 0L) {
            return "-";
        }
        return TS_FMT.format(Instant.ofEpochMilli(epochMs));
    }

    public String defaultDisplayName() {
        String actionTxt;
        if (stage == Stage.REPLAY_EXEC) {
            actionTxt = replayMode.shortTitle();
        } else {
            actionTxt = switch (proxyEffect) {
                case INTERCEPT_WAIT -> "Chặn chờ duyệt";
                case HOLD_SPOOF -> "Giữ Proxy 200 ảo";
                case BLOCK_DROP -> "Chặn Drop";
                case ALLOW_EXPLICIT -> "Cho qua (ALLOW)";
                case PASS_NONE -> "Nguyên bản";
            };
        }
        return actionTxt + " [" + methodsLabel() + "] " + targetSummary();
    }

    public String methodsLabel() {
        if (methods.isEmpty() || methods.size() == ALL_METHODS.size()) {
            return "Mọi Method";
        }
        return String.join(", ", methods);
    }

    public String targetSummary() {
        return switch (scopeType) {
            case GLOBAL -> "Toàn hệ thống";
            case METHOD_MATRIX -> "Mọi đường dẫn (" + methodsLabel() + ")";
            case BRANCH -> {
                if (targetBranches.isEmpty()) {
                    yield "Mọi nhánh";
                }
                String first = targetBranches.iterator().next();
                String extra = targetBranches.size() > 1 ? " (+" + (targetBranches.size() - 1) + " nhánh)" : "";
                String sub = includeSubBranches ? " + con" : "";
                String pfx = !pathPattern.isEmpty() ? " · " + pathPattern : "";
                yield first + extra + sub + pfx;
            }
            case PATH_PATTERN -> {
                String p = pathPattern.isEmpty() ? "*" : pathPattern;
                String h = hostPattern.isEmpty() ? "" : hostPattern + " ";
                yield h + p + " [" + pathMatchType.name() + "]";
            }
            case API_EXACT -> {
                String gidTxt = targetApiGid != null ? Text.apiLabel(targetApiGid) + " " : "";
                String p = pathPattern.isEmpty() ? "(chưa rõ path)" : pathPattern;
                yield gidTxt + p;
            }
        };
    }

    public String behaviorSummary() {
        if (stage == Stage.PROXY_GATE) {
            return proxyEffect.label;
        }
        if (stage == Stage.REPLAY_EXEC) {
            return "Replay: " + replayMode.shortTitle();
        }
        return proxyEffect.label + " | Replay: " + replayMode.shortTitle();
    }

    /**
     * Evaluates whether this policy matches the given request/API context.
     */
    public boolean matches(String activeBranch, String host, String method, String rawPath, Integer apiGid) {
        if (!enabled) {
            return false;
        }
        // 1. Method filter
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!methods.isEmpty() && !methods.contains(m)) {
            return false;
        }
        // 2. Host filter (if specified)
        if (!hostPattern.isEmpty() && !matchesHost(host, hostPattern)) {
            return false;
        }
        // 3. Scope-specific filter
        return switch (scopeType) {
            case GLOBAL, METHOD_MATRIX -> pathPattern.isEmpty() || matchesPath(rawPath, pathMatchType, pathPattern);
            case BRANCH -> {
                if (!matchesAnyBranch(activeBranch)) {
                    yield false;
                }
                yield pathPattern.isEmpty() || matchesPath(rawPath, pathMatchType, pathPattern);
            }
            case PATH_PATTERN -> {
                if (!targetBranches.isEmpty() && !matchesAnyBranch(activeBranch)) {
                    yield false;
                }
                yield pathPattern.isEmpty() || matchesPath(rawPath, pathMatchType, pathPattern);
            }
            case API_EXACT -> {
                if (apiGid != null && targetApiGid != null && apiGid.equals(targetApiGid)) {
                    yield true;
                }
                if (!pathPattern.isEmpty()) {
                    String normIncoming = ActionEngine.normalizePathTemplate(rawPath);
                    String normTarget = ActionEngine.normalizePathTemplate(pathPattern);
                    yield normIncoming.equalsIgnoreCase(normTarget);
                }
                yield false;
            }
        };
    }

    private boolean matchesAnyBranch(String activeBranch) {
        if (targetBranches.isEmpty()) {
            return true;
        }
        String cur = Text.blank(activeBranch) ? "" : Hierarchy.normalize(activeBranch);
        if (cur.isEmpty()) {
            return false;
        }
        String curLow = cur.toLowerCase(Locale.ROOT);
        for (String b : targetBranches) {
            String bLow = b.toLowerCase(Locale.ROOT);
            if (curLow.equals(bLow)) {
                return true;
            }
            if (includeSubBranches && curLow.startsWith(bLow + " > ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesHost(String host, String pattern) {
        String h = Text.nz(host).trim().toLowerCase(Locale.ROOT);
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        if (p.equals("*") || p.isEmpty()) {
            return true;
        }
        if (p.contains("*")) {
            return wildcardToRegex(p).matcher(h).matches();
        }
        return h.equals(p) || h.endsWith("." + p);
    }

    public static boolean matchesPath(String rawPath, PathMatchType type, String pattern) {
        if (pattern == null || pattern.isBlank() || "*".equals(pattern.trim())) {
            return true;
        }
        String cleanPath = Text.stripQuery(Text.nz(rawPath));
        String fullPath = Text.nz(rawPath);
        String pat = pattern.trim();
        return switch (type) {
            case EXACT -> cleanPath.equalsIgnoreCase(Text.stripQuery(pat)) || fullPath.equalsIgnoreCase(pat);
            case PREFIX -> {
                String cleanPat = Text.stripQuery(pat).toLowerCase(Locale.ROOT);
                String cp = cleanPath.toLowerCase(Locale.ROOT);
                yield cp.equals(cleanPat) || cp.startsWith(cleanPat.endsWith("/") ? cleanPat : cleanPat + "/");
            }
            case CONTAINS -> fullPath.toLowerCase(Locale.ROOT).contains(pat.toLowerCase(Locale.ROOT));
            case REGEX -> {
                try {
                    yield Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(fullPath).find();
                } catch (RuntimeException ignored) {
                    yield false;
                }
            }
            case TEMPLATE -> {
                String normPath = PathMatch.normalize(cleanPath);
                PathMatch.Template tpl = PathMatch.template(pat, false);
                if (tpl.exact().matcher(normPath).matches()) {
                    yield true;
                }
                yield ActionEngine.normalizePathTemplate(cleanPath)
                        .equalsIgnoreCase(ActionEngine.normalizePathTemplate(pat));
            }
            case WILDCARD -> {
                if (!pat.contains("*")) {
                    String cp = cleanPath.toLowerCase(Locale.ROOT);
                    String lp = pat.toLowerCase(Locale.ROOT);
                    yield cp.equals(lp) || cp.startsWith(lp.endsWith("/") ? lp : lp + "/");
                }
                yield wildcardToRegex(pat.toLowerCase(Locale.ROOT)).matcher(cleanPath.toLowerCase(Locale.ROOT)).matches()
                        || wildcardToRegex(pat.toLowerCase(Locale.ROOT)).matcher(fullPath.toLowerCase(Locale.ROOT)).matches();
            }
        };
    }

    private static Pattern wildcardToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*') {
                sb.append(".*");
            } else if ("\\.[]{}()+-?^$|".indexOf(ch) >= 0) {
                sb.append('\\').append(ch);
            } else {
                sb.append(ch);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    public String buildSpoofResponse() {
        String body = (spoofBody == null || spoofBody.isBlank()) ? DEFAULT_SPOOF_BODY : spoofBody;
        int len = body.getBytes(StandardCharsets.UTF_8).length;
        String reason = switch (spoofStatus) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 403 -> "Forbidden";
            default -> "OK";
        };
        if (spoofStatus == 204) {
            return "HTTP/1.1 204 No Content\r\n"
                    + "X-JCrawl-Held: true\r\n"
                    + "X-JCrawl-Policy: " + id + "\r\n"
                    + "Content-Length: 0\r\n\r\n";
        }
        return "HTTP/1.1 " + spoofStatus + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "X-JCrawl-Held: true\r\n"
                + "X-JCrawl-Policy: " + id + "\r\n"
                + "Content-Length: " + len + "\r\n\r\n"
                + body;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("enabled", enabled);
        m.put("priority", priority);
        m.put("scope_type", scopeType.name());
        m.put("target_branches", new ArrayList<>(targetBranches));
        m.put("include_sub_branches", includeSubBranches);
        m.put("host_pattern", hostPattern);
        m.put("path_match_type", pathMatchType.name());
        m.put("path_pattern", pathPattern);
        if (targetApiGid != null) {
            m.put("target_api_gid", targetApiGid);
        }
        m.put("methods", new ArrayList<>(methods));
        m.put("stage", stage.name());
        m.put("proxy_effect", proxyEffect.name());
        m.put("replay_mode", replayMode.name());
        m.put("spoof_status", spoofStatus);
        m.put("spoof_body", spoofBody);
        if (producerGid != null) {
            m.put("producer_gid", producerGid);
        }
        if (!producerJsonPath.isEmpty()) {
            m.put("producer_json_path", producerJsonPath);
        }
        m.put("created_at", createdAt);
        m.put("updated_at", updatedAt);
        m.put("created_from", createdFrom);
        m.put("last_hit_at", lastHitAt);
        m.put("last_hit_summary", lastHitSummary);
        m.put("hit_count", hitCount);
        return m;
    }

    public static ActionPolicy fromMap(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        Set<String> branches = new LinkedHashSet<>();
        Object tb = map.get("target_branches");
        if (tb instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    branches.add(String.valueOf(item));
                }
            }
        }
        Set<String> meths = new LinkedHashSet<>();
        Object mo = map.get("methods");
        if (mo instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    meths.add(String.valueOf(item));
                }
            }
        }
        ActionEngine.ActionMode rm = ActionEngine.ActionMode.NONE;
        String rmRaw = str(map.get("replay_mode"));
        if (!rmRaw.isEmpty()) {
            try {
                rm = ActionEngine.ActionMode.valueOf(rmRaw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                rm = ActionEngine.ActionMode.NONE;
            }
        }
        return new ActionPolicy(
                str(map.get("id")),
                str(map.get("name")),
                str(map.get("description")),
                bool(map.get("enabled"), true),
                numInt(map.get("priority"), 100),
                ScopeType.parse(str(map.get("scope_type"))),
                branches,
                bool(map.get("include_sub_branches"), true),
                str(map.get("host_pattern")),
                PathMatchType.parse(str(map.get("path_match_type"))),
                str(map.get("path_pattern")),
                nullableInt(map.get("target_api_gid")),
                meths,
                Stage.parse(str(map.get("stage"))),
                ProxyEffect.parse(str(map.get("proxy_effect"))),
                rm,
                numInt(map.get("spoof_status"), 200),
                str(map.get("spoof_body")),
                nullableInt(map.get("producer_gid")),
                str(map.get("producer_json_path")),
                numLong(map.get("created_at"), 0L),
                numLong(map.get("updated_at"), 0L),
                str(map.get("created_from")),
                numLong(map.get("last_hit_at"), 0L),
                str(map.get("last_hit_summary")),
                numInt(map.get("hit_count"), 0));
    }

    public static String listToJson(List<ActionPolicy> policies) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (policies != null) {
            for (ActionPolicy p : policies) {
                if (p != null) {
                    out.add(p.toMap());
                }
            }
        }
        return Json.pretty(out);
    }

    public static List<ActionPolicy> listFromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = Json.parse(json);
            if (!(parsed instanceof List<?> list)) {
                return List.of();
            }
            List<ActionPolicy> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    ActionPolicy p = fromMap(m);
                    if (p != null) {
                        out.add(p);
                    }
                }
            }
            return out;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o, boolean fallback) {
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(String.valueOf(o));
        return fallback;
    }

    private static int numInt(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Integer nullableInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            String s = String.valueOf(o).trim();
            return s.isEmpty() ? null : Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long numLong(Object o, long fallback) {
        if (o instanceof Number n) return n.longValue();
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
