package themythofcrawl.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ActionPolicy.PathMatchType;
import themythofcrawl.model.ActionPolicy.ProxyEffect;
import themythofcrawl.model.ActionPolicy.ScopeType;
import themythofcrawl.model.ActionPolicy.Stage;

/**
 * V_7.1 "Cổng chặn Proxy": the ONE main switch for stopping browser requests at Burp, answered as three
 * explicit questions instead of a list of modes:
 * <ol>
 *   <li><b>Chặn request nào?</b> mọi host / một host / một host + path / một nhánh đang crawl</li>
 *   <li><b>Method nào?</b> (mặc định POST, PUT, PATCH, DELETE — không chặn GET)</li>
 *   <li><b>Làm gì với request bị chặn?</b> Hỏi tôi trước / Giữ lại + trả 200 giả cho trình duyệt / Chặn hẳn</li>
 * </ol>
 * It is stored as a single rule (fixed id {@link #ID}) in the {@link ActionPolicyLedger}, so it persists,
 * shows up in "Thử luật" and coexists with more specific rules (which still win).
 */
public final class ProxyGate {
    public static final String ID = "gate-main";
    public static final String SOURCE = "Cổng chặn Proxy";

    public enum Scope {
        ALL_HOSTS("Mọi host"),
        HOST("Chỉ 1 host"),
        HOST_PATH("1 host + path"),
        BRANCH("Chỉ khi đang crawl ở 1 nhánh");

        public final String label;

