package themythofcrawl.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import themythofcrawl.app.Engine;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleResult;

/**
 * V_7.0 role access matrix: for every API × role it combines
 * <ul>
 *   <li><b>Thấy bởi</b> — the role's own UI reached the endpoint while crawling (intended access), and</li>
 *   <li><b>Gọi được</b> — replaying the request with the role's token returned 2xx with the same content
 *       as the crawl account (actual access).</li>
 * </ul>
 * Actual minus intended = Broken Access Control candidates. Optional {@link ExpectedAccess} rules turn
 * a candidate into a confirmed violation (and cover roles whose UI shows everything).
 */
public final class AccessMatrix {
    /** Body similarity at or above which a role's 2xx counts as "the same data" as the crawl account's. */
    public static final double SAME_CONTENT = 0.75;

    public enum Verdict {
        SEEN_OK("OK", "Thấy · Gọi được", "Role thấy chức năng trong UI và gọi được — hợp lệ", AuditLevel.OK, false),
        DENIED_OK("Chặn", "Không thấy · Bị chặn", "Role không thấy chức năng và server chặn — hợp lệ", AuditLevel.OK, false),
        BAC("NGHI BAC", "NGHI BAC", "Role KHÔNG thấy chức năng trong UI nhưng gọi được 2xx với cùng nội dung", AuditLevel.WARN, true),
        VIOLATION("VI PHẠM", "VI PHẠM quyền", "Theo Quyền mong đợi role này không được dùng nhánh này nhưng vẫn gọi được", AuditLevel.WARN, true),
        DIFF("2xx khác", "2xx · nội dung khác", "Gọi được 2xx nhưng nội dung khác bản gốc (trang lỗi/login/dữ liệu riêng) — xem lại",
                AuditLevel.INFO, false),
        SEEN_DENIED("Thấy·bị chặn", "Thấy nhưng bị chặn", "Role thấy/được phép nhưng bị chặn — token hết hạn hoặc thiếu quyền?",
                AuditLevel.INFO, false),
        UNKNOWN_OK("2xx ?", "Gọi được (role chưa crawl)", "Role chưa crawl nên chưa biết UI có cho thấy không — crawl role này để kết luận",
                AuditLevel.REDIRECT, false),
        STALE_ID("ID đã dùng", "Chưa kết luận: ID đã dùng",
                "404 / 410 vì ID đã bị xóa bởi lần gửi trước (cùng ID), không phải vì bị chặn. Gửi lại bằng 'Mỗi vai trò 1 bản ghi'.",
                AuditLevel.INFO, false),
        ERROR("Lỗi", "Lỗi", "Lỗi gọi API / 5xx", AuditLevel.ERROR, false),
        NOT_SENT("-", "Chưa gửi", "Chưa gửi request của role này", AuditLevel.NONE, false);

        /** Compact text for matrix cells. */
        public final String shortLabel;
        public final String label;
        public final String explain;
        public final AuditLevel level;
        public final boolean finding;

        Verdict(String shortLabel, String label, String explain, AuditLevel level, boolean finding) {
            this.shortLabel = shortLabel;
            this.label = label;
            this.explain = explain;
            this.level = level;
            this.finding = finding;
        }
    }

    public record Cell(String role, Verdict verdict, Integer status, double similarity) {
        public String shortText() {
            String st = status != null ? String.valueOf(status) : "";
            return switch (verdict) {
                case NOT_SENT -> "-";
                case BAC, VIOLATION -> "!! " + st + (similarity >= 0 ? " ~" + Math.round(similarity * 100) + "%" : "");
                default -> st;
            };
        }
    }

    public record Row(ApiGroup group, Map<String, Cell> cells) {
        public boolean hasFinding() {
            for (Cell c : cells.values()) {
                if (c.verdict().finding) {
                    return true;
                }
            }
            return false;
        }
    }

    /** One BAC test case. */
    public record Finding(int gid, String method, String host, String path, String branch, String role,
                          Set<String> seenBy, Integer status, double similarity, Verdict verdict) {
        public String testCaseId() {
            return "BAC-" + gid + "-" + role.replaceAll("[^A-Za-z0-9]+", "");
        }

        public String evidence() {
            return "Role " + role + " gọi " + method + " " + path + " -> HTTP " + status + ", giống bản gốc "
                    + Math.round(similarity * 100) + "%; " + (seenBy.isEmpty() ? "chưa role nào thấy trong UI"
                    : "chỉ " + String.join(", ", seenBy) + " thấy trong UI") + ".";
        }
    }

    private final List<String> roles;
    private final List<Row> rows;
    private final List<Finding> findings;
    private final Set<String> crawledRoles;

