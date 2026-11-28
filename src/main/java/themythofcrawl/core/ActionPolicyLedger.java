package themythofcrawl.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import themythofcrawl.model.ActionInterceptRule;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ActionPolicy.PathMatchType;
import themythofcrawl.model.ActionPolicy.ProxyEffect;
import themythofcrawl.model.ActionPolicy.ScopeType;
import themythofcrawl.model.ActionPolicy.Stage;
import themythofcrawl.model.ApiGroup;

/**
 * Unified Action Policy Ledger (V_3.4_beta) — Single Source of Truth with Full + Extended CRUD.
 * <ul>
 *   <li><b>Create</b>: form creation, quick-create from API/Branch/Traffic, duplicate/clone, presets, JSON import.</li>
 *   <li><b>Read</b>: deterministic priority-sorted list, multi-criteria filtering, per-API effective policy explainer,
 *       scope breakdown summary, JSON export.</li>
 *   <li><b>Update</b>: inline toggle, full edit preserving {@code createdAt}/{@code hitCount}, priority reorder
 *       (up/down/top), bulk toggle, bulk effect update, hit audit recording.</li>
 *   <li><b>Delete &amp; Recovery</b>: delete single, bulk delete, clear scope, emergency Reset All to NONE,
 *       plus 50-entry Soft-Delete Undo Trash with 1-click restore.</li>
 *   <li><b>Simulation</b>: zero-network Batch Pre-flight Simulator predicting exact Proxy &amp; Replay outcomes
 *       for any collection of captured APIs.</li>
 * </ul>
 */
public final class ActionPolicyLedger {

    public static final int TRASH_CAPACITY = 50;

    public enum Preset {
        WAIT_ALL_ACTIONS(
                "Chặn chờ duyệt mọi Action (POST/PUT/PATCH/DELETE)",
                "Tạo luật Toàn cục chặn chờ duyệt mọi POST/PUT/PATCH/DELETE để bấm Gửi thủ công"),
        ALLOW_AUTH_TELEMETRY(
                "Cho qua Auth / Login / Telemetry (luôn ưu tiên)",
                "Chèn ngoại lệ Cho qua ở độ ưu tiên cao nhất cho login, refresh, logout, analytics, health"),
        HOLD_ALL_DELETES(
                "Giữ ở Proxy mọi lệnh Xóa (DELETE -> 200 ảo)",
                "Giữ toàn bộ DELETE tại Proxy để không xóa dữ liệu thật trên Server khi crawl"),
        ALLOW_ALL_GET(
                "Cho qua toàn bộ GET / HEAD / OPTIONS (ALLOW)",
                "Đảm bảo các request đọc (GET/HEAD/OPTIONS) luôn đi thẳng không bao giờ bị chặn");

        public final String label;
        public final String description;

        Preset(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    public record DeletedEntry(ActionPolicy policy, long deletedAt, String reason) {
    }

    public record EvaluationResult(
            ActionPolicy winningPolicy,
            List<ActionPolicy> allMatched,
            ProxyEffect effectiveProxyEffect,
            ActionEngine.ActionMode effectiveReplayMode,
            String reasonVi) {

        public boolean matched() {
            return winningPolicy != null;
        }

        public boolean willBlockAtProxy() {
            return effectiveProxyEffect != null && effectiveProxyEffect.isBlock();
        }
    }

    public record SimulationRow(
            int gid,
            String method,
            String path,
            String host,
            String branch,
            boolean actionProtocol,
            EvaluationResult proxyEval,
            EvaluationResult replayEval) {
    }

    public record SimulationReport(
            List<SimulationRow> rows,
            int totalApis,
            int totalActions,
            int willWait,
            int willHoldSpoof,
            int willDrop,
            int willAllowExplicit,
            int willPassDefault) {
    }

    /** The main Proxy gate ({@link ProxyGate#ID}) is the catch-all: every specific rule is evaluated before it. */
    private static final Comparator<ActionPolicy> ORDER = Comparator
            .comparing((ActionPolicy p) -> ProxyGate.ID.equals(p.id()))
            .thenComparingInt(ActionPolicy::priority)
            .thenComparingInt(p -> p.scopeType().specificityRank)
            .thenComparingLong(ActionPolicy::createdAt);

    private final List<ActionPolicy> policies = new ArrayList<>();
    private final Deque<DeletedEntry> trash = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // =========================================================================
    // READ
    // =========================================================================

    public synchronized List<ActionPolicy> all() {
        List<ActionPolicy> out = new ArrayList<>(policies.size());
        for (ActionPolicy p : policies) {
            out.add(p.copy());
        }
        return out;
    }

    public synchronized int size() {
        return policies.size();
    }

    public synchronized ActionPolicy findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (ActionPolicy p : policies) {
            if (id.equals(p.id())) {
                return p.copy();
            }
        }
        return null;
    }