        Scope(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Effect {
        ASK("Hỏi tôi trước", ProxyEffect.INTERCEPT_WAIT,
                "Request DỪNG ở Burp. Bạn xem / sửa rồi bấm Gửi (mới tới Server) hoặc Hủy. Trình duyệt đứng chờ (tối đa 10 phút).",
                "Muốn tự quyết từng request."),
        FAKE_200("Giữ lại, trả 200 giả cho trình duyệt", ProxyEffect.HOLD_SPOOF,
                "Server KHÔNG nhận gì. Trình duyệt nhận 200 giả nên tưởng thành công và chạy tiếp. Request được lưu vào JCrawl"
                        + " để gửi tay / gửi theo role sau.",
                "Crawl nút Xóa / Hủy / Thanh toán mà không muốn dữ liệu thật bị đổi."),
        DROP("Chặn hẳn (cắt kết nối)", ProxyEffect.BLOCK_DROP,
                "Server KHÔNG nhận gì. Trình duyệt báo lỗi mạng. Request vẫn được lưu vào JCrawl.",
                "Chặn chắc chắn, không cần trình duyệt chạy tiếp.");

        public final String label;
        public final ProxyEffect effect;
        public final String what;
        public final String when;

        Effect(String label, ProxyEffect effect, String what, String when) {
            this.label = label;
            this.effect = effect;
            this.what = what;
            this.when = when;
        }

        @Override
        public String toString() {
            return label;
        }

        static Effect of(ProxyEffect e) {
            for (Effect x : values()) {
                if (x.effect == e) {
                    return x;
                }
            }
            return ASK;
        }
    }

    /** The three answers + on/off. */
    public record Settings(boolean on, Scope scope, String host, String path, String branch, Set<String> methods,
                           Effect effect) {
        public Settings {
            scope = scope == null ? Scope.ALL_HOSTS : scope;
            host = host == null ? "" : host.trim();
            path = path == null ? "" : path.trim();
            branch = branch == null ? "" : branch.trim();
            Set<String> ms = new LinkedHashSet<>();
            if (methods != null) {
                for (String m : methods) {
                    if (m != null && !m.isBlank()) {
                        ms.add(m.trim().toUpperCase(Locale.ROOT));
                    }
                }
            }
            methods = Set.copyOf(ms);
            effect = effect == null ? Effect.ASK : effect;
        }

        public static Settings defaults() {
            return new Settings(false, Scope.ALL_HOSTS, "", "", "", new LinkedHashSet<>(List.of("POST", "PUT", "PATCH", "DELETE")),
                    Effect.ASK);
        }

        public Settings withOn(boolean v) {
            return new Settings(v, scope, host, path, branch, methods, effect);
        }

        /** "Chặn POST/PUT/PATCH/DELETE tới api.shop.vn/api/orders/* -> Hỏi tôi trước". */
        public String sentence() {
            if (!on) {
                return "Cổng chặn đang TẮT — mọi request đi thẳng lên Server như bình thường.";
            }
            String ms = methods.isEmpty() ? "mọi method" : String.join("/", ordered(methods));
            String where = switch (scope) {
                case ALL_HOSTS -> "tới MỌI host";
                case HOST -> "tới host " + (host.isEmpty() ? "(chưa chọn)" : host);
                case HOST_PATH -> "tới " + (host.isEmpty() ? "(chưa chọn host)" : host) + (path.isEmpty() ? "/*" : path);
                case BRANCH -> "khi đang crawl ở nhánh " + (branch.isEmpty() ? "(chưa chọn)" : branch);
            };
            return "Chặn " + ms + " " + where + " -> " + effect.label + ". Request khác đi thẳng lên Server.";
        }

        /** Missing input that makes the settings unusable, or null. */
        public String problem() {
            if ((scope == Scope.HOST || scope == Scope.HOST_PATH) && host.isEmpty()) {
                return "Chưa chọn host";
            }
            if (scope == Scope.HOST_PATH && path.isEmpty()) {
                return "Chưa nhập path";
            }
            if (scope == Scope.BRANCH && branch.isEmpty()) {
                return "Chưa chọn nhánh";
            }
            if (methods.isEmpty()) {
                return "Chưa chọn method nào";
            }
            return null;
        }
    }

    private static final List<String> ORDER = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    static List<String> ordered(Set<String> ms) {
        List<String> out = new java.util.ArrayList<>();
        for (String m : ORDER) {
            if (ms.contains(m)) {
                out.add(m);
            }
        }
        for (String m : ms) {
            if (!out.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    private ProxyGate() {
    }

    /** Current gate settings read back from the ledger (defaults, switched off, when never set). */
    public static Settings read(ActionPolicyLedger ledger) {
        ActionPolicy p = ledger.findById(ID);
        if (p == null) {
            return Settings.defaults();
        }
        Scope scope;
        String host = p.hostPattern();
        String path = "";
        String branch = "";
        switch (p.scopeType()) {
            case BRANCH -> {
                scope = Scope.BRANCH;
                branch = p.targetBranches().isEmpty() ? "" : p.targetBranches().iterator().next();
            }
            case PATH_PATTERN -> {
                boolean anyPath = p.pathPattern().isEmpty() || "*".equals(p.pathPattern());
                scope = anyPath ? Scope.HOST : Scope.HOST_PATH;
                path = anyPath ? "" : p.pathPattern();
            }
            default -> scope = Scope.ALL_HOSTS;
        }
        return new Settings(p.enabled(), scope, host, path, branch, p.methods(), Effect.of(p.proxyEffect()));
    }

    /** Writes the gate as the single rule {@link #ID} (replacing the previous one, keeping its hit count). */
    public static ActionPolicy toPolicy(Settings s) {
        long now = System.currentTimeMillis();
        ScopeType st;
        List<String> branches = List.of();
        String host = "";
        String path = "";
        PathMatchType pmt = PathMatchType.WILDCARD;
        switch (s.scope()) {
            case HOST -> {
                st = ScopeType.PATH_PATTERN;
                host = s.host();
                path = "*";
            }
            case HOST_PATH -> {
                st = ScopeType.PATH_PATTERN;
                host = s.host();
                path = s.path().startsWith("/") ? s.path() : "/" + s.path();
                pmt = path.contains("*") ? PathMatchType.WILDCARD : PathMatchType.PREFIX;
            }
            case BRANCH -> {
                st = ScopeType.BRANCH;
                branches = List.of(Hierarchy.normalize(s.branch()));
            }
            default -> st = ScopeType.GLOBAL;
        }
        return new ActionPolicy(ID, "Chặn chính: " + s.sentence(), "Thiết lập ở nút Chặn trên thanh trên", s.on(), -1, st, branches, true, host,
                pmt, path, null, s.methods(), Stage.PROXY_GATE, s.effect().effect, ActionEngine.ActionMode.NONE, 200, null,
                null, "", now, now, SOURCE, 0L, "", 0);
    }

    public static void write(ActionPolicyLedger ledger, Settings s) {
        ledger.upsert(toPolicy(s));
    }
}
