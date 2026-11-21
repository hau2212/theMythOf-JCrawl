package themythofcrawl.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import themythofcrawl.model.ActionInterceptRule;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ActionPolicy.PathMatchType;
import themythofcrawl.model.ActionPolicy.ProxyEffect;
import themythofcrawl.model.ActionPolicy.ScopeType;
import themythofcrawl.model.ActionPolicy.Stage;

/**
 * V_7.0 "Bảo vệ dữ liệu": the Action controls reduced to TWO plain questions, written into ONE list of
 * rules (the {@link ActionPolicyLedger}):
 * <ol>
 *   <li><b>Khi trình duyệt gửi request thay đổi dữ liệu</b> (POST / PUT / PATCH / DELETE): Cho đi bình thường /
 *       Hỏi tôi trước / Giữ lại, không gửi — for everything, a method, a branch or one API.</li>
 *   <li><b>Khi gửi lại mà ID đã bị xóa</b>: Gửi nguyên bản / Thay ID còn sống / Tạo bản ghi mới rồi gửi /
 *       Dùng lại kết quả 200 cũ (see {@link Resend}).</li>
 * </ol>
 * {@link #migrateLegacy} folds every older switch (global hold, per-endpoint hold, intercept-wait by
 * method / branch / endpoint, legacy proxy rules) into that same list so there is one place to look.
 */
public final class DataProtection {
    public static final String SOURCE = "Bảo vệ dữ liệu";

    /** Answer to question 1. */
    public enum Choice {
        PASS("Cho đi bình thường", ProxyEffect.PASS_NONE),
        ASK("Hỏi tôi trước (hàng chờ, bấm Gửi / Hủy)", ProxyEffect.INTERCEPT_WAIT),
        HOLD("Giữ lại, không gửi (trình duyệt nhận 200 giả)", ProxyEffect.HOLD_SPOOF),
        ALLOW("Luôn cho qua (ngoại lệ, thắng mọi luật khác)", ProxyEffect.ALLOW_EXPLICIT);

        public final String label;
        public final ProxyEffect effect;

        Choice(String label, ProxyEffect effect) {
            this.label = label;
            this.effect = effect;
        }

        @Override
        public String toString() {
            return label;
        }

        public static Choice of(ProxyEffect e) {
            for (Choice c : values()) {
                if (c.effect == e) {
                    return c;
                }
            }
            return e != null && e.isBlock() ? HOLD : PASS;
        }
    }

    /** Where a rule applies. */
    public enum Scope {
        ALL("Mọi request thay đổi dữ liệu"),
        METHOD("Một method"),
        BRANCH("Một nhánh (kèm nhánh con)"),
        API("Riêng một API");

        public final String label;