    public synchronized List<ActionPolicy> filter(
            ScopeType scope,
            Boolean enabledOnly,
            Stage stage,
            ProxyEffect effect,
            String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<ActionPolicy> out = new ArrayList<>();
        for (ActionPolicy p : policies) {
            if (scope != null && p.scopeType() != scope) {
                continue;
            }
            if (enabledOnly != null && p.enabled() != enabledOnly) {
                continue;
            }
            if (stage != null && p.stage() != stage) {
                continue;
            }
            if (effect != null && p.proxyEffect() != effect) {
                continue;
            }
            if (!q.isEmpty()) {
                String hay = (p.id() + " " + p.name() + " " + p.description() + " "
                        + p.scopeType().label + " " + p.targetSummary() + " "
                        + p.methodsLabel() + " " + p.behaviorSummary() + " "
                        + p.createdFrom()).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) {
                    continue;
                }
            }
            out.add(p.copy());
        }
        return out;
    }

    public synchronized Map<ScopeType, Integer> countByScope(boolean enabledOnly) {
        Map<ScopeType, Integer> counts = new EnumMap<>(ScopeType.class);
        for (ScopeType s : ScopeType.values()) {
            counts.put(s, 0);
        }
        for (ActionPolicy p : policies) {
            if (!enabledOnly || p.enabled()) {
                counts.merge(p.scopeType(), 1, Integer::sum);
            }
        }
        return counts;
    }

    public synchronized int activeCount() {
        int n = 0;
        for (ActionPolicy p : policies) {
            if (p.enabled()) n++;
        }
        return n;
    }

    public synchronized int activeBlockingCount() {
        int n = 0;
        for (ActionPolicy p : policies) {
            if (p.enabled() && p.stage().appliesToProxy() && p.proxyEffect().isBlock()) {
                n++;
            }
        }
        return n;
    }

    /** Human-readable breakdown of currently enabled policies by scope. */
    public synchronized String activeSummaryText() {
        Map<ScopeType, Integer> c = countByScope(true);
        List<String> parts = new ArrayList<>();
        if (c.get(ScopeType.GLOBAL) > 0) parts.add(c.get(ScopeType.GLOBAL) + " Toàn cục");
        if (c.get(ScopeType.METHOD_MATRIX) > 0) parts.add(c.get(ScopeType.METHOD_MATRIX) + " Method");
        if (c.get(ScopeType.BRANCH) > 0) parts.add(c.get(ScopeType.BRANCH) + " Nhánh");
        if (c.get(ScopeType.PATH_PATTERN) > 0) parts.add(c.get(ScopeType.PATH_PATTERN) + " Path");
        if (c.get(ScopeType.API_EXACT) > 0) parts.add(c.get(ScopeType.API_EXACT) + " API");
        return parts.isEmpty() ? "0 luật bật" : String.join(" · ", parts);
    }

    public synchronized String exportJson(Collection<String> onlyIds) {
        if (onlyIds == null || onlyIds.isEmpty()) {
            return ActionPolicy.listToJson(policies);
        }
        Set<String> set = new LinkedHashSet<>(onlyIds);
        List<ActionPolicy> sub = new ArrayList<>();
        for (ActionPolicy p : policies) {
            if (set.contains(p.id())) {
                sub.add(p);
            }
        }
        return ActionPolicy.listToJson(sub);
    }

    // =========================================================================
    // CREATE & UPDATE
    // =========================================================================

