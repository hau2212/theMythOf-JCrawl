package themythofcrawl.collab;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.excel.TargetList;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleResult;

/**
 * Unified API Inventory, Multi-Context Synthesizer (7 contexts), Status/Tag Tracker,
 * and Colored Final Excel (.xlsx) Exporter for V_5.0_beta.
 * <p>
 * Works seamlessly both WITH a loaded Excel target list AND WITHOUT any Excel file (Auto-Inventory),
 * keeping statuses ({@code Done (A)}, {@code Info (A)}, {@code Missing}, {@code OOS (A)},
 * {@code Conflict (A vs B)}, {@code Doing (A)}) and custom user tags synchronized across teammates
 * and Google Sheets / Microsoft Excel Online.
 */
public final class InventoryCatalog {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/MM").withZone(ZoneId.systemDefault());

    public enum ItemStatus {
        DONE("Done", "Tìm thấy & gọi thành công (2xx)", Xlsx.CellTone.DONE_GREEN),
        INFO("Info", "Tìm thấy nhưng gọi lỗi (4xx/5xx/Timeout) hoặc cần xem lại", Xlsx.CellTone.INFO_YELLOW),
        MISSING("Missing", "Có trong danh sách Excel nhưng chưa tìm ra", Xlsx.CellTone.MISSING_RED),
        OOS("OOS", "Out of Scope — ngoài phạm vi kiểm thử", Xlsx.CellTone.OOS_GRAY),
        CONFLICT("Conflict", "Xung đột giữa 2 thành viên — cần chọn bản chuẩn", Xlsx.CellTone.CONFLICT_ORANGE),
        DOING("Doing", "Đang thực hiện / Đang giữ tại Proxy", Xlsx.CellTone.DOING_BLUE);

        public final String shortLabel;
        public final String description;
        public final Xlsx.CellTone xlsxTone;

        ItemStatus(String shortLabel, String description, Xlsx.CellTone xlsxTone) {
            this.shortLabel = shortLabel;
            this.description = description;
            this.xlsxTone = xlsxTone;
        }

        public String formatWithOwners(Collection<String> owners) {
            if (this == MISSING || owners == null || owners.isEmpty()) {
                return shortLabel;
            }
            List<String> clean = new ArrayList<>();
            for (String o : owners) {
                if (o != null && !o.isBlank() && !clean.contains(o.trim())) {
                    clean.add(o.trim());
                }
            }
            if (clean.isEmpty()) {
                return shortLabel;
            }
            if (this == CONFLICT && clean.size() >= 2) {
                return "Conflict (" + String.join(" vs ", clean) + ")";
            }
            return shortLabel + " (" + String.join(", ", clean) + ")";
        }