        Scope(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Answer to question 2 ("Cách gửi lại"), mapped onto the existing per-API Action modes. */
    public enum Resend {
        AUTO("Tự động", ActionEngine.ActionMode.AUTO_SMART,
                "Kết quả lần gửi đầu + Kho ID sống + API tạo mới (nếu đã nhận ra)",
                "Lần đầu 2xx mà giờ 4xx -> thử thay ID còn sống; không có thì gọi API tạo mới lấy ID mới rồi gửi lại"),
        RAW("Gửi nguyên bản", ActionEngine.ActionMode.NONE,
                "Request y nguyên như lúc crawl",
                "Gửi y nguyên lên Server; ID đã bị xóa thì nhận 404/400 thật (trung thực nhất)"),
        LIVE_ID("Thay ID còn sống", ActionEngine.ActionMode.LIVE_ID_POOL,
                "Kho ID sống: ID tự thu từ response trước đó (vd GET /orders trả về 77, 78)",
                "DELETE /orders/15 (15 đã xóa) -> gửi DELETE /orders/78"),
        RECREATE("Tạo bản ghi mới rồi gửi", ActionEngine.ActionMode.PRODUCER_RECREATE,
                "API tạo mới của cùng tài nguyên (vd POST /orders, tự nhận ra khi crawl)",
                "Gọi POST /orders -> nhận id 91 -> gửi DELETE /orders/91 (mỗi role xóa bản ghi của riêng nó)"),
        REUSE_200("Dùng lại kết quả 200 cũ", ActionEngine.ActionMode.LOCK_2XX,
                "Response 2xx đầu tiên đã được lưu lại",
                "KHÔNG gửi lại lên Server; hiển thị lại 200 cũ (tránh xóa / thanh toán 2 lần)");

        public final String label;
        public final ActionEngine.ActionMode mode;
        /** What this option needs (Đầu vào). */
        public final String input;
        /** What the resend actually does (Đầu ra), as a concrete example. */
        public final String output;

        Resend(String label, ActionEngine.ActionMode mode, String input, String output) {
            this.label = label;
            this.mode = mode;
            this.input = input;
            this.output = output;
        }

        @Override
        public String toString() {
            return label;
        }

        public static Resend of(ActionEngine.ActionMode m) {
            for (Resend r : values()) {
                if (r.mode == m) {
                    return r;
                }
            }
            return m == ActionEngine.ActionMode.HOLD_AT_PROXY || m == ActionEngine.ActionMode.INTERCEPT_WAIT ? RAW : AUTO;
        }
    }

    private DataProtection() {
    }

    /** Builds one rule; {@code target} = method (METHOD), branch path (BRANCH) or request path (API). */
    public static ActionPolicy rule(Scope scope, String target, Collection<String> methods, Choice choice, Integer apiGid) {
        long now = System.currentTimeMillis();
        Set<String> ms = new LinkedHashSet<>();
        if (methods != null) {
            for (String m : methods) {
                if (m != null && !m.isBlank()) {
                    ms.add(m.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        String t = target == null ? "" : target.trim();
        ScopeType st;
        List<String> branches = List.of();
        PathMatchType pmt = PathMatchType.WILDCARD;
        String path = "";
        switch (scope) {
            case METHOD -> {
                st = ScopeType.METHOD_MATRIX;
                if (!t.isEmpty()) {
                    ms = new LinkedHashSet<>(List.of(t.toUpperCase(Locale.ROOT)));
                }
            }
            case BRANCH -> {
                st = ScopeType.BRANCH;
                branches = List.of(Hierarchy.normalize(t));
            }
            case API -> {
                st = ScopeType.API_EXACT;
                path = ActionEngine.normalizePathTemplate(Text.stripQuery(t));
                pmt = PathMatchType.TEMPLATE;
            }
            default -> {
                st = ScopeType.GLOBAL;
                if (ms.isEmpty()) {
                    ms.addAll(ActionEngine.ACTION_METHODS);
                }
            }
        }
        ActionPolicy p = new ActionPolicy(null, "", "Tạo từ " + SOURCE, true, -1, st, branches, true, "", pmt, path,
                scope == Scope.API ? apiGid : null, ms, Stage.PROXY_GATE, choice.effect, ActionEngine.ActionMode.NONE,
                200, null, null, "", now, now, SOURCE, 0L, "", 0);
        return renamed(p);
    }

    private static ActionPolicy renamed(ActionPolicy p) {
        long now = System.currentTimeMillis();
        return new ActionPolicy(p.id(), sentence(p), p.description(), p.enabled(), p.priority(), p.scopeType(),
                p.targetBranches(), p.includeSubBranches(), p.hostPattern(), p.pathMatchType(), p.pathPattern(),
                p.targetApiGid(), p.methods(), p.stage(), p.proxyEffect(), p.replayMode(), p.spoofStatus(), p.spoofBody(),
                p.producerGid(), p.producerJsonPath(), now, now, p.createdFrom(), 0L, "", 0);
    }

    /** The rule as one Vietnamese sentence: "Mọi DELETE trong nhánh Orders -> Hỏi tôi trước". */
    public static String sentence(ActionPolicy p) {
        String methods = p.methods().isEmpty() ? "mọi request" : "mọi " + String.join("/", p.methods());
        String where = switch (p.scopeType()) {
            case GLOBAL, METHOD_MATRIX -> p.pathPattern().isEmpty() || "*".equals(p.pathPattern()) ? ""
                    : " có path " + p.pathPattern();
            case BRANCH -> " trong nhánh " + String.join(", ", p.targetBranches())
                    + (p.includeSubBranches() ? " (kèm nhánh con)" : "");
            case PATH_PATTERN -> " có path " + p.pathPattern()
                    + (p.targetBranches().isEmpty() ? "" : " trong nhánh " + String.join(", ", p.targetBranches()));
            case API_EXACT -> " tới API " + (p.targetApiGid() != null ? "#" + p.targetApiGid() + " " : "") + p.pathPattern();
        };
        String head = Character.toUpperCase(methods.charAt(0)) + methods.substring(1);
        return head + where + " -> " + Choice.of(p.proxyEffect()).label;
    }

    /** Legacy switches still active outside the rule list (shown as a banner with a one-click merge). */
    public static int legacyCount(ActionEngine ae, Config c) {
        int n = 0;
        if (c != null && (c.holdActionAtProxy || c.actionMode == ActionEngine.ActionMode.HOLD_AT_PROXY)) {
            n++;
        }
        if (ae.isHoldAllActionAtProxy()) {
            n++;
        }
        n += ae.getProxyHoldEndpoints().size();
        if (ae.isInterceptWaitEnabled()) {
            n += ae.isInterceptWaitAllScope() ? 1 : 0;
            n += ae.getInterceptWaitBranches().size();
            n += ae.getInterceptWaitEndpoints().size();
        }
        n += ae.interceptRules().size();
        return n;
    }

    /**
     * Moves every legacy switch into the rule list and turns the switch off, so behaviour is unchanged
     * but there is only ONE place to read it. {@code config} is modified in place (caller applies it).
     * Returns how many rules were created.
     */
    public static int migrateLegacy(ActionEngine ae, Config config) {
        ActionPolicyLedger ledger = ae.policyLedger();
        List<ActionPolicy> created = new ArrayList<>();
        if (config != null && (config.holdActionAtProxy || config.actionMode == ActionEngine.ActionMode.HOLD_AT_PROXY)) {
            created.add(rule(Scope.ALL, null, ActionEngine.ACTION_METHODS, Choice.HOLD, null));
            config.holdActionAtProxy = false;
            if (config.actionMode == ActionEngine.ActionMode.HOLD_AT_PROXY) {
                config.actionMode = ActionEngine.ActionMode.NONE;
            }
        }
        if (ae.isHoldAllActionAtProxy()) {
            created.add(rule(Scope.ALL, null, ActionEngine.ACTION_METHODS, Choice.HOLD, null));
            ae.setHoldAllActionAtProxy(false);
        }
        for (String key : ae.getProxyHoldEndpoints()) {
            String[] mp = key.split(" ", 2);
            created.add(rule(Scope.API, mp.length > 1 ? mp[1] : key, "*".equals(mp[0]) ? List.of() : List.of(mp[0]),
                    Choice.HOLD, null));
            ae.setEndpointProxyHold(mp[0], mp.length > 1 ? mp[1] : key, false);
        }
        if (ae.isInterceptWaitEnabled()) {
            Set<String> methods = ae.getInterceptWaitMethods();
            for (String key : ae.getInterceptWaitEndpoints()) {
                String[] mp = key.split(" ", 2);
                created.add(rule(Scope.API, mp.length > 1 ? mp[1] : key, "*".equals(mp[0]) ? List.of() : List.of(mp[0]),
                        Choice.ASK, null));
            }
            for (String b : ae.getInterceptWaitBranches()) {
                created.add(rule(Scope.BRANCH, b, methods, Choice.ASK, null));
            }
            if (ae.isInterceptWaitAllScope()) {
                created.add(rule(Scope.ALL, null, methods, Choice.ASK, null));
            }
            for (String key : ae.getInterceptWaitEndpoints()) {
                String[] mp = key.split(" ", 2);
                ae.setEndpointInterceptWait(mp[0], mp.length > 1 ? mp[1] : key, false);
            }
            for (String b : ae.getInterceptWaitBranches()) {
                ae.setBranchInterceptWait(b, false);
            }
            ae.setInterceptWaitEnabled(false);
            ae.setInterceptWaitAllScope(false);
        }
        int n = created.size();
        for (ActionPolicy p : created) {
            ledger.upsert(p);
        }
        List<ActionInterceptRule> legacy = ae.interceptRules();
        if (!legacy.isEmpty()) {
            n += ledger.migrateFromLegacyRules(legacy);
            ae.setInterceptRules(List.of());
        }
        return n;
    }

    /** The current answer to question 1 for everything (the GLOBAL rule created here), or PASS. */
    public static Choice globalChoice(ActionPolicyLedger ledger) {
        for (ActionPolicy p : ledger.all()) {
            if (p.enabled() && p.scopeType() == ScopeType.GLOBAL && SOURCE.equals(p.createdFrom())) {
                return Choice.of(p.proxyEffect());
            }
        }
        return Choice.PASS;
    }

    /** Sets the answer for everything: replaces the previous GLOBAL rule created here. */
    public static void setGlobalChoice(ActionPolicyLedger ledger, Choice choice) {
        for (ActionPolicy p : ledger.all()) {
            if (p.scopeType() == ScopeType.GLOBAL && SOURCE.equals(p.createdFrom())) {
                ledger.delete(p.id(), "Đổi lựa chọn chung trong " + SOURCE);
            }
        }
        if (choice != null && choice != Choice.PASS) {
            ledger.upsert(rule(Scope.ALL, null, ActionEngine.ACTION_METHODS, choice, null));
        }
    }
}