    private AccessMatrix(List<String> roles, List<Row> rows, List<Finding> findings, Set<String> crawledRoles) {
        this.roles = roles;
        this.rows = rows;
        this.findings = findings;
        this.crawledRoles = crawledRoles;
    }

    public List<String> roles() {
        return roles;
    }

    public List<Row> rows() {
        return rows;
    }

    public List<Finding> findings() {
        return findings;
    }

    public Set<String> crawledRoles() {
        return crawledRoles;
    }

    public static AccessMatrix build(Engine engine, Collection<ApiGroup> groups) {
        List<String> roleNames = new ArrayList<>();
        for (Role r : engine.roles().all()) {
            if (r.enabled()) {
                roleNames.add(r.name());
            }
        }
        Set<String> crawled = engine.crawledRoles();
        ExpectedAccess expected = engine.expectedAccess();
        List<Row> rows = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        for (ApiGroup g : groups) {
            byte[] origResp = engine.responseOf(g, g.origResult());
            Map<String, Cell> cells = new LinkedHashMap<>();
            ExpectedAccess.Rule rule = expected.match(g.branch());
            for (RoleResult r : g.results()) {
                String role = r.roleKey();
                if (!roleNames.contains(role)) {
                    roleNames.add(role);
                }
                Cell c = evaluate(engine, g, r, role, origResp, crawled, rule);
                cells.put(role, c);
                r.setAccessBadge(badgeOf(c.verdict(), c.status(), c.similarity()));
                if (c.verdict().finding) {
                    findings.add(new Finding(g.gid(), g.method(), g.service().hostLabel(), g.path(), g.branch(), role,
                            g.seenBy(), c.status(), c.similarity(), c.verdict()));
                }
            }
            rows.add(new Row(g, cells));
            Audit.recalc(g);
        }
        return new AccessMatrix(List.copyOf(roleNames), rows, findings, crawled);
    }

    static Cell evaluate(Engine engine, ApiGroup g, RoleResult r, String role, byte[] origResp, Set<String> crawled,
                         ExpectedAccess.Rule rule) {
        Integer st = r.status();
        if (ResultState.ERROR.equals(r.state()) || (st != null && st >= 500)) {
            return new Cell(role, Verdict.ERROR, st, -1);
        }
        if (st == null) {
            return new Cell(role, Verdict.NOT_SENT, null, -1);
        }
        boolean ok = st >= 200 && st < 300;
        if ((st == 404 || st == 410) && staleId(engine, g, r)) {
            return new Cell(role, Verdict.STALE_ID, st, -1);
        }
        boolean seen = g.seenBy(role);
        double sim = ok ? similarity(origResp, engine.responseOf(g, r)) : -1;
        boolean same = sim >= SAME_CONTENT;
        Verdict v;
        if (rule != null) {
            boolean allowed = rule.allows(role);
            if (allowed) {
                v = ok ? Verdict.SEEN_OK : Verdict.SEEN_DENIED;
            } else if (ok) {
                v = same ? Verdict.VIOLATION : Verdict.DIFF;
            } else {
                v = Verdict.DENIED_OK;
            }
        } else if (ok) {
            if (seen) {
                v = Verdict.SEEN_OK;
            } else if (!crawled.contains(role)) {
                v = Verdict.UNKNOWN_OK;
            } else {
                v = same ? Verdict.BAC : Verdict.DIFF;
            }
        } else {
            v = seen ? Verdict.SEEN_DENIED : Verdict.DENIED_OK;
        }
        return new Cell(role, v, st, sim);
    }

    /**
     * V_8.2: a DELETE-like row that got 404 / 410 for an ID another row (or the captured request) already used
     * successfully: the object was simply gone, the server never had to decide about this role.
     */
    static boolean staleId(Engine engine, ApiGroup g, RoleResult r) {
        if (!ActionEngine.isActionMethod(g.method())) {
            return false;
        }
        String used = usedId(engine, g, r);
        if (used == null) {
            return false;
        }
        for (RoleResult o : g.entries()) {
            if (o == r || o.status() == null || o.status() < 200 || o.status() >= 300) {
                continue;
            }
            if (o.original() && g.heldAtProxy()) {
                continue;   // captured while held: the server never executed it
            }
            if (used.equals(usedId(engine, g, o))) {
                return true;
            }
        }
        return false;
    }