    /**
     * Inserts a new policy or updates an existing one with the same ID.
     * Preserves {@code createdAt} and {@code hitCount} when updating an existing ID.
     */
    public ActionPolicy upsert(ActionPolicy policy) {
        if (policy == null) {
            return null;
        }
        ActionPolicy saved;
        synchronized (this) {
            ActionPolicy copy = policy.copy();
            int existingIdx = indexOf(copy.id());
            if (existingIdx >= 0) {
                ActionPolicy prev = policies.get(existingIdx);
                copy.setTimestamps(prev.createdAt(), System.currentTimeMillis());
                if (copy.priority() < 0) {
                    copy.setPriority(prev.priority());
                }
                if (copy.hitCount() == 0 && prev.hitCount() > 0) {
                    // keep accumulated audit hits unless explicitly changed
                    for (int i = 0; i < prev.hitCount(); i++) {
                        copy.recordHit(prev.lastHitSummary());
                    }
                }
                policies.set(existingIdx, copy);
            } else {
                if (copy.priority() < 0) {
                    copy.setPriority(copy.scopeType().specificityRank * 100 + policies.size());
                }
                policies.add(copy);
            }
            policies.sort(ORDER);
            saved = copy.copy();
        }
        fireChanged();
        return saved;
    }

    /** Inserts a policy right at priority 0 (top priority — ideal for ALLOW exceptions). */
    public ActionPolicy createAtTop(ActionPolicy policy) {
        if (policy == null) {
            return null;
        }
        ActionPolicy saved;
        synchronized (this) {
            int minP = Integer.MAX_VALUE;
            for (ActionPolicy p : policies) {
                if (p.priority() < minP) {
                    minP = p.priority();
                }
            }
            if (!policies.isEmpty() && minP <= 0) {
                int shift = 1 - minP;
                for (ActionPolicy p : policies) {
                    p.setPriority(p.priority() + shift);
                }
            }
            ActionPolicy copy = policy.copy();
            copy.setPriority(0);
            int idx = indexOf(copy.id());
            if (idx >= 0) {
                policies.set(idx, copy);
            } else {
                policies.add(0, copy);
            }
            policies.sort(ORDER);
            saved = copy.copy();
        }
        fireChanged();
        return saved;
    }

    public ActionPolicy duplicate(String id) {
        ActionPolicy created;
        synchronized (this) {
            int idx = indexOf(id);
            if (idx < 0) {
                return null;
            }
            ActionPolicy dup = policies.get(idx).duplicateWithNewId();
            policies.add(idx + 1, dup);
            normalizeSequentialPrioritiesLocked();
            created = dup.copy();
        }
        fireChanged();
        return created;
    }