        public static ItemStatus parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return INFO;
            }
            String u = raw.trim().toUpperCase(Locale.ROOT);
            if (u.startsWith("DONE")) return DONE;
            if (u.startsWith("INFO")) return INFO;
            if (u.startsWith("MISS") || u.startsWith("NOT FOUND")) return MISSING;
            if (u.startsWith("OOS") || u.contains("OUT OF SCOPE")) return OOS;
            if (u.startsWith("CONFLICT")) return CONFLICT;
            if (u.startsWith("DOING") || u.startsWith("HELD") || u.startsWith("IN PROGRESS")) return DOING;
            return INFO;
        }
    }

    /** 7 analytical synthesis contexts usable even when NO Excel file is loaded. */
    public enum ViewContext {
        TIMELINE("1. Thứ tự Crawl", "Sắp xếp theo đúng trình tự thời gian crawl được"),
        PATH_TEMPLATE("2. Mẫu Path {id}", "Gom các URL giống nhau chỉ khác ID/UUID thành mẫu /orders/{id}"),
        CRUD_RESOURCE("3. Nhóm CRUD", "Gom các API cùng tài nguyên (GET/POST/PUT/PATCH/DELETE) & chỉ ra thiếu gì"),
        BRANCH_SCREEN("4. Nhánh / Màn hình", "Gom theo vị trí nhánh cây UI tìm thấy & đánh dấu Shared API"),
        HOST_PREFIX("5. Host / Tiền tố", "Gom theo Domain & nhóm prefix API (/api/v1/...)"),
        STATUS_AUDIT("6. Trạng thái & Lỗi", "Gom theo Done / Info / OOS / Missing / Conflict / Rủi ro"),
        OWNER_CONFLICT("7. Thành viên & Conflict", "Gom theo người tìm (A, B...) và các mục đang xung đột");

        public final String label;
        public final String description;

        ViewContext(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    /** Mutable tracked state for one normalized endpoint across local + remote teammates. */
    public static final class EndpointMeta {
        public final String key;
        public String method;
        public String host;
        public String pathTemplate;
        public ItemStatus status;
        public boolean manualOverride;
        public final Set<String> owners = new LinkedHashSet<>();
        public final Set<String> tags = new LinkedHashSet<>();
        public final Set<String> branches = new LinkedHashSet<>();
        public int httpStatus;
        public String note = "";
        public long revision;
        public long updatedAtMs;
        public String updatedBy = "";

        public EndpointMeta(String key, String method, String host, String pathTemplate) {
            this.key = key;
            this.method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
            this.host = host == null ? "" : host;
            this.pathTemplate = pathTemplate == null ? "/" : pathTemplate;
            this.status = ItemStatus.INFO;
            this.updatedAtMs = System.currentTimeMillis();
        }

        public EndpointMeta copy() {
            EndpointMeta c = new EndpointMeta(key, method, host, pathTemplate);
            c.status = status;
            c.manualOverride = manualOverride;
            c.owners.addAll(owners);
            c.tags.addAll(tags);
            c.branches.addAll(branches);
            c.httpStatus = httpStatus;
            c.note = note;
            c.revision = revision;
            c.updatedAtMs = updatedAtMs;
            c.updatedBy = updatedBy;
            return c;
        }
    }

    /** One synthesized display row for the Excel / Auto-Inventory table & exports. */
    public record InventoryRow(
            String key,
            String groupBucket,
            String stt,
            ItemStatus status,
            String statusDisplay,
            String method,
            String host,
            String path,
            String pathTemplate,
            String crudResource,
            String branchSummary,
            List<String> allBranches,
            int httpStatus,
            int variantCount,
            Set<String> owners,
            Set<String> tags,
            String note,
            boolean inExcelTarget,
            boolean sharedAcrossScreens,
            long firstSeenMs,
            ApiGroup primaryGroup,
            TargetEntry targetEntry) {

        public String tagsDisplay() {
            return tags == null || tags.isEmpty() ? "" : String.join(", ", tags);
        }

        public String ownersDisplay() {
            return owners == null || owners.isEmpty() ? "" : String.join(", ", owners);
        }

        public String primaryBranch() {
            if (primaryGroup != null && !Text.blank(primaryGroup.branch())) {
                return primaryGroup.branch();
            }
            return allBranches != null && !allBranches.isEmpty() ? allBranches.get(0) : "";
        }
    }

    public static final List<String> DEFAULT_TAGS = List.of(
            "IDOR", "AuthBypass", "Recheck", "BusinessLogic", "Sensitive", "HighPriority");

    private volatile String memberName = "A";
    private final Map<String, EndpointMeta> metaByKey = new LinkedHashMap<>();
    private final Set<String> customTags = new LinkedHashSet<>(DEFAULT_TAGS);
    private final Map<String, ConflictRecord> conflicts = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public String memberName() {
        return memberName;
    }

    public void setMemberName(String name) {
        if (name != null && !name.isBlank()) {
            this.memberName = name.trim();
            fireChanged();
        }
    }

    public void addListener(Runnable r) {
        if (r != null) {
            listeners.addIfAbsent(r);
        }
    }

    public void removeListener(Runnable r) {
        if (r != null) {
            listeners.remove(r);
        }
    }

    private void fireChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // never break callers
            }
        }
    }

    // ------------------------------------------------------------------ endpoint normalization

    public static String normalizeKey(String method, String host, String path) {
        String m = method == null || method.isBlank() ? "ANY" : method.trim().toUpperCase(Locale.ROOT);
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        String tpl = ActionEngine.normalizePathTemplate(path == null ? "/" : path);
        return m + " " + h + " " + tpl;
    }

    public static String prefixGroup(String host, String path) {
        String clean = Text.stripQuery(path == null ? "/" : path);
        String[] segs = clean.split("/");
        List<String> parts = new ArrayList<>();
        for (String s : segs) {
            if (!s.isBlank()) {
                parts.add(s);
                if (parts.size() >= 2) break;
            }
        }
        String pref = parts.isEmpty() ? "/" : "/" + String.join("/", parts);
        return (host == null || host.isBlank() ? "(No Host)" : host) + " · " + pref;
    }

    // ------------------------------------------------------------------ custom tags CRUD

    public synchronized List<String> allTags() {
        return new ArrayList<>(customTags);
    }

    public synchronized boolean addCustomTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        String clean = tag.trim();
        boolean added = customTags.add(clean);
        if (added) {
            fireChanged();
        }
        return added;
    }

    public synchronized boolean removeCustomTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        boolean removed = customTags.remove(tag.trim());
        if (removed) {
            for (EndpointMeta m : metaByKey.values()) {
                m.tags.remove(tag.trim());
            }
            fireChanged();
        }
        return removed;
    }

    public synchronized void toggleTagOnEndpoint(String key, String method, String host, String pathTemplate, String tag) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        String clean = tag.trim();
        customTags.add(clean);
        EndpointMeta m = getOrCreate(key, method, host, pathTemplate);
        if (!m.tags.remove(clean)) {
            m.tags.add(clean);
        }
        m.owners.add(memberName);
        m.revision++;
        m.updatedAtMs = System.currentTimeMillis();
        m.updatedBy = memberName;
        fireChanged();
    }

    // ------------------------------------------------------------------ local status & discovery updates

    /** Automatically records a locally captured/replayed API unless manually overridden (e.g. OOS). */
    public synchronized EndpointMeta recordLocalCapture(ApiGroup g) {
        if (g == null) {
            return null;
        }
        String tpl = ActionEngine.normalizePathTemplate(g.path());
        String key = normalizeKey(g.method(), g.host(), tpl);
        EndpointMeta m = getOrCreate(key, g.method(), g.host(), tpl);
        if (!Text.blank(g.branch())) {
            m.branches.add(Hierarchy.normalize(g.branch()));
        }
        m.owners.add(memberName);
        int st = g.origStatus() != null ? g.origStatus() : 0;
        if (st > 0) {
            m.httpStatus = st;
        }
        if (!m.manualOverride && !conflicts.containsKey(key)) {
            if (g.heldAtProxy()) {
                m.status = ItemStatus.DOING;
            } else if (st >= 200 && st < 300) {
                m.status = ItemStatus.DONE;
            } else if (st > 0) {
                m.status = ItemStatus.INFO;
            } else {
                m.status = ItemStatus.INFO;
            }
        }
        m.revision++;
        m.updatedAtMs = System.currentTimeMillis();
        m.updatedBy = memberName;
        fireChanged();
        return m.copy();
    }

    /** Explicit user status override (Done / Info / OOS / Doing / auto). */
    public synchronized EndpointMeta setManualStatus(
            String key, String method, String host, String pathTemplate,
            ItemStatus newStatus, String branch, String noteOrNull) {
        EndpointMeta m = getOrCreate(key, method, host, pathTemplate);
        if (newStatus == null) {
            m.manualOverride = false;
            m.status = (m.httpStatus >= 200 && m.httpStatus < 300) ? ItemStatus.DONE : ItemStatus.INFO;
        } else {
            m.manualOverride = true;
            m.status = newStatus;
        }
        if (!Text.blank(branch)) {
            m.branches.add(Hierarchy.normalize(branch));
        }
        if (newStatus != ItemStatus.MISSING) {
            m.owners.add(memberName);
        }
        if (noteOrNull != null) {
            m.note = noteOrNull;
        }
        m.revision++;
        m.updatedAtMs = System.currentTimeMillis();
        m.updatedBy = memberName;
        conflicts.remove(key);
        fireChanged();
        return m.copy();
    }

    public synchronized EndpointMeta setNote(String key, String method, String host, String pathTemplate, String note) {
        EndpointMeta m = getOrCreate(key, method, host, pathTemplate);
        m.note = note == null ? "" : note.trim();
        m.revision++;
        m.updatedAtMs = System.currentTimeMillis();
        m.updatedBy = memberName;
        fireChanged();
        return m.copy();
    }

    public synchronized EndpointMeta metaFor(String key) {
        EndpointMeta m = metaByKey.get(key);
        return m == null ? null : m.copy();
    }

    public synchronized List<EndpointMeta> allMeta() {
        List<EndpointMeta> out = new ArrayList<>(metaByKey.size());
        for (EndpointMeta m : metaByKey.values()) {
            out.add(m.copy());
        }
        return out;
    }

    // ------------------------------------------------------------------ remote sync & Git-style conflicts

    /**
     * Merges a remote teammate's endpoint update.
     * Detects a Git-style {@link ConflictRecord} if both the local member and the remote member
     * have touched the same endpoint with differing statuses, branches, or tags.
     *
     * @return the newly created {@link ConflictRecord} if a conflict occurred, otherwise {@code null}.
     */
    public synchronized ConflictRecord applyRemoteUpdate(
            String remoteMember,
            String method,
            String host,
            String pathTemplate,
            ItemStatus remoteStatus,
            int remoteHttpStatus,
            String remoteBranch,
            Collection<String> remoteTags,
            String remoteNote,
            long remoteUpdatedAtMs) {
        if (remoteMember == null || remoteMember.isBlank() || remoteMember.equalsIgnoreCase(memberName)) {
            return null;
        }
        String key = normalizeKey(method, host, pathTemplate);
        if (remoteTags != null) {
            for (String t : remoteTags) {
                if (t != null && !t.isBlank()) {
                    customTags.add(t.trim());
                }
            }
        }
        EndpointMeta existing = metaByKey.get(key);
        if (existing == null) {
            EndpointMeta created = getOrCreate(key, method, host, pathTemplate);
            created.status = remoteStatus == null ? ItemStatus.INFO : remoteStatus;
            created.httpStatus = remoteHttpStatus;
            created.owners.add(remoteMember.trim());
            if (!Text.blank(remoteBranch)) created.branches.add(Hierarchy.normalize(remoteBranch));
            if (remoteTags != null) created.tags.addAll(remoteTags);
            if (!Text.blank(remoteNote)) created.note = remoteNote;
            created.updatedAtMs = remoteUpdatedAtMs > 0 ? remoteUpdatedAtMs : System.currentTimeMillis();
            created.updatedBy = remoteMember.trim();
            created.revision++;
            fireChanged();
            return null;
        }

        boolean localOwned = existing.owners.contains(memberName);
        boolean statusDiff = remoteStatus != null && existing.status != remoteStatus
                && existing.status != ItemStatus.CONFLICT;
        boolean branchDiff = !Text.blank(remoteBranch) && !existing.branches.isEmpty()
                && !existing.branches.contains(Hierarchy.normalize(remoteBranch));
        boolean bothFoundSameEndpoint = localOwned && !existing.owners.contains(remoteMember.trim())
                && (statusDiff || branchDiff || (existing.httpStatus > 0 && remoteHttpStatus > 0
                && existing.httpStatus / 100 != remoteHttpStatus / 100));

        if (bothFoundSameEndpoint) {
            String localBranch = existing.branches.isEmpty() ? "" : existing.branches.iterator().next();
            ConflictRecord.Side localSide = new ConflictRecord.Side(
                    memberName, existing.status, existing.httpStatus, localBranch,
                    existing.tags, existing.note, existing.updatedAtMs);
            ConflictRecord.Side remoteSide = new ConflictRecord.Side(
                    remoteMember.trim(),
                    remoteStatus == null ? ItemStatus.INFO : remoteStatus,
                    remoteHttpStatus,
                    Text.blank(remoteBranch) ? "" : Hierarchy.normalize(remoteBranch),
                    remoteTags == null ? Set.of() : new LinkedHashSet<>(remoteTags),
                    remoteNote == null ? "" : remoteNote,
                    remoteUpdatedAtMs > 0 ? remoteUpdatedAtMs : System.currentTimeMillis());

            ConflictRecord cr = new ConflictRecord(
                    "cnf-" + UUID.randomUUID().toString().substring(0, 8),
                    key, existing.method, existing.host, existing.pathTemplate,
                    localSide, remoteSide, System.currentTimeMillis());
            conflicts.put(key, cr);
            existing.status = ItemStatus.CONFLICT;
            existing.owners.add(remoteMember.trim());
            fireChanged();
            return cr;
        }

        // Non-conflicting update: merge cleanly
        if (remoteStatus != null && !existing.manualOverride && existing.status != ItemStatus.CONFLICT) {
            // Prefer DONE (2xx) over INFO if remote succeeded
            if (remoteStatus == ItemStatus.DONE || existing.status == ItemStatus.MISSING || existing.status == ItemStatus.DOING) {
                existing.status = remoteStatus;
            }
        }
        if (remoteHttpStatus >= 200 && remoteHttpStatus < 300) {
            existing.httpStatus = remoteHttpStatus;
        } else if (existing.httpStatus <= 0 && remoteHttpStatus > 0) {
            existing.httpStatus = remoteHttpStatus;
        }
        existing.owners.add(remoteMember.trim());
        if (!Text.blank(remoteBranch)) existing.branches.add(Hierarchy.normalize(remoteBranch));
        if (remoteTags != null) existing.tags.addAll(remoteTags);
        if (Text.blank(existing.note) && !Text.blank(remoteNote)) existing.note = remoteNote;
        existing.updatedAtMs = Math.max(existing.updatedAtMs, remoteUpdatedAtMs);
        existing.updatedBy = remoteMember.trim();
        existing.revision++;
        fireChanged();
        return null;
    }

    public synchronized List<ConflictRecord> openConflicts() {
        return new ArrayList<>(conflicts.values());
    }

    public synchronized int conflictCount() {
        return conflicts.size();
    }

    /** Resolves a Git-style conflict using one of the 4 user choices and returns the updated EndpointMeta. */
    public synchronized EndpointMeta resolveConflict(String endpointKey, ConflictRecord.Resolution resolution) {
        ConflictRecord cr = conflicts.remove(endpointKey);
        if (cr == null) {
            return null;
        }
        EndpointMeta m = getOrCreate(cr.endpointKey(), cr.method(), cr.host(), cr.pathTemplate());
        ConflictRecord.Side loc = cr.localSide();
        ConflictRecord.Side rem = cr.remoteSide();
        switch (resolution) {
            case KEEP_LOCAL -> {
                m.status = loc.status();
                m.httpStatus = loc.httpStatus();
                m.owners.clear();
                m.owners.add(loc.member());
                m.branches.clear();
                if (!loc.branch().isEmpty()) m.branches.add(loc.branch());
                m.tags.clear();
                m.tags.addAll(loc.tags());
                m.note = loc.note();
            }
            case KEEP_REMOTE -> {
                m.status = rem.status();
                m.httpStatus = rem.httpStatus();
                m.owners.clear();
                m.owners.add(rem.member());
                m.branches.clear();
                if (!rem.branch().isEmpty()) m.branches.add(rem.branch());
                m.tags.clear();
                m.tags.addAll(rem.tags());
                m.note = rem.note();
            }
            case MERGE_BOTH -> {
                boolean loc2xx = loc.httpStatus() >= 200 && loc.httpStatus() < 300;
                boolean rem2xx = rem.httpStatus() >= 200 && rem.httpStatus() < 300;
                if (loc2xx || loc.status() == ItemStatus.DONE) {
                    m.status = ItemStatus.DONE;
                    m.httpStatus = loc2xx ? loc.httpStatus() : rem.httpStatus();
                } else if (rem2xx || rem.status() == ItemStatus.DONE) {
                    m.status = ItemStatus.DONE;
                    m.httpStatus = rem.httpStatus();
                } else {
                    m.status = loc.status();
                    m.httpStatus = Math.max(loc.httpStatus(), rem.httpStatus());
                }
                m.owners.clear();
                m.owners.add(loc.member());
                m.owners.add(rem.member());
                m.branches.clear();
                if (!loc.branch().isEmpty()) m.branches.add(loc.branch());
                if (!rem.branch().isEmpty()) m.branches.add(rem.branch());
                m.tags.clear();
                m.tags.addAll(loc.tags());
                m.tags.addAll(rem.tags());
                StringBuilder note = new StringBuilder();
                if (!loc.note().isEmpty()) note.append(loc.note());
                if (!rem.note().isEmpty() && !rem.note().equals(loc.note())) {
                    if (!note.isEmpty()) note.append(" | ");
                    note.append(rem.member()).append(": ").append(rem.note());
                }
                if (loc2xx && !rem2xx && rem.httpStatus() > 0) {
                    if (!note.isEmpty()) note.append(" | ");
                    note.append("Evidence ").append(rem.member()).append(": HTTP ").append(rem.httpStatus());
                } else if (rem2xx && !loc2xx && loc.httpStatus() > 0) {
                    if (!note.isEmpty()) note.append(" | ");
                    note.append("Evidence ").append(loc.member()).append(": HTTP ").append(loc.httpStatus());
                }
                m.note = note.toString();
            }
            case KEEP_BOTH_VARIANTS -> {
                m.status = loc.status();
                m.httpStatus = loc.httpStatus();
                m.owners.clear();
                m.owners.add(loc.member());
                m.tags.addAll(loc.tags());
                // Also store remote variant under a variant key so neither is lost
                String varKey = cr.endpointKey() + "#var-" + rem.member();
                EndpointMeta vm = getOrCreate(varKey, cr.method(), cr.host(), cr.pathTemplate() + " [" + rem.member() + "]");
                vm.status = rem.status();
                vm.httpStatus = rem.httpStatus();
                vm.owners.add(rem.member());
                if (!rem.branch().isEmpty()) vm.branches.add(rem.branch());
                vm.tags.addAll(rem.tags());
                vm.note = rem.note();
                vm.revision++;
            }
        }
        m.manualOverride = true;
        m.revision++;
        m.updatedAtMs = System.currentTimeMillis();
        m.updatedBy = memberName;
        fireChanged();
        return m.copy();
    }

    // ------------------------------------------------------------------ 7-context synthesis

    /**
     * Synthesizes a unified list of {@link InventoryRow}s from live crawled {@link ApiGroup}s,
     * optional loaded Excel {@link TargetList}, and tracked team {@link EndpointMeta}, organized
     * according to the chosen {@link ViewContext}.
     */
    public synchronized List<InventoryRow> synthesize(
            Engine engine,
            ViewContext context,
            ItemStatus statusFilter,
            String tagFilter,
            String query) {
        ViewContext ctx = context == null ? ViewContext.TIMELINE : context;
        TargetList targets = engine.targets();
        List<ApiGroup> allGroups = engine.groups();

        // 1. Index crawled groups by normalized key (collecting variants & branches)
        Map<String, List<ApiGroup>> byKey = new LinkedHashMap<>();
        for (ApiGroup g : allGroups) {
            String tpl = ActionEngine.normalizePathTemplate(g.path());
            String k = normalizeKey(g.method(), g.host(), tpl);
            byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(g);
        }

        List<InventoryRow> rawRows = new ArrayList<>();
        Set<String> emittedKeys = new LinkedHashSet<>();

        boolean hasTargets = targets != null && targets.size() > 0;

        // 2. If Excel TargetList is loaded, emit every TargetEntry first (including MISSING rows in red!)
        if (hasTargets) {
            for (TargetEntry te : targets.entries()) {
                List<ApiGroup> matchedGroups = new ArrayList<>();
                for (ApiGroup g : allGroups) {
                    if (g.target() != null && g.target().rowNo() == te.rowNo()) {
                        matchedGroups.add(g);
                    }
                }
                ApiGroup primary = matchedGroups.isEmpty() ? null : matchedGroups.get(0);
                String host = primary != null ? primary.host() : Text.nz(te.host());
                String method = primary != null ? primary.method()
                        : (Text.blank(te.methodLabel()) ? "ANY" : te.methodLabel());
                String rawPath = primary != null ? primary.path() : te.path();
                String tpl = ActionEngine.normalizePathTemplate(rawPath);
                String key = normalizeKey(method, host, tpl);
                emittedKeys.add(key);

                EndpointMeta meta = metaByKey.get(key);
                rawRows.add(buildRow(key, te.sttText(), method, host, rawPath, tpl, matchedGroups, meta, true, te, ctx));
            }
        }

        // 3. Emit all crawled APIs (Auto-Inventory when no Excel, or Extra APIs when Excel loaded)
        int autoIdx = 1;
        for (Map.Entry<String, List<ApiGroup>> en : byKey.entrySet()) {
            String key = en.getKey();
            if (emittedKeys.contains(key)) {
                continue;
            }
            emittedKeys.add(key);
            List<ApiGroup> gs = en.getValue();
            ApiGroup primary = gs.get(0);
            String tpl = ActionEngine.normalizePathTemplate(primary.path());
            EndpointMeta meta = metaByKey.get(key);
            String stt = !hasTargets ? "#" + autoIdx++ : "Extra-" + autoIdx++;
            rawRows.add(buildRow(key, stt, primary.method(), primary.host(), primary.path(), tpl,
                    gs, meta, false, null, ctx));
        }

        // 4. Also emit remote-only synced endpoints discovered by teammates (even before local crawl)
        for (EndpointMeta meta : metaByKey.values()) {
            if (emittedKeys.contains(meta.key)) {
                continue;
            }
            emittedKeys.add(meta.key);
            rawRows.add(buildRow(meta.key, "Sync", meta.method, meta.host, meta.pathTemplate, meta.pathTemplate,
                    List.of(), meta, false, null, ctx));
        }

        // 5. Filter by status, tag, and search query
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String tf = tagFilter == null || tagFilter.isBlank() || tagFilter.startsWith("Mọi") ? "" : tagFilter.trim();
        List<InventoryRow> filtered = new ArrayList<>();
        for (InventoryRow row : rawRows) {
            if (statusFilter != null && row.status() != statusFilter) {
                continue;
            }
            if (!tf.isEmpty() && !row.tags().contains(tf)) {
                continue;
            }
            if (!q.isEmpty()) {
                String hay = (row.stt() + " " + row.groupBucket() + " " + row.statusDisplay() + " "
                        + row.method() + " " + row.host() + " " + row.path() + " " + row.pathTemplate() + " "
                        + row.crudResource() + " " + row.branchSummary() + " " + row.ownersDisplay() + " "
                        + row.tagsDisplay() + " " + row.note()).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) {
                    continue;
                }
            }
            filtered.add(row);
        }

        // 6. Sort cleanly according to chosen ViewContext
        sortByContext(filtered, ctx);
        return filtered;
    }

    private InventoryRow buildRow(
            String key,
            String stt,
            String method,
            String host,
            String rawPath,
            String tpl,
            List<ApiGroup> matchedGroups,
            EndpointMeta meta,
            boolean inTarget,
            TargetEntry targetEntry,
            ViewContext ctx) {
        ApiGroup primary = matchedGroups.isEmpty() ? null : matchedGroups.get(0);
        Set<String> branches = new LinkedHashSet<>();
        int bestHttp = 0;
        long firstSeen = Long.MAX_VALUE;
        for (ApiGroup g : matchedGroups) {
            if (!Text.blank(g.branch())) {
                branches.add(Hierarchy.normalize(g.branch()));
            }
            Integer st = g.origStatus();
            if (st != null && st > 0) {
                if (bestHttp < 200 || bestHttp >= 300 || (st >= 200 && st < 300)) {
                    bestHttp = st;
                }
            }
            if (g.gid() < firstSeen) {
                firstSeen = g.gid();
            }
        }
        if (meta != null) {
            branches.addAll(meta.branches);
            if (bestHttp <= 0 && meta.httpStatus > 0) {
                bestHttp = meta.httpStatus;
            }
        }
        if (firstSeen == Long.MAX_VALUE) {
            firstSeen = meta != null ? meta.updatedAtMs : 0L;
        }

        Set<String> owners = new LinkedHashSet<>();
        if (meta != null) {
            owners.addAll(meta.owners);
        }
        if (owners.isEmpty() && primary != null) {
            owners.add(memberName);
        }

        Set<String> tags = meta != null ? new LinkedHashSet<>(meta.tags) : new LinkedHashSet<>();
        String note = meta != null ? meta.note : "";

        ItemStatus status;
        if (conflicts.containsKey(key)) {
            status = ItemStatus.CONFLICT;
        } else if (meta != null && meta.manualOverride) {
            status = meta.status;
        } else if (primary == null && (meta == null || meta.owners.isEmpty())) {
            status = inTarget ? ItemStatus.MISSING : ItemStatus.INFO;
        } else if (primary != null && primary.heldAtProxy()) {
            status = ItemStatus.DOING;
        } else if (bestHttp >= 200 && bestHttp < 300) {
            status = ItemStatus.DONE;
        } else if (meta != null && meta.status != null && primary == null) {
            status = meta.status;
        } else {
            status = ItemStatus.INFO;
        }

        String crudRes = ActionEngine.extractResourceKey(rawPath);
        List<String> branchList = new ArrayList<>(branches);
        boolean shared = branchList.size() > 1;
        String branchSummary = branchList.isEmpty() ? "(Chưa gán nhánh)"
                : (branchList.size() == 1 ? branchList.get(0)
                : branchList.get(0) + " (+" + (branchList.size() - 1) + " nhánh)");
        String statusDisplay = status.formatWithOwners(owners);

        String bucket = switch (ctx) {
            case TIMELINE -> stt;
            case PATH_TEMPLATE -> tpl + (matchedGroups.size() > 1 ? " (" + matchedGroups.size() + " biến thể)" : "");
            case CRUD_RESOURCE -> crudRes.toUpperCase(Locale.ROOT);
            case BRANCH_SCREEN -> branchSummary + (shared ? " [Shared]" : "");
            case HOST_PREFIX -> prefixGroup(host, rawPath);
            case STATUS_AUDIT -> status.shortLabel;
            case OWNER_CONFLICT -> status == ItemStatus.CONFLICT ? "CONFLICT"
                    : (owners.isEmpty() ? "Chưa ai nhận" : String.join(", ", owners));
        };

        return new InventoryRow(
                key, bucket, stt, status, statusDisplay, method, host, rawPath, tpl, crudRes,
                branchSummary, branchList, bestHttp, Math.max(1, matchedGroups.size()),
                owners, tags, note, inTarget, shared, firstSeen, primary, targetEntry);
    }

    private static void sortByContext(List<InventoryRow> rows, ViewContext ctx) {
        Comparator<InventoryRow> cmp = switch (ctx) {
            case TIMELINE -> Comparator.comparingLong(InventoryRow::firstSeenMs);
            case PATH_TEMPLATE -> Comparator.comparing(InventoryRow::pathTemplate, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(InventoryRow::method);
            case CRUD_RESOURCE -> Comparator.comparing(InventoryRow::crudResource, String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(r -> methodOrder(r.method()))
                    .thenComparing(InventoryRow::pathTemplate);
            case BRANCH_SCREEN -> Comparator.comparing(InventoryRow::branchSummary, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(InventoryRow::pathTemplate);
            case HOST_PREFIX -> Comparator.comparing(InventoryRow::groupBucket, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(InventoryRow::pathTemplate);
            case STATUS_AUDIT -> Comparator.comparingInt((InventoryRow r) -> statusOrder(r.status()))
                    .thenComparing(InventoryRow::pathTemplate);
            case OWNER_CONFLICT -> Comparator.comparingInt((InventoryRow r) -> r.status() == ItemStatus.CONFLICT ? 0 : 1)
                    .thenComparing(InventoryRow::ownersDisplay, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(InventoryRow::pathTemplate);
        };
        rows.sort(cmp);
    }

    private static int methodOrder(String m) {
        return switch (m == null ? "" : m.toUpperCase(Locale.ROOT)) {
            case "GET" -> 1;
            case "POST" -> 2;
            case "PUT" -> 3;
            case "PATCH" -> 4;
            case "DELETE" -> 5;
            default -> 9;
        };
    }

    private static int statusOrder(ItemStatus s) {
        return switch (s) {
            case CONFLICT -> 0;
            case MISSING -> 1;
            case INFO -> 2;
            case DOING -> 3;
            case DONE -> 4;
            case OOS -> 5;
        };
    }

    // ------------------------------------------------------------------ Colored 4-Sheet Final Excel (.xlsx) Export

    /**
     * Builds a 4-sheet colored {@code .xlsx} workbook ready for Microsoft Excel Online &amp; Desktop Excel:
     * <ol>
     *   <li>{@code Master_API_List} — full inventory with colored status cells ({@code Done (A)} green,
     *       {@code Info (A)} yellow, {@code Missing} red, {@code OOS (A)} gray, {@code Conflict} orange)</li>
     *   <li>{@code CRUD_Matrix} — Resource x Method matrix highlighting missing CRUD operations</li>
     *   <li>{@code By_Branch_Screen} — grouped by UI screen/branch</li>
     *   <li>{@code Team_Summary} — per-member statistics and overall coverage</li>
     * </ol>
     */
    public synchronized byte[] exportFinalColoredXlsx(Engine engine) throws IOException {
        List<InventoryRow> master = synthesize(engine, ViewContext.TIMELINE, null, null, null);
        String exportedAt = TS_FMT.format(Instant.now());

        // Sheet 1: Master_API_List
        List<String> h1 = List.of(
                "STT", "Trạng thái", "Method", "Host", "Path", "Mẫu Path {id}",
                "Nhóm CRUD", "Nhánh UI", "HTTP", "Biến thể", "Tags", "Người tìm", "Ghi chú", "Thời điểm xuất");
        int[] w1 = {10, 20, 10, 24, 38, 34, 16, 30, 8, 10, 22, 16, 28, 18};
        List<List<Object>> r1 = new ArrayList<>();
        for (InventoryRow row : master) {
            r1.add(List.of(
                    row.stt(),
                    Xlsx.StyledCell.of(row.statusDisplay(), row.status().xlsxTone),
                    row.method(),
                    row.host(),
                    row.path(),
                    row.pathTemplate(),
                    row.crudResource(),
                    row.branchSummary(),
                    row.httpStatus() > 0 ? row.httpStatus() : "-",
                    row.variantCount(),
                    row.tagsDisplay(),
                    row.ownersDisplay(),
                    row.note(),
                    exportedAt));
        }

        // Sheet 2: CRUD_Matrix
        Map<String, Set<String>> crudMethods = new LinkedHashMap<>();
        Map<String, Integer> crudCount = new LinkedHashMap<>();
        for (InventoryRow row : master) {
            String res = row.crudResource();
            crudMethods.computeIfAbsent(res, k -> new LinkedHashSet<>()).add(row.method());
            crudCount.merge(res, 1, Integer::sum);
        }
        List<String> h2 = List.of("Tài nguyên (CRUD)", "Tổng API", "GET", "POST", "PUT", "PATCH", "DELETE", "Gợi ý còn thiếu");
        int[] w2 = {24, 10, 10, 10, 10, 10, 10, 34};
        List<List<Object>> r2 = new ArrayList<>();
        for (Map.Entry<String, Set<String>> en : crudMethods.entrySet()) {
            Set<String> ms = en.getValue();
            List<String> missing = new ArrayList<>();
            for (String std : List.of("GET", "POST", "PUT", "DELETE")) {
                if (!ms.contains(std)) missing.add(std);
            }
            r2.add(List.of(
                    en.getKey(),
                    crudCount.getOrDefault(en.getKey(), 0),
                    ms.contains("GET") ? Xlsx.StyledCell.of("OK", Xlsx.CellTone.DONE_GREEN) : "-",
                    ms.contains("POST") ? Xlsx.StyledCell.of("OK", Xlsx.CellTone.DONE_GREEN) : "-",
                    ms.contains("PUT") ? Xlsx.StyledCell.of("OK", Xlsx.CellTone.DONE_GREEN) : "-",
                    ms.contains("PATCH") ? Xlsx.StyledCell.of("OK", Xlsx.CellTone.DONE_GREEN) : "-",
                    ms.contains("DELETE") ? Xlsx.StyledCell.of("OK", Xlsx.CellTone.DONE_GREEN) : "-",
                    missing.isEmpty() ? "Đủ bộ CRUD" : "Chưa thấy: " + String.join(", ", missing)));
        }

        // Sheet 3: By_Branch_Screen
        List<InventoryRow> byBranch = synthesize(engine, ViewContext.BRANCH_SCREEN, null, null, null);
        List<String> h3 = List.of("Nhánh UI / Màn hình", "Trạng thái", "Method", "Path", "HTTP", "Shared?", "Tags", "Người tìm");
        int[] w3 = {34, 18, 10, 40, 8, 10, 22, 16};
        List<List<Object>> r3 = new ArrayList<>();
        for (InventoryRow row : byBranch) {
            r3.add(List.of(
                    row.branchSummary(),
                    Xlsx.StyledCell.of(row.statusDisplay(), row.status().xlsxTone),
                    row.method(),
                    row.path(),
                    row.httpStatus() > 0 ? row.httpStatus() : "-",
                    row.sharedAcrossScreens() ? "Shared" : "",
                    row.tagsDisplay(),
                    row.ownersDisplay()));
        }

        // Sheet 4: Team_Summary
        Map<String, int[]> memberStats = new LinkedHashMap<>();
        int totalDone = 0, totalInfo = 0, totalMissing = 0, totalOos = 0, totalConflict = 0;
        for (InventoryRow row : master) {
            switch (row.status()) {
                case DONE -> totalDone++;
                case INFO -> totalInfo++;
                case MISSING -> totalMissing++;
                case OOS -> totalOos++;
                case CONFLICT -> totalConflict++;
                default -> { }
            }
            for (String o : row.owners()) {
                int[] st = memberStats.computeIfAbsent(o, k -> new int[4]);
                if (row.status() == ItemStatus.DONE) st[0]++;
                else if (row.status() == ItemStatus.INFO) st[1]++;
                else if (row.status() == ItemStatus.OOS) st[2]++;
                st[3]++;
            }
        }
        List<String> h4 = List.of("Thành viên / Tổng hợp", "Done (Xanh)", "Info (Vàng)", "OOS (Xám)", "Missing (Đỏ)", "Conflict (Cam)", "Tổng cộng");
        int[] w4 = {26, 14, 14, 14, 14, 16, 14};
        List<List<Object>> r4 = new ArrayList<>();
        r4.add(List.of(
                "TỔNG TOÀN DỰ ÁN",
                Xlsx.StyledCell.of(totalDone, Xlsx.CellTone.DONE_GREEN),
                Xlsx.StyledCell.of(totalInfo, Xlsx.CellTone.INFO_YELLOW),
                Xlsx.StyledCell.of(totalOos, Xlsx.CellTone.OOS_GRAY),
                Xlsx.StyledCell.of(totalMissing, Xlsx.CellTone.MISSING_RED),
                Xlsx.StyledCell.of(totalConflict, Xlsx.CellTone.CONFLICT_ORANGE),
                master.size()));
        for (Map.Entry<String, int[]> en : memberStats.entrySet()) {
            int[] st = en.getValue();
            r4.add(List.of(en.getKey(), st[0], st[1], st[2], 0, 0, st[3]));
        }

        return Xlsx.write(List.of(
                new Xlsx.OutSheet("Master_API_List", h1, r1, w1),
                new Xlsx.OutSheet("CRUD_Matrix", h2, r2, w2),
                new Xlsx.OutSheet("By_Branch_Screen", h3, r3, w3),
                new Xlsx.OutSheet("Team_Summary", h4, r4, w4)));
    }

    private EndpointMeta getOrCreate(String key, String method, String host, String pathTemplate) {
        return metaByKey.computeIfAbsent(key, k -> new EndpointMeta(k, method, host, pathTemplate));
    }

    /** Serializes custom tags & member identity for Prefs persistence. */
    public synchronized String exportTagsJson() {
        List<Object> list = new ArrayList<>(customTags);
        return Json.write(list);
    }

    public synchronized void importTagsJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Object parsed = Json.parse(json);
            if (parsed instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null && !o.toString().isBlank()) {
                        customTags.add(o.toString().trim());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // keep defaults
        }
    }
}