    static String usedId(Engine engine, ApiGroup g, RoleResult r) {
        byte[] req = engine.shownRequestOf(g, r);
        if (req == null) {
            return null;
        }
        String head = new String(req, 0, Math.min(req.length, 2048), StandardCharsets.ISO_8859_1);
        int sp1 = head.indexOf(' ');
        int sp2 = sp1 < 0 ? -1 : head.indexOf(' ', sp1 + 1);
        String path = sp1 > 0 && sp2 > sp1 ? head.substring(sp1 + 1, sp2) : g.path();
        List<String> ids = engine.actionEngine().detectIds(path, req);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Text cached on a row for the Cây / API colours; null = nothing to say beyond the HTTP status. */
    public static String badgeOf(Verdict v, Integer st, double sim) {
        if (v == null) {
            return null;
        }
        return switch (v) {
            case BAC, VIOLATION -> v.shortLabel + ": " + st + (sim >= 0 ? " ~" + Math.round(sim * 100) + "% giống" : "");
            case STALE_ID -> "Chưa kết luận: ID đã dùng (" + st + ")";
            default -> null;
        };
    }

    /** Re-evaluate every role row of one API (right after a send) so the Cây / API colours show the verdict. */
    public static void refreshBadges(Engine engine, ApiGroup g) {
        byte[] origResp = engine.responseOf(g, g.origResult());
        Set<String> crawled = engine.crawledRoles();
        ExpectedAccess.Rule rule = engine.expectedAccess().match(g.branch());
        for (RoleResult r : g.results()) {
            if (r.status() == null) {
                r.setAccessBadge(null);
                continue;
            }
            Cell c = evaluate(engine, g, r, r.roleKey(), origResp, crawled, rule);
            r.setAccessBadge(badgeOf(c.verdict(), c.status(), c.similarity()));
        }
    }

    /**
     * Token-set Jaccard similarity of two HTTP message bodies (numbers normalised, case-insensitive).
     * 1.0 = same words, 0.0 = nothing in common. Two empty bodies are identical.
     */
    public static double similarity(byte[] msgA, byte[] msgB) {
        Set<String> a = tokens(body(msgA));
        Set<String> b = tokens(body(msgB));
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 1.0 : (double) inter.size() / union.size();
    }

    private static String body(byte[] msg) {
        if (msg == null || msg.length == 0) {
            return "";
        }
        int off = Math.max(0, Math.min(HttpBytes.bodyOffset(msg), msg.length));
        int len = Math.min(msg.length - off, 256 * 1024);
        return new String(msg, off, len, StandardCharsets.ISO_8859_1);
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        String norm = s.toLowerCase(Locale.ROOT).replaceAll("[0-9]+", "0");
        for (String t : norm.split("[^a-z0-9_]+")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ exports
    /** Test cases as JSON (for the test-management server / Logger++ notes). */
    public String findingsJson() {
        List<Object> out = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.testCaseId());
            m.put("type", f.verdict() == Verdict.VIOLATION ? "Vertical/Horizontal BAC (vi phạm quyền mong đợi)"
                    : "Nghi BAC (role không thấy trong UI)");
            m.put("api", f.gid());
            m.put("method", f.method());
            m.put("host", f.host());
            m.put("path", f.path());
            m.put("branch", f.branch());
            m.put("attacker_role", f.role());
            m.put("owner_roles", new ArrayList<>(f.seenBy()));
            m.put("status", f.status());
            m.put("similarity", Math.round(f.similarity() * 100));
            m.put("evidence", f.evidence());
            out.add(m);
        }
        return Json.write(out);
    }

    /** Rows for an Excel sheet: header + one row per API with each role's verdict. */
    public List<List<Object>> matrixRows() {
        List<List<Object>> out = new ArrayList<>();
        for (Row r : rows) {
            List<Object> line = new ArrayList<>();
            ApiGroup g = r.group();
            line.add(g.gid());
            line.add(g.method());
            line.add(g.service().hostLabel());
            line.add(g.path());
            line.add(g.branch());
            line.add(String.join(", ", g.seenBy()));
            for (String role : roles) {
                Cell c = r.cells().get(role);
                line.add(c == null ? "" : c.verdict().label + (c.status() != null ? " (" + c.status() + ")" : ""));
            }
            out.add(line);
        }
        return out;
    }

    public List<String> matrixHeader() {
        List<String> h = new ArrayList<>(List.of("API #", "Method", "Host", "Path", "Nhánh", "Thấy bởi"));
        h.addAll(roles);
        return h;
    }

    public List<List<Object>> findingRows() {
        List<List<Object>> out = new ArrayList<>();
        for (Finding f : findings) {
            out.add(List.of(f.testCaseId(), f.verdict().label, f.gid(), f.method(), f.host(), f.path(), f.branch(),
                    f.role(), String.join(", ", f.seenBy()), f.status() == null ? "" : f.status(),
                    Math.round(f.similarity() * 100) + "%", f.evidence(), "Chưa test", ""));
        }
        return out;
    }

    public static List<String> findingHeader() {
        return List.of("Test case", "Loại", "API #", "Method", "Host", "Path", "Nhánh", "Role tấn công", "Thấy bởi",
                "HTTP", "Giống gốc", "Bằng chứng", "Trạng thái", "Người test");
    }
}