    public ActionPolicy applyPreset(Preset preset) {
        if (preset == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        return switch (preset) {
            case WAIT_ALL_ACTIONS -> upsert(new ActionPolicy(
                    null, "Chặn chờ duyệt mọi Action (POST/PUT/PATCH/DELETE)",
                    preset.description, true, policies.size(),
                    ScopeType.METHOD_MATRIX, List.of(), true, "",
                    PathMatchType.WILDCARD, "", null,
                    List.of("POST", "PUT", "PATCH", "DELETE"),
                    Stage.PROXY_GATE, ProxyEffect.INTERCEPT_WAIT, ActionEngine.ActionMode.NONE,
                    200, null, null, "", now, now, "Preset", 0L, "", 0));
            case ALLOW_AUTH_TELEMETRY -> createAtTop(new ActionPolicy(
                    null, "Cho qua Auth / Login / Telemetry (ALLOW)",
                    preset.description, true, 0,
                    ScopeType.PATH_PATTERN, List.of(), true, "",
                    PathMatchType.REGEX, "/(auth|login|logout|refresh|token|health|metrics|telemetry|analytics)(/|$|\\?)",
                    null, List.of(),
                    Stage.PROXY_GATE, ProxyEffect.ALLOW_EXPLICIT, ActionEngine.ActionMode.NONE,
                    200, null, null, "", now, now, "Preset", 0L, "", 0));
            case HOLD_ALL_DELETES -> upsert(new ActionPolicy(
                    null, "Giữ ở Proxy mọi lệnh DELETE (200 ảo)",
                    preset.description, true, policies.size(),
                    ScopeType.METHOD_MATRIX, List.of(), true, "",
                    PathMatchType.WILDCARD, "", null,
                    List.of("DELETE"),
                    Stage.PROXY_GATE, ProxyEffect.HOLD_SPOOF, ActionEngine.ActionMode.NONE,
                    200, null, null, "", now, now, "Preset", 0L, "", 0));
            case ALLOW_ALL_GET -> createAtTop(new ActionPolicy(
                    null, "Cho qua toàn bộ GET / HEAD / OPTIONS (ALLOW)",
                    preset.description, true, 0,
                    ScopeType.METHOD_MATRIX, List.of(), true, "",
                    PathMatchType.WILDCARD, "", null,
                    List.of("GET", "HEAD", "OPTIONS"),
                    Stage.PROXY_GATE, ProxyEffect.ALLOW_EXPLICIT, ActionEngine.ActionMode.NONE,
                    200, null, null, "", now, now, "Preset", 0L, "", 0));
        };
    }

    public int importJson(String json, boolean replaceAll) {
        List<ActionPolicy> parsed = ActionPolicy.listFromJson(json);
        if (parsed.isEmpty() && !replaceAll) {
            return 0;
        }
        synchronized (this) {
            if (replaceAll) {
                for (ActionPolicy old : policies) {
                    pushTrashLocked(old, "Thay thế bằng Import JSON");
                }
                policies.clear();
            }
            for (ActionPolicy p : parsed) {
                int idx = indexOf(p.id());
                if (idx >= 0) {
                    policies.set(idx, p.copy());
                } else {
                    policies.add(p.copy());
                }
            }
            sortAndNormalizeLocked();
        }
        fireChanged();
        return parsed.size();
    }

    public boolean setEnabled(String id, boolean enabled) {
        boolean changed = false;
        synchronized (this) {
            int idx = indexOf(id);
            if (idx >= 0 && policies.get(idx).enabled() != enabled) {
                policies.get(idx).setEnabled(enabled);
                changed = true;
            }
        }
        if (changed) {
            fireChanged();
        }
        return changed;
    }

    public int bulkSetEnabled(Collection<String> ids, boolean enabled) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int changed = 0;
        Set<String> set = new LinkedHashSet<>(ids);
        synchronized (this) {
            for (ActionPolicy p : policies) {
                if (set.contains(p.id()) && p.enabled() != enabled) {
                    p.setEnabled(enabled);
                    changed++;
                }
            }
        }
        if (changed > 0) {
            fireChanged();
        }
        return changed;
    }

    public int bulkSetProxyEffect(Collection<String> ids, ProxyEffect effect) {
        if (ids == null || ids.isEmpty() || effect == null) {
            return 0;
        }
        int changed = 0;
        Set<String> set = new LinkedHashSet<>(ids);
        synchronized (this) {
            for (ActionPolicy p : policies) {
                if (set.contains(p.id()) && p.proxyEffect() != effect) {
                    p.setProxyEffect(effect);
                    changed++;
                }
            }
        }
        if (changed > 0) {
            fireChanged();
        }
        return changed;
    }

    public boolean movePriority(String id, int delta) {
        if (id == null || delta == 0) {
            return false;
        }
        boolean moved = false;
        synchronized (this) {
            int idx = indexOf(id);
            if (idx >= 0) {
                int target = Math.max(0, Math.min(policies.size() - 1, idx + delta));
                if (target != idx) {
                    ActionPolicy item = policies.remove(idx);
                    policies.add(target, item);
                    normalizeSequentialPrioritiesLocked();
                    moved = true;
                }
            }
        }
        if (moved) {
            fireChanged();
        }
        return moved;
    }

    public boolean moveToTop(String id) {
        if (id == null) {
            return false;
        }
        boolean moved = false;
        synchronized (this) {
            int idx = indexOf(id);
            if (idx > 0) {
                ActionPolicy item = policies.remove(idx);
                policies.add(0, item);
                normalizeSequentialPrioritiesLocked();
                moved = true;
            }
        }
        if (moved) {
            fireChanged();
        }
        return moved;
    }

    public void recordHit(String policyId, String summary) {
        if (policyId == null || policyId.isBlank()) {
            return;
        }
        boolean hit = false;
        synchronized (this) {
            int idx = indexOf(policyId);
            if (idx >= 0) {
                policies.get(idx).recordHit(summary);
                hit = true;
            }
        }
        if (hit) {
            fireChanged();
        }
    }

    public void resetAllHitCounts() {
        synchronized (this) {
            for (ActionPolicy p : policies) {
                p.resetHit();
            }
        }
        fireChanged();
    }

    // =========================================================================
    // DELETE & SAFE RECOVERY (UNDO TRASH)
    // =========================================================================

    public boolean delete(String id, String reason) {
        boolean removed = false;
        synchronized (this) {
            int idx = indexOf(id);
            if (idx >= 0) {
                ActionPolicy old = policies.remove(idx);
                pushTrashLocked(old, Text.blank(reason) ? "Xóa thủ công" : reason);
                normalizeSequentialPrioritiesLocked();
                removed = true;
            }
        }
        if (removed) {
            fireChanged();
        }
        return removed;
    }

    public int bulkDelete(Collection<String> ids, String reason) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Set<String> set = new LinkedHashSet<>(ids);
        int count = 0;
        synchronized (this) {
            List<ActionPolicy> keep = new ArrayList<>();
            for (ActionPolicy p : policies) {
                if (set.contains(p.id())) {
                    pushTrashLocked(p, Text.blank(reason) ? "Xóa hàng loạt" : reason);
                    count++;
                } else {
                    keep.add(p);
                }
            }
            if (count > 0) {
                policies.clear();
                policies.addAll(keep);
                normalizeSequentialPrioritiesLocked();
            }
        }
        if (count > 0) {
            fireChanged();
        }
        return count;
    }

    /** Removes all policies bound to a specific branch or specific API/path. */
    public int clearScope(ScopeType scopeType, String targetKey, Integer targetGid) {
        if (scopeType == null) {
            return 0;
        }
        int removed = 0;
        String normBranch = (scopeType == ScopeType.BRANCH && !Text.blank(targetKey))
                ? Hierarchy.normalize(targetKey) : null;
        String normPath = (scopeType == ScopeType.API_EXACT && !Text.blank(targetKey))
                ? ActionEngine.normalizePathTemplate(targetKey) : null;
        synchronized (this) {
            List<ActionPolicy> keep = new ArrayList<>();
            for (ActionPolicy p : policies) {
                boolean drop = false;
                if (p.scopeType() == scopeType) {
                    if (scopeType == ScopeType.GLOBAL) {
                        drop = true;
                    } else if (scopeType == ScopeType.BRANCH && normBranch != null) {
                        for (String b : p.targetBranches()) {
                            if (b.equalsIgnoreCase(normBranch)) {
                                drop = true;
                                break;
                            }
                        }
                    } else if (scopeType == ScopeType.API_EXACT) {
                        if (targetGid != null && targetGid.equals(p.targetApiGid())) {
                            drop = true;
                        } else if (normPath != null && !p.pathPattern().isEmpty()
                                && ActionEngine.normalizePathTemplate(p.pathPattern()).equalsIgnoreCase(normPath)) {
                            drop = true;
                        }
                    } else if (scopeType == ScopeType.PATH_PATTERN && !Text.blank(targetKey)) {
                        drop = targetKey.equalsIgnoreCase(p.pathPattern());
                    }
                }
                if (drop) {
                    pushTrashLocked(p, "Bỏ luật phạm vi " + scopeType.label);
                    removed++;
                } else {
                    keep.add(p);
                }
            }
            if (removed > 0) {
                policies.clear();
                policies.addAll(keep);
                normalizeSequentialPrioritiesLocked();
            }
        }
        if (removed > 0) {
            fireChanged();
        }
        return removed;
    }

    /** Emergency Reset All: moves every policy to Undo Trash so the system returns cleanly to NONE. */
    public int resetAllToNone() {
        int count;
        synchronized (this) {
            count = policies.size();
            for (ActionPolicy p : policies) {
                pushTrashLocked(p, "Đặt lại tất cả");
            }
            policies.clear();
        }
        if (count > 0) {
            fireChanged();
        }
        return count;
    }

    public synchronized List<DeletedEntry> trash() {
        List<DeletedEntry> out = new ArrayList<>(trash.size());
        for (DeletedEntry e : trash) {
            out.add(new DeletedEntry(e.policy().copy(), e.deletedAt(), e.reason()));
        }
        return out;
    }

    public synchronized int trashSize() {
        return trash.size();
    }

    public ActionPolicy restoreFromTrash(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return null;
        }
        ActionPolicy restored = null;
        synchronized (this) {
            List<DeletedEntry> list = new ArrayList<>(trash);
            for (int i = 0; i < list.size(); i++) {
                if (policyId.equals(list.get(i).policy().id())) {
                    DeletedEntry removed = list.remove(i);
                    trash.clear();
                    trash.addAll(list);
                    ActionPolicy p = removed.policy().copy();
                    policies.add(p);
                    sortAndNormalizeLocked();
                    restored = p.copy();
                    break;
                }
            }
        }
        if (restored != null) {
            fireChanged();
        }
        return restored;
    }

    public ActionPolicy undoLastDelete() {
        ActionPolicy restored = null;
        synchronized (this) {
            DeletedEntry top = trash.pollFirst();
            if (top != null) {
                ActionPolicy p = top.policy().copy();
                policies.add(p);
                sortAndNormalizeLocked();
                restored = p.copy();
            }
        }
        if (restored != null) {
            fireChanged();
        }
        return restored;
    }

    public synchronized void clearTrash() {
        trash.clear();
    }

    // =========================================================================
    // PERSISTENCE & LEGACY MIGRATION
    // =========================================================================

    public synchronized void loadJson(String json) {
        policies.clear();
        for (ActionPolicy p : ActionPolicy.listFromJson(json)) {
            policies.add(p);
        }
        sortAndNormalizeLocked();
    }

    public synchronized String toJson() {
        return ActionPolicy.listToJson(policies);
    }

    /** Migrates legacy V2.2/V2.4 {@link ActionInterceptRule} list into unified policies when ledger is empty. */
    public int migrateFromLegacyRules(List<ActionInterceptRule> legacyRules) {
        if (legacyRules == null || legacyRules.isEmpty()) {
            return 0;
        }
        int added = 0;
        synchronized (this) {
            for (ActionInterceptRule r : legacyRules) {
                if (r == null || indexOf(r.id()) >= 0) {
                    continue;
                }
                ActionPolicy p = ActionPolicy.fromLegacyRule(r, policies.size());
                if (p != null) {
                    policies.add(p);
                    added++;
                }
            }
            if (added > 0) {
                sortAndNormalizeLocked();
            }
        }
        if (added > 0) {
            fireChanged();
        }
        return added;
    }

    // =========================================================================
    // DETERMINISTIC EVALUATION & BATCH PRE-FLIGHT SIMULATOR
    // =========================================================================

    /**
     * Evaluates Proxy gate policies deterministically in priority order.
     * Never mutates hit counters (call {@link #recordHit} only on real Proxy traffic).
     */
    public synchronized EvaluationResult evaluateProxy(
            String activeBranch, String host, String method, String rawPath, Integer apiGid) {
        return evaluateProxy(activeBranch, host, method, rawPath, apiGid, null);
    }

    /** V_11.6: {@code skip} leaves a matching policy out (the Chặn gate whose extra scope rejects the request). */
    public synchronized EvaluationResult evaluateProxy(
            String activeBranch, String host, String method, String rawPath, Integer apiGid,
            java.util.function.Predicate<ActionPolicy> skip) {
        List<ActionPolicy> matched = new ArrayList<>();
        for (ActionPolicy p : policies) {
            if (!p.enabled() || !p.stage().appliesToProxy()) {
                continue;
            }
            if (skip != null && skip.test(p)) {
                continue;
            }
            if (p.matches(activeBranch, host, method, rawPath, apiGid)) {
                matched.add(p.copy());
            }
        }
        if (matched.isEmpty()) {
            return new EvaluationResult(
                    null, List.of(), ProxyEffect.PASS_NONE, ActionEngine.ActionMode.NONE,
                    "Không có chính sách nào khớp -> Cho qua nguyên bản");
        }
        ActionPolicy winner = matched.get(0);
        int rank = Math.max(1, indexOf(winner.id()) + 1);
        String reason = "[" + winner.scopeType().label + " #" + rank + "] "
                + winner.name() + " -> " + winner.proxyEffect().label;
        return new EvaluationResult(winner, matched, winner.proxyEffect(), winner.replayMode(), reason);
    }

    /**
     * Evaluates Replay execution policies deterministically for a captured API.
     */
    public synchronized EvaluationResult evaluateReplay(
            String activeBranch, String host, String method, String rawPath, Integer apiGid,
            ActionEngine.ActionMode fallbackGlobalMode) {
        List<ActionPolicy> matched = new ArrayList<>();
        for (ActionPolicy p : policies) {
            if (!p.enabled() || !p.stage().appliesToReplay()) {
                continue;
            }
            if (p.matches(activeBranch, host, method, rawPath, apiGid)) {
                matched.add(p.copy());
            }
        }
        if (!matched.isEmpty()) {
            ActionPolicy winner = matched.get(0);
            int rank = Math.max(1, indexOf(winner.id()) + 1);
            String reason = "[" + winner.scopeType().label + " #" + rank + "] "
                    + winner.name() + " -> " + winner.replayMode().shortTitle();
            return new EvaluationResult(winner, matched, winner.proxyEffect(), winner.replayMode(), reason);
        }
        ActionEngine.ActionMode fb = fallbackGlobalMode == null ? ActionEngine.ActionMode.NONE : fallbackGlobalMode;
        return new EvaluationResult(
                null, List.of(), ProxyEffect.PASS_NONE, fb,
                fb == ActionEngine.ActionMode.NONE
                        ? "Không luật (đi thẳng)"
                        : "Mặc định toàn cục: " + fb.shortTitle());
    }

    /**
     * Zero-network Batch Pre-flight Simulator: evaluates every given API against the live ledger
     * so users can verify which requests will be blocked vs allowed before touching the target UI.
     */
    public synchronized SimulationReport simulateBatch(
            Collection<ApiGroup> apis, ActionEngine.ActionMode fallbackGlobalMode) {
        List<SimulationRow> rows = new ArrayList<>();
        int totalApis = 0;
        int totalActions = 0;
        int willWait = 0;
        int willHoldSpoof = 0;
        int willDrop = 0;
        int willAllowExplicit = 0;
        int willPassDefault = 0;
        if (apis != null) {
            for (ApiGroup g : apis) {
                if (g == null) continue;
                totalApis++;
                boolean isAction = ActionEngine.isDestructiveOrMutating(g.method(), g.path());
                if (isAction) totalActions++;
                EvaluationResult proxyEval = evaluateProxy(g.branch(), g.host(), g.method(), g.path(), g.gid());
                ActionEngine.ActionMode apiFallback = g.actionModeOverride() != null ? g.actionModeOverride() : fallbackGlobalMode;
                EvaluationResult replayEval = evaluateReplay(g.branch(), g.host(), g.method(), g.path(), g.gid(), apiFallback);
                switch (proxyEval.effectiveProxyEffect()) {
                    case INTERCEPT_WAIT -> willWait++;
                    case HOLD_SPOOF -> willHoldSpoof++;
                    case BLOCK_DROP -> willDrop++;
                    case ALLOW_EXPLICIT -> willAllowExplicit++;
                    case PASS_NONE -> willPassDefault++;
                }
                rows.add(new SimulationRow(
                        g.gid(), g.method(), g.path(), g.host(), g.branch(), isAction, proxyEval, replayEval));
            }
        }
        return new SimulationReport(
                rows, totalApis, totalActions, willWait, willHoldSpoof, willDrop, willAllowExplicit, willPassDefault);
    }

    public void addListener(Runnable r) {
        if (r != null) listeners.add(r);
    }

    public void removeListener(Runnable r) {
        if (r != null) listeners.remove(r);
    }

    // ---- Internal helpers
    private int indexOf(String id) {
        if (id == null) return -1;
        for (int i = 0; i < policies.size(); i++) {
            if (id.equals(policies.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private void sortAndNormalizeLocked() {
        policies.sort(ORDER);
        normalizeSequentialPrioritiesLocked();
    }

    private void normalizeSequentialPrioritiesLocked() {
        for (int i = 0; i < policies.size(); i++) {
            policies.get(i).setPriority(i);
        }
    }

    private void pushTrashLocked(ActionPolicy p, String reason) {
        trash.addFirst(new DeletedEntry(p.copy(), System.currentTimeMillis(), reason));
        while (trash.size() > TRASH_CAPACITY) {
            trash.removeLast();
        }
    }

    private void fireChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // listener failure must never block engine
            }
        }
    }
}
