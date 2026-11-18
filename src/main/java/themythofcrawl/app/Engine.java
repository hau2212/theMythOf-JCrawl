package themythofcrawl.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Config;
import themythofcrawl.core.CopyFormat;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.HttpBytes;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.excel.HitLog;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.excel.TargetList;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;
import themythofcrawl.model.Branch;
import themythofcrawl.model.BranchRule;
import themythofcrawl.model.Clock;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.PlaceHint;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.model.Roles;
import themythofcrawl.model.Service;
import themythofcrawl.place.PlacementBook;
import themythofcrawl.rolereq.RoleRequestBuilder;
import themythofcrawl.rolereq.RoleRules;
import themythofcrawl.send.SendDispatcher;

/**
 * The extension without Burp and without Swing: capture pipeline, branches, placement ('giữ chỗ',
 * pending, duplicates, hidden), role scopes, sending, Repeater, Excel list, copy labels, persistence.
 * Every method is thread-safe; state changes are recorded in {@link #changes()} for the UI.
 */
public final class Engine {
    public static final String PREF_CONFIG = "tmoc.config.v2";
    public static final String PREF_ROLES = "tmoc.roles.v1";
    public static final String PREF_ORIGINAL = "tmoc.original_profile.v1";

    /** A message handed to the capture pipeline. */
    public record Message(Service service, byte[] request, byte[] response, String url) {
    }

    private final Bridge bridge;
    private final Prefs prefs;
    private final RoleRequestBuilder builder;
    private final Changes changes = new Changes();
    private final Logbook logbook = new Logbook();
    private final Roles roles = new Roles();
    private final ActionEngine actionEngine = new ActionEngine();
    private final Set<Integer> forceRawGids = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService captureExec;
    private final ThreadPoolExecutor sendExec;
    private final ScheduledExecutorService scheduler;
    private final SendDispatcher<Work> dispatcher;
    private final AtomicInteger activeSends = new AtomicInteger();

    private volatile Config config;
    private volatile Pattern apiRegex;
    private volatile Set<String> staticExts = Set.of();
    private volatile Role originalProfile = new Role(Role.ORIGINAL_PROFILE_NAME, false, List.of());
    private volatile List<BranchRule> branchRules = List.of();
    private volatile TargetList targets;
    private final HitLog hits = new HitLog();
    private final PlacementBook book = new PlacementBook();

    // ---- workspace state (guarded by this)
    private Storage storage = new MemoryStorage();
    private final List<Branch> branches = new ArrayList<>();
    private int activeBranchId = 1;
    private int nextBranchId = 2;
    private int nextGid = 1;
    private final LinkedHashMap<Integer, ApiGroup> groups = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, ApiGroup> hidden = new LinkedHashMap<>();
    private final Map<String, List<ApiGroup>> copies = new HashMap<>();
    private final Set<String> seenKeys = new HashSet<>();
    private final Map<String, Integer> seenGid = new HashMap<>();
    private final List<String> recentDest = new ArrayList<>();
    private volatile List<ApiGroup> snapshot = List.of();
    private int skipped;
    private String lastSkip = "";
    private final Set<ApiGroup> dirtyGroups = new LinkedHashSet<>();
    private final Set<Integer> deletedGids = new HashSet<>();
    private boolean stateDirty;
    private volatile boolean closed;

    public Engine(Bridge bridge, Prefs prefs, RoleRequestBuilder builder) {
        this.bridge = bridge;
        this.prefs = prefs;
        this.builder = builder == null ? RoleRules.INSTANCE : builder;
        this.config = loadConfig();
        compileFilters(config);
        syncActionEngine(config);
        loadRoles();
        captureExec = Executors.newSingleThreadExecutor(daemon("JCrawl-capture"));
        sendExec = new ThreadPoolExecutor(config.threads, config.threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                daemon("JCrawl-send"));
        scheduler = Executors.newSingleThreadScheduledExecutor(daemon("JCrawl-timer"));
        dispatcher = new SendDispatcher<>(this::runJob, sendExec,
                (task, delayMs) -> scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS),
                () -> {
                    Config c = config;
                    return new SendDispatcher.Pacing(c.sendDelayMs, c.sendJitterPct, c.hostConcurrency, c.maxRetries);
                }, line -> log(line, null), System::currentTimeMillis, Math::random);
        branches.add(new Branch(1, Hierarchy.DEFAULT_PATH, null));
        scheduler.scheduleWithFixedDelay(this::flushQuietly, 2, 2, TimeUnit.SECONDS);
    }

    private void syncActionEngine(Config c) {
        actionEngine.setHoldAllActionAtProxy(c.holdActionAtProxy || c.actionMode == ActionEngine.ActionMode.HOLD_AT_PROXY);
        actionEngine.setDefaultLock2xxStrategy(c.lock2xxStrategy);
    }

    public ActionEngine actionEngine() {
        return actionEngine;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public Changes changes() {
        return changes;
    }

    public Logbook logbook() {
        return logbook;
    }

    public RoleRequestBuilder builder() {
        return builder;
    }

    public void log(String text, Integer gid) {
        Logbook.Line l = logbook.add(text, gid);
        changes.log(l);
        try {
            bridge.out((gid != null ? "[" + Text.apiLabel(gid) + "] " : "") + text);
        } catch (RuntimeException ignored) {
            // the UI log still has it
        }
    }

    // =================================================================== config / roles (global prefs)
    private Config loadConfig() {
        String raw = prefs.get(PREF_CONFIG);
        try {
            return Config.fromMap(raw == null ? null : Json.parseObject(raw));
        } catch (RuntimeException e) {
            return new Config().normalized();
        }
    }

    private void loadRoles() {
        String raw = prefs.get(PREF_ROLES);
        try {
            roles.setAll(raw == null ? Roles.template() : Roles.fromJson(raw));
        } catch (RuntimeException e) {
            roles.setAll(Roles.template());
        }
        String op = prefs.get(PREF_ORIGINAL);
        if (op != null) {
            try {
                Role r = Role.fromMap(Json.parse(op), 0);
                originalProfile = new Role(Role.ORIGINAL_PROFILE_NAME, r.enabled(), r.rules());
            } catch (RuntimeException ignored) {
                // keep the empty profile
            }
        }
    }

    public Config config() {
        return config;
    }

    private String compileFilters(Config c) {
        staticExts = Text.parseStaticExtensions(c.staticExt);
        if (c.apiRegex.isBlank()) {
            apiRegex = null;
            return null;
        }
        try {
            apiRegex = Pattern.compile(c.apiRegex);
            return null;
        } catch (PatternSyntaxException e) {
            apiRegex = null;
            return e.getDescription();
        }
    }

    /** Store a new configuration; returns the API regex error, if any. */
    public String applyConfig(Config next) {
        Config c = next.copy();
        Config old = config;
        String err = compileFilters(c);
        config = c;
        syncActionEngine(c);
        prefs.set(PREF_CONFIG, Json.write(c.toMap()));
        if (targets != null && (old.targetAllowPrefix != c.targetAllowPrefix || old.targetIdsAsParams != c.targetIdsAsParams
                || old.targetQueryMode != c.targetQueryMode)) {
            retag();
        }
        if (old.autoSend && !c.autoSend) {
            cancelPending("Đã DỪNG gửi");
        }
        if (c.threads != sendExec.getMaximumPoolSize()) {
            if (c.threads > sendExec.getMaximumPoolSize()) {
                sendExec.setMaximumPoolSize(c.threads);
                sendExec.setCorePoolSize(c.threads);
            } else {
                sendExec.setCorePoolSize(c.threads);
                sendExec.setMaximumPoolSize(c.threads);
            }
        }
        if (old.showGhosts != c.showGhosts) {
            changes.structure();
        }
        changes.status();
        return err;
    }

    public Roles roles() {
        return roles;
    }

    public void saveRoles() {
        prefs.set(PREF_ROLES, Roles.toJson(roles.all()));
        changes.status();
    }

    public Role originalProfile() {
        return originalProfile;
    }

    public void saveOriginalProfile(Role profile) {
        originalProfile = new Role(Role.ORIGINAL_PROFILE_NAME, profile.enabled(), profile.rules());
        prefs.set(PREF_ORIGINAL, Json.write(originalProfile.toMap()));
        changes.status();
    }

    // =================================================================== branches
    public synchronized List<Branch> branches() {
        return new ArrayList<>(branches);
    }

    public synchronized Branch activeBranch() {
        for (Branch b : branches) {
            if (b.id() == activeBranchId) {
                return b;
            }
        }
        return branches.get(branches.size() - 1);
    }

    public synchronized Branch branch(int id) {
        for (Branch b : branches) {
            if (b.id() == id) {
                return b;
            }
        }
        return null;
    }

    public synchronized Branch branchByName(String name) {
        for (Branch b : branches) {
            if (Hierarchy.same(b.name(), name)) {
                return b;
            }
        }
        return null;
    }

    public synchronized Branch findOrCreateBranch(String path, boolean makeActive) {
        String raw = Text.nz(path).trim();
        String clean = raw.contains(">") ? Hierarchy.normalize(raw) : (raw.isEmpty() ? Hierarchy.DEFAULT_PATH : raw);
        Branch b = branchByName(clean);
        if (b == null) {
            b = new Branch(nextBranchId++, clean, null);
            branches.add(b);
            stateDirty = true;
            changes.structure();
        }
        if (makeActive) {
            activeBranchId = b.id();
            stateDirty = true;
            changes.status();
        }
        return b;
    }

    public synchronized void setActiveBranch(int id) {
        if (branch(id) != null) {
            activeBranchId = id;
            stateDirty = true;
            changes.status();
        }
    }

    /** The fixed destination of every new request; only changes when chosen again. */
    public Branch setDestination(String path) {
        Branch b = findOrCreateBranch(path, true);
        synchronized (this) {
            recentDest.remove(b.name());
            recentDest.add(0, b.name());
            while (recentDest.size() > 12) {
                recentDest.remove(recentDest.size() - 1);
            }
        }
        log("➜ Đích cho request mới: " + b.name(), null);
        return b;
    }

    public synchronized List<String> recentDestinations() {
        return new ArrayList<>(recentDest);
    }

    public Branch startNewBranch(String name) {
        Branch b;
        synchronized (this) {
            String raw = Text.nz(name).trim();
            b = new Branch(nextBranchId++, raw.contains(">") ? Hierarchy.normalize(raw) : (raw.isEmpty() ? null : raw), null);
            branches.add(b);
            activeBranchId = b.id();
            stateDirty = true;
        }
        changes.structure();
        log("Chuyển sang " + b.label() + " - các API tiếp theo sẽ được gom vào chức năng này.", null);
        return b;
    }

    /** Rename one branch (its APIs follow). */
    public void renameBranch(int id, String name) {
        Branch b = branch(id);
        if (b == null) {
            return;
        }
        synchronized (this) {
            b.rename(name);
            for (ApiGroup g : allGroups()) {
                if (g.branchId() == id) {
                    g.setBranch(id, b.name());
                    dirtyGroups.add(g);
                }
            }
            stateDirty = true;
        }
        changes.structure();
        log("Đổi tên chức năng #" + id + " -> " + b.label(), null);
    }

    /** Rename a folder prefix everywhere (branches, APIs, remembered places), deeper levels kept. */
    public void renamePrefix(String oldPrefix, String newPrefix) {
        String o = Hierarchy.normalize(oldPrefix);
        String n = Hierarchy.normalize(newPrefix);
        synchronized (this) {
            for (Branch b : branches) {
                if (Hierarchy.matchesPrefix(b.name(), o)) {
                    b.rename(Hierarchy.replacePrefix(b.name(), o, n));
                }
            }
            for (ApiGroup g : allGroups()) {
                if (Hierarchy.matchesPrefix(g.branch(), o)) {
                    g.setBranch(g.branchId(), Hierarchy.replacePrefix(g.branch(), o, n));
                    dirtyGroups.add(g);
                }
            }
            List<BranchRule> rs = new ArrayList<>();
            for (BranchRule r : branchRules) {
                rs.add(Hierarchy.matchesPrefix(r.prefix(), o) ? new BranchRule(Hierarchy.replacePrefix(r.prefix(), o, n), r.roles()) : r);
            }
            branchRules = rs;
            stateDirty = true;
        }
        int places = book.renamePrefix(o, n);
        book.event(PlacementBook.Action.RENAME, null, null, "", "", o, n, places + " vị trí giữ chỗ cập nhật");
        changes.structure();
        log("Đổi tên nhánh '" + o + "' -> '" + n + "' (cập nhật toàn bộ sub-folder con).", null);
    }

    /** Delete a branch: its APIs too, or they move up to the parent branch. */
    public void deletePrefix(String prefix, boolean deleteApis) {
        String clean = Hierarchy.normalize(prefix);
        String parent = Hierarchy.depth(clean) > 1 ? Hierarchy.parent(clean) : Hierarchy.ROOT;
        if (parent.equalsIgnoreCase(clean)) {
            parent = Hierarchy.ROOT;
        }
        List<ApiGroup> matched;
        synchronized (this) {
            branches.removeIf(b -> Hierarchy.matchesPrefix(b.name(), clean));
            if (branches.isEmpty()) {
                branches.add(new Branch(nextBranchId++, parent, null));
            }
            if (branch(activeBranchId) == null) {
                activeBranchId = branches.get(branches.size() - 1).id();
            }
            matched = groupsUnder(clean);
            stateDirty = true;
        }
        Branch fallback = findOrCreateBranch(parent, false);
        if (deleteApis && !matched.isEmpty()) {
            remove(matched, "xóa cả nhánh " + clean);
        } else if (!matched.isEmpty()) {
            moveToBranch(matched, fallback.id(), PlacementBook.Action.MOVE, false,
                    "nhánh " + clean + " bị xóa, API chuyển lên nhánh cha", true);
        }
        changes.structure();
        log("Đã xóa nhánh '" + clean + "'.", null);
    }

    /** Every path that exists: branches, APIs, remembered places (with all their levels). */
    public Set<String> knownPaths() {
        Set<String> paths = new java.util.TreeSet<>(Comparator.comparing(Hierarchy::key));
        List<String> raw = new ArrayList<>();
        for (Branch b : branches()) {
            raw.add(b.name());
        }
        for (ApiGroup g : groups()) {
            raw.add(g.branch());
        }
        for (PlacementBook.Record r : book.records()) {
            if (r.place != null) {
                raw.add(r.place);
            }
        }
        for (String p : raw) {
            paths.addAll(Hierarchy.levels(p));
        }
        return paths;
    }

    // =================================================================== groups
    public List<ApiGroup> groups() {
        return snapshot;
    }

    private void refreshSnapshot() {
        snapshot = List.copyOf(groups.values());
    }

    private synchronized List<ApiGroup> allGroups() {
        List<ApiGroup> out = new ArrayList<>(groups.values());
        out.addAll(hidden.values());
        return out;
    }

    public synchronized List<ApiGroup> hiddenGroups() {
        return new ArrayList<>(hidden.values());
    }

    public synchronized ApiGroup group(int gid) {
        ApiGroup g = groups.get(gid);
        return g != null ? g : hidden.get(gid);
    }

    public List<ApiGroup> groupsUnder(String prefix) {
        List<ApiGroup> out = new ArrayList<>();
        for (ApiGroup g : groups()) {
            if (Hierarchy.matchesPrefix(g.branch(), prefix)) {
                out.add(g);
            }
        }
        return out;
    }

    public List<ApiGroup> pendingGroups() {
        return groups().stream().filter(ApiGroup::pending).toList();
    }

    public synchronized List<ApiGroup> copiesOf(String key) {
        List<ApiGroup> l = key == null ? null : copies.get(key);
        return l == null ? List.of() : new ArrayList<>(l);
    }

    public synchronized Map<String, List<ApiGroup>> copies() {
        Map<String, List<ApiGroup>> out = new LinkedHashMap<>();
        copies.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        return out;
    }

    private synchronized void addCopy(ApiGroup g) {
        copies.computeIfAbsent(g.bookKey(), k -> new ArrayList<>()).add(g);
        refreshDupTotals(g.bookKey());
    }

    private synchronized void dropCopy(ApiGroup g) {
        List<ApiGroup> l = copies.get(g.bookKey());
        if (l != null) {
            l.remove(g);
            if (l.isEmpty()) {
                copies.remove(g.bookKey());
            } else {
                refreshDupTotals(g.bookKey());
            }
        }
    }

    private synchronized void refreshDupTotals(String key) {
        List<ApiGroup> l = copies.getOrDefault(key, List.of());
        for (ApiGroup c : l) {
            if (c.dupTotal() != l.size()) {
                c.setDupTotal(l.size());
                changes.dirty(c);
            }
        }
    }

    public synchronized int skippedCount() {
        return skipped;
    }

    public synchronized String lastSkipReason() {
        return lastSkip;
    }

    private synchronized void skip(String reason) {
        skipped++;
        lastSkip = reason;
        changes.status();
    }

    public void touch(ApiGroup g) {
        synchronized (this) {
            dirtyGroups.add(g);
        }
        changes.dirty(g);
    }

    // =================================================================== capture
    public String placeKey(String method, Service s, String path) {
        return Text.dedupKey(method, s.protocol(), s.host(), s.port(), path, config.dedupNormalizeIds);
    }

    public TargetEntry targetMatch(String method, String path, String host) {
        TargetList t = targets;
        if (t == null) {
            return null;
        }
        Config c = config;
        return t.match(method, path, c.targetAllowPrefix, host, c.targetIdsAsParams,
                TargetList.QueryMode.valueOf(c.targetQueryMode.name()));
    }

    /**
     * Cheap checks on the proxy thread before copying anything: OPTIONS/HEAD, static files, and either
     * the Excel list (list-only mode) or the API regex. Returns the skip reason, or null to go on.
     */
    public String quickReject(String method, String path, String host) {
        Config c = config;
        if (c.ignoreOptionsHead && ("OPTIONS".equals(method) || "HEAD".equals(method))) {
            return "method " + method + " ignored";
        }
        if (c.ignoreStatic && Text.isStaticPath(path, staticExts)) {
            return "static extension";
        }
        if (c.targetOnly && targets != null) {
            return targetMatch(method, path, host) == null ? "không có trong danh sách Excel" : null;
        }
        Pattern rx = apiRegex;
        if (c.apiRegexEnabled && rx != null && !rx.matcher(Text.stripQuery(path)).find()) {
            return "API path regex mismatch";
        }
        return null;
    }

    /** Proxy capture: a cheap check here, the rest on the single capture thread (stable API numbers). */
    public void onProxyMessage(Message m) {
        Config c = config;
        if (!c.captureEnabled || closed) {
            return;
        }
        if (m.response() != null && "true".equalsIgnoreCase(HttpBytes.header(m.response(), "X-JCrawl-Held"))) {
            // Đã được bắt bởi onProxyHeldRequest (Phương pháp 1: HOLD_AT_PROXY)
            return;
        }
        String[] rl = Text.requestLine(m.request());
        if (rl == null) {
            return;
        }
        String reason = quickReject(rl[0], rl[1], m.service().host());
        if (reason != null) {
            skip(reason);
            return;
        }
        int dest = activeBranch().id();
        captureExec.execute(() -> safely(() -> process(m, "Proxy", true, dest)));
    }

    /**
     * Phương pháp 1 (HOLD_AT_PROXY): Kiểm tra xem request từ trình duyệt đi qua Proxy có cần được
     * chặn lại trước khi tới Server (để giữ nguyên trạng thái ID trên Server) hay không.
     */
    public boolean shouldHoldAtProxy(Service svc, byte[] request, String url) {
        Config c = config;
        if (!c.captureEnabled || closed || svc == null || request == null) {
            return false;
        }
        String[] rl = Text.requestLine(request);
        if (rl == null) {
            return false;
        }
        String method = rl[0];
        String path = rl[1];
        if (!ActionEngine.isActionMethod(method)) {
            return false;
        }
        boolean holdMode = c.holdActionAtProxy
                || c.actionMode == ActionEngine.ActionMode.HOLD_AT_PROXY
                || actionEngine.isHoldAllActionAtProxy()
                || actionEngine.isEndpointProxyHold(method, path);
        if (!holdMode) {
            return false;
        }
        if (quickReject(method, path, svc.host()) != null) {
            return false;
        }
        String fullUrl = url != null ? url : svc.protocol() + "://" + svc.hostLabel() + path;
        TargetEntry target = targetMatch(method, path, svc.host());
        return filterReason(c, fullUrl, method, path, target) == null;
    }

    /**
     * Phương pháp 1 (HOLD_AT_PROXY): Thu nhận Action request đã được giữ tại Proxy (chưa thực thi trên Server).
     */
    public void onProxyHeldRequest(Service svc, byte[] request, String url) {
        if (closed) {
            return;
        }
        int dest = activeBranch().id();
        Message m = new Message(svc, request, null, url);
        captureExec.execute(() -> safely(() -> process(m, "Proxy-Held", true, dest)));
    }

    /** Manual capture from the context menu. */
    public void submitManual(List<Message> ms, boolean applyFilters, int branchId) {
        for (Message m : ms) {
            captureExec.execute(() -> safely(() -> process(m, "Manual", applyFilters, branchId)));
        }
        log("Manual trigger: " + ms.size() + " message(s) queued -> Feature #" + branchId + " ("
                + (applyFilters ? "capture filters applied" : "filters bypassed") + ").", null);
    }

    private void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            bridge.err(Text.EXT_NAME + ": " + e);
            log("Lỗi xử lý: " + e, null);
        }
    }

    /** Wait until queued captures are processed (tests, export). */
    public void awaitCaptures() {
        try {
            captureExec.submit(() -> { }).get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // best effort
        }
    }

    private String filterReason(Config c, String url, String method, String path, TargetEntry target) {
        boolean targetMode = c.targetOnly && targets != null;
        if (c.ignoreOptionsHead && ("OPTIONS".equals(method) || "HEAD".equals(method))) {
            return "method " + method + " ignored";
        }
        if (c.inScopeOnly && !bridge.inScope(url)) {
            return "out of scope";
        }
        if (c.ignoreStatic && Text.isStaticPath(path, staticExts)) {
            return "static extension";
        }
        if (targetMode) {
            if (target == null) {
                return "không có trong danh sách Excel";
            }
        } else {
            Pattern rx = apiRegex;
            if (c.apiRegexEnabled && rx != null && !rx.matcher(Text.stripQuery(path)).find()) {
                return "API path regex mismatch";
            }
        }
        return null;
    }

    /** The capture pipeline (capture thread). Returns the new API, or null when skipped. */
    public ApiGroup process(Message m, String source, boolean applyFilters, Integer branchId) {
        Config c = config;
        String[] rl = Text.requestLine(m.request());
        if (rl == null) {
            return null;
        }
        String method = rl[0];
        String path = rl[1];
        Service svc = m.service();
        String url = m.url() != null ? m.url() : svc.protocol() + "://" + svc.hostLabel() + path;
        Branch feat = branchId != null && branch(branchId) != null ? branch(branchId) : activeBranch();
        String standing = feat.name();
        TargetEntry target = targetMatch(method, path, svc.host());
        if (target != null && !target.position().isEmpty() && c.targetUsePosition) {
            feat = findOrCreateBranch(target.position(), false);
        }
        final Branch[] filed = {feat};
        java.util.function.BiConsumer<HitLog.Result, Object[]> hit = (result, extra) -> {
            if (target != null) {
                hits.record(target, result, (Integer) extra[0], method, path.length() > 400 ? path.substring(0, 400) : path,
                        svc.host(), standing, filed[0].name(), source, (String) extra[1]);
                changes.structure();
                synchronized (this) {
                    stateDirty = true;
                }
            }
        };
        String pkey = placeKey(method, svc, path);
        String key = null;
        if (applyFilters) {
            String reason = filterReason(c, url, method, path, target);
            if (reason != null) {
                skip(reason);
                hit.accept(HitLog.Result.FILTERED, new Object[] {null, reason});
                if ("Manual".equals(source)) {
                    log("Skipped " + method + " " + url + " (" + reason + ")", null);
                }
                return null;
            }
            if (c.dedup) {
                String base = Text.dedupKey(method, svc.protocol(), svc.host(), svc.port(), path, c.dedupNormalizeIds);
                key = c.dedupPerBranch ? "F" + feat.id() + "|" + base : base;
                boolean seen;
                Integer earlier;
                synchronized (this) {
                    seen = seenKeys.contains(key);
                    if (!seen) {
                        seenKeys.add(key);
                        stateDirty = true;
                    }
                    earlier = seenGid.get(key);
                }
                if (seen) {
                    // Same endpoint again: skipped, unless it shows up at a NEW place (another branch than
                    // every copy so far) - then it becomes a duplicate copy to decide on.
                    List<ApiGroup> cps = copiesOf(pkey);
                    boolean newPlace = false;
                    if (c.dupNewPlace && !cps.isEmpty()) {
                        newPlace = true;
                        for (ApiGroup cp : cps) {
                            if (Hierarchy.same(cp.branch(), feat.name())) {
                                newPlace = false;
                                break;
                            }
                        }
                    }
                    if (!newPlace) {
                        skip("dedup");
                        hit.accept(HitLog.Result.DUP, new Object[] {earlier, ""});
                        book.ensure(pkey, method, svc.host(), path);
                        book.seen(pkey, standing, true);
                        if ("Manual".equals(source)) {
                            log("Skipped " + method + " " + url + " (duplicate endpoint)", null);
                        }
                        return null;
                    }
                }
            }
        }

        // Placement: a deleted API that comes back returns to its remembered place ('giữ chỗ'); a duplicate
        // met at a new place asks whether to keep it there; new APIs wait (grey) for a confirmation.
        PlacementBook.Record rec = book.get(pkey);
        List<ApiGroup> cps = copiesOf(pkey);
        boolean pending = c.confirmNewApis;
        PlacementBook.Action action = pending ? PlacementBook.Action.PENDING : PlacementBook.Action.CAPTURE;
        PlaceHint hint = null;
        if (!cps.isEmpty()) {
            pending = true;
            action = PlacementBook.Action.DUP;
        } else if (rec != null && rec.place != null && c.restoreKnownPlace && !Hierarchy.same(rec.place, feat.name())) {
            boolean oldExists = branchByName(rec.place) != null;
            String foundAt = feat.name();
            feat = findOrCreateBranch(rec.place, false);
            hint = new PlaceHint(feat.name(), foundAt, !oldExists, rec.deleted);
            pending = true;
            action = PlacementBook.Action.RETURN;
        }
        filed[0] = feat;
        List<RoleScopes.Indexed> eff = RoleScopes.effective(roles.all(), feat.name(), branchRules, null);

        int gid;
        synchronized (this) {
            gid = nextGid++;
            stateDirty = true;
        }
        Blob req = storage.put(gid, GroupCodec.ORIG_REQ, m.request());
        Blob resp = m.response() != null ? storage.put(gid, GroupCodec.ORIG_RESP, m.response()) : null;
        ApiGroup g = new ApiGroup(gid, svc, method, path, url, source, feat.id(), feat.name(), req, resp, Clock.hms(),
                System.currentTimeMillis());
        g.setTarget(target);
        g.setBookKey(pkey);
        g.setPending(pending);
        g.setPlaceHint(hint);
        boolean isHeld = "Proxy-Held".equals(source)
                || (source != null && source.contains("Chặn"))
                || (m.response() != null && "true".equalsIgnoreCase(HttpBytes.header(m.response(), "X-JCrawl-Held")));
        if (isHeld) {
            g.setHeldAtProxy(true);
            g.setActiveIdNote("Đã chặn ở Proxy (Server giữ nguyên ID)");
        } else if (m.response() != null) {
            g.setOrigMeta(HttpBytes.statusCode(m.response()), HttpBytes.bodyLength(m.response()));
            actionEngine.harvest(method, path, m.request(), m.response(),
                    g.origStatus() != null ? g.origStatus() : 0, gid);
        }
        Integer autoProd = actionEngine.findProducerGid(path);
        if (autoProd != null && autoProd != gid) {
            g.setProducerGid(autoProd);
        }
        book.ensure(pkey, method, svc.host(), path);
        g.setDupNo(book.nextCopyNo(pkey));
        g.setDupPending(!cps.isEmpty());
        if (cps.isEmpty()) {
            book.live(pkey, gid, pending);
        }
        book.seen(pkey, standing, false);
        if (!pending) {
            book.setPlace(pkey, feat.name());
        }
        if (!cps.isEmpty()) {
            ApiGroup first = cps.stream().min(Comparator.comparingInt(ApiGroup::dupNo)).orElse(cps.get(0));
            book.event(action, pkey, gid, method, path, first.branch(), feat.name(),
                    "bản #" + g.dupNo() + "; bản #" + first.dupNo() + " (API #" + first.gid() + ") đang ở " + first.branch());
        } else if (hint != null) {
            book.event(action, pkey, gid, method, path, hint.found(), feat.name(), "từng ở đây (đã xóa " + hint.deleted()
                    + " lần); vừa gặp tại " + hint.found() + (hint.oldMissing() ? "; nhánh cũ đã bị xóa nên được tạo lại" : ""));
        } else {
            book.event(action, pkey, gid, method, path, Hierarchy.same(standing, feat.name()) ? null : standing, feat.name(),
                    target != null && !target.position().isEmpty() ? "theo dòng Excel" : "");
        }
        if (key != null) {
            g.setDedupKey(key);
            synchronized (this) {
                seenGid.put(key, gid);
            }
        }
        hit.accept(HitLog.Result.NEW, new Object[] {gid, cps.isEmpty() ? "" : "bản trùng #" + g.dupNo()});
        g.origResult().setTabName(Text.repeaterTabName(gid, method, path, "00_Original_Crawl", c.shortPathLen, feat.id(),
                c.includeBranchInTab));
        g.setResults(makeResults(g, eff, c));
        applyOriginalProfile(g);
        g.origResult().setStatus(g.origStatus());
        g.origResult().setLength(g.origLength());
        Audit.recalc(g);
        synchronized (this) {
            groups.put(gid, g);
            refreshSnapshot();
            dirtyGroups.add(g);
        }
        addCopy(g);
        changes.added(g);
        String note = "";
        if (g.results().isEmpty()) {
            note += " (không có role nào bật - chỉ lưu API gốc)";
        }
        if (!g.origResult().rules().isEmpty()) {
            note += " (API gốc áp profile: " + g.origResult().rules().size() + " rule)";
        }
        if (target != null) {
            note += " (khớp dòng STT " + target.sttText() + " trong file Excel)";
        }
        if (g.heldAtProxy()) {
            note += " [Đã chặn ở Proxy - Server giữ nguyên trạng thái cho tới khi bấm Gửi]";
        }
        log("Bắt từ " + source + ": " + method + " " + url + " -> 1 API gốc + " + g.results().size() + " role" + note, gid);
        if (c.autoRepeater) {
            toRepeater(g, defaultTargets(g, false), false);
        }
        if (c.autoSend) {
            List<RoleResult> t = new ArrayList<>(g.results());
            if (c.autoSendOriginal) {
                t.add(0, g.origResult());
            }
            if (!t.isEmpty()) {
                replay(g, t);
            }
        }
        return g;
    }

    private List<RoleResult> makeResults(ApiGroup g, List<RoleScopes.Indexed> eff, Config c) {
        List<RoleResult> out = new ArrayList<>();
        for (RoleScopes.Indexed ir : eff) {
            Role role = ir.role();
            String display = Text.displayRoleName(role.name(), ir.index(), c.numberRolesInTab);
            RoleResult r = new RoleResult(display, role.name(), ir.index(), false, role.strippedAuth(), role.rules());
            r.setId(g.nextResultId());
            r.setTabName(Text.repeaterTabName(g.gid(), g.method(), g.path(), display, c.shortPathLen, g.branchId(),
                    c.includeBranchInTab));
            out.add(r);
        }
        return out;
    }

    /** Snapshot the API gốc profile's rules onto the original row (like role rules at capture time). */
    private void applyOriginalProfile(ApiGroup g) {
        Role prof = originalProfile;
        List<HeaderRule> rules = prof.enabled() ? prof.rules() : List.of();
        g.origResult().setRules(rules);
        g.origResult().setRoleName(Role.ORIGINAL_LABEL + (rules.isEmpty() ? "" : "  + profile (" + rules.size() + " rule)"));
    }

    // =================================================================== requests
    /** Bytes a row sends: hand-edited request, the original, or the role's variant built on demand. */
    public byte[] requestOf(ApiGroup g, RoleResult r) {
        if (r.customRequest() != null) {
            return r.customRequest().bytes();
        }
        byte[] orig = Blob.bytesOf(g.origRequest());
        if (orig == null) {
            return null;
        }
        if (r.original() && r.rules().isEmpty()) {
            return orig;
        }
        return builder.build(orig, r.rules());
    }

    public byte[] responseOf(ApiGroup g, RoleResult r) {
        if (r.responseBlob() != null) {
            return r.responseBlob().bytes();
        }
        return r.original() ? Blob.bytesOf(g.origResponse()) : null;
    }

    public void setCustomRequest(ApiGroup g, RoleResult r, byte[] request) {
        r.setCustomRequest(request == null ? null : storage.put(g.gid(), GroupCodec.reqName(r), request));
        touch(g);
    }

    /** What a plain Send / Push acts on: every role, or the original when the API has no role. */
    public List<RoleResult> defaultTargets(ApiGroup g, boolean includeOriginal) {
        if (g.results().isEmpty()) {
            return List.of(g.origResult());
        }
        List<RoleResult> out = new ArrayList<>();
        if (includeOriginal) {
            out.add(g.origResult());
        }
        out.addAll(g.results());
        return out;
    }

    /** Entries of one API to push / send: optionally the original, then the chosen roles. */
    public static List<RoleResult> selectTargets(ApiGroup g, boolean includeOriginal, Collection<String> roleNames,
                                                 boolean onlyFlagged) {
        List<RoleResult> out = new ArrayList<>();
        if (includeOriginal) {
            out.add(g.origResult());
        }
        for (RoleResult r : g.results()) {
            if (!roleNames.contains(r.roleName())) {
                continue;
            }
            if (onlyFlagged && !Audit.level(g, r).flagged()) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    /** [(role display name, number of APIs having it)] in role order. */
    public static List<Map.Entry<String, Integer>> roleNamesIn(Collection<ApiGroup> gs) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, int[]> order = new HashMap<>();
        for (ApiGroup g : gs) {
            for (RoleResult r : g.results()) {
                counts.computeIfAbsent(r.roleName(), k -> {
                    order.put(k, new int[] {r.roleIndex(), order.size()});
                    return new int[1];
                })[0]++;
            }
        }
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        counts.keySet().stream().sorted(Comparator.comparingInt((String k) -> order.get(k)[0]).thenComparingInt(k -> order.get(k)[1]))
                .forEach(k -> out.add(Map.entry(k, counts.get(k)[0])));
        return out;
    }
    // =================================================================== sending
    /** A request waiting in / running from the send queue. */
    public record Work(ApiGroup group, RoleResult result) {
    }

    private static final Set<String> BUSY = Set.of(ResultState.QUEUED, ResultState.SENDING, ResultState.RETRY);

    public ActionEngine.ActionMode effectiveActionMode(ApiGroup g) {
        if (g != null && g.actionModeOverride() != null) {
            return g.actionModeOverride();
        }
        return config.actionMode != null ? config.actionMode : ActionEngine.ActionMode.AUTO_SMART;
    }

    public void setApiActionMode(ApiGroup g, ActionEngine.ActionMode mode) {
        if (g == null) return;
        g.setActionModeOverride(mode);
        g.setActiveIdNote(mode == null ? "Theo mặc định (" + config.actionMode.label + ")" : mode.label);
        touch(g);
        log("Đặt chế độ xử lý Action cho API #" + g.gid() + " -> " + (mode == null ? "Mặc định" : mode.label), g.gid());
    }

    public void toggleEndpointProxyHold(ApiGroup g, boolean hold) {
        if (g == null) return;
        actionEngine.setEndpointProxyHold(g.method(), g.path(), hold);
        g.setActiveIdNote(hold ? "Chặn ở Proxy (Giữ ID Server)" : null);
        touch(g);
        log((hold ? "Bật" : "Tắt") + " Chặn tại Proxy (Phương pháp 1) cho endpoint " + g.method() + " "
                + ActionEngine.normalizePathTemplate(g.path()), g.gid());
    }

    /**
     * Phương pháp 2 - Cách 1A (INHERIT_200): Kế thừa kết quả 200 OK ban đầu mà không gửi lại ID đã xóa lên Server.
     */
    public boolean inheritInitial2xx(ApiGroup g, Collection<RoleResult> targets) {
        if (g == null || !g.hasFirstOkSnapshot()) {
            return false;
        }
        Collection<RoleResult> rows = (targets == null || targets.isEmpty()) ? defaultTargets(g, true) : targets;
        synchronized (g) {
            for (RoleResult r : rows) {
                r.setResponse(g.firstOkResponse());
                r.setStatus(g.firstOkStatus());
                r.setLength(g.firstOkLength());
                r.setState(ResultState.DONE);
                r.setError("");
                if (r.original()) {
                    g.setOriginal(g.firstOkRequest() != null ? g.firstOkRequest() : g.origRequest(), g.firstOkResponse());
                    g.setOrigMeta(g.firstOkStatus(), g.firstOkLength());
                }
            }
            g.setActiveIdNote("Đã kế thừa Snapshot " + g.firstOkStatus() + " OK");
        }
        Audit.recalc(g);
        touch(g);
        log("Khóa 2xx (Cách 1A - Kế thừa 200 OK): Đã áp kết quả " + g.firstOkStatus() + " OK ban đầu cho " + rows.size()
                + " dòng (không gửi lại ID đã xóa lên Server).", g.gid());
        return true;
    }

    /**
     * Phương pháp 2 - Khôi phục lại Snapshot Response 200 OK gốc sau khi người dùng cưỡng bức gửi lại bị lỗi 4xx.
     */
    public boolean restoreInitial2xx(ApiGroup g) {
        if (g == null || !g.hasFirstOkSnapshot()) {
            return false;
        }
        synchronized (g) {
            if (g.firstOkRequest() != null) {
                g.setOriginal(g.firstOkRequest(), g.firstOkResponse());
            } else {
                g.setOriginal(g.origRequest(), g.firstOkResponse());
            }
            g.setOrigMeta(g.firstOkStatus(), g.firstOkLength());
            if (g.firstOkPath() != null && !g.firstOkPath().equals(g.path())) {
                String newUrl = g.service().protocol() + "://" + g.service().hostLabel() + g.firstOkPath();
                g.setPathAndUrl(g.firstOkPath(), newUrl);
            }
            RoleResult orig = g.origResult();
            orig.setResponse(g.firstOkResponse());
            orig.setStatus(g.firstOkStatus());
            orig.setLength(g.firstOkLength());
            orig.setState(ResultState.DONE);
            orig.setError("");
            g.setActiveIdNote("Đã khôi phục " + g.firstOkStatus() + " OK gốc");
        }
        Audit.recalc(g);
        touch(g);
        log("Đã khôi phục Response " + g.firstOkStatus() + " OK ban đầu cho API #" + g.gid(), g.gid());
        return true;
    }

    /**
     * Phương pháp 2 - Cách 2 (FORCE_RAW_SEND): Cưỡng bức gửi lại request nguyên bản lên Server (chấp nhận rủi ro 4xx).
     */
    public void forceRawReplay(ApiGroup g, Collection<RoleResult> targets) {
        if (g == null) return;
        forceRawGids.add(g.gid());
        g.setActiveIdNote("Gửi cưỡng bức nguyên bản");
        Collection<RoleResult> rows = (targets == null || targets.isEmpty()) ? defaultTargets(g, true) : targets;
        log("Khóa 2xx (Cách 2 - Cưỡng bức gửi lại): Đẩy nguyên bản API #" + g.gid()
                + " lên Server (bản Snapshot 200 OK vẫn được lưu để khôi phục khi cần).", g.gid());
        replayInternal(g, rows);
    }

    /**
     * Phương pháp 3 (LIVE_ID_POOL): Đổi sang một ID cụ thể (từ Giỏ ID sống hoặc do người dùng nhập) rồi Gửi lên Server.
     */
    public boolean replaceIdAndSend(ApiGroup g, String oldId, String newId, boolean updateApiGroup, Collection<RoleResult> targets) {
        if (g == null || newId == null || newId.isBlank()) {
            return false;
        }
        byte[] origBytes = Blob.bytesOf(g.origRequest());
        if (origBytes == null) {
            return false;
        }
        String targetOldId = oldId;
        if (targetOldId == null || targetOldId.isBlank()) {
            List<String> detected = actionEngine.detectIds(g.path(), origBytes);
            if (!detected.isEmpty()) {
                targetOldId = detected.get(0);
            }
        }
        if (targetOldId == null || targetOldId.isBlank()) {
            return false;
        }
        byte[] replacedReq = ActionEngine.replaceIdInRequest(origBytes, targetOldId, newId.trim());
        String replacedPath = ActionEngine.replaceIdInPath(g.path(), targetOldId, newId.trim());
        if (updateApiGroup) {
            Blob reqBlob = storage.put(g.gid(), GroupCodec.ORIG_REQ, replacedReq);
            g.setOriginal(reqBlob, g.origResponse());
            String newUrl = g.service().protocol() + "://" + g.service().hostLabel() + replacedPath;
            g.setPathAndUrl(replacedPath, newUrl);
            for (RoleResult r : g.entries()) {
                if (r.customRequest() != null) {
                    byte[] cBytes = r.customRequest().bytes();
                    if (cBytes != null) {
                        byte[] newCustom = ActionEngine.replaceIdInRequest(cBytes, targetOldId, newId.trim());
                        r.setCustomRequest(storage.put(g.gid(), GroupCodec.reqName(r), newCustom));
                    }
                }
            }
        }
        g.setActiveIdNote("Đổi ID: " + targetOldId + " -> " + newId.trim());
        touch(g);
        log("Phương pháp 3 (Đổi ID sống): Đã thay ID [" + targetOldId + "] -> [" + newId.trim() + "] cho API #" + g.gid() + " và gửi lên Server.", g.gid());
        Collection<RoleResult> rows = (targets == null || targets.isEmpty()) ? defaultTargets(g, true) : targets;
        forceRawGids.add(g.gid());
        replayInternal(g, rows);
        return true;
    }

    /**
     * Phương pháp 3 (LIVE_ID_POOL - Tự động): Tự lấy ID sống tiếp theo trong Giỏ ID và Gửi.
     */
    public boolean sendWithNextLiveId(ApiGroup g, Collection<RoleResult> targets) {
        if (g == null) return false;
        byte[] origBytes = Blob.bytesOf(g.origRequest());
        List<String> ids = actionEngine.detectIds(g.path(), origBytes);
        String oldId = ids.isEmpty() ? null : ids.get(0);
        String nextId = actionEngine.nextLiveId(g.path(), oldId);
        if (nextId == null || oldId == null) {
            log("Giỏ ID sống chưa có ID dự phòng khả dụng cho tài nguyên '" + ActionEngine.extractResourceKey(g.path()) + "'.", g.gid());
            return false;
        }
        return replaceIdAndSend(g, oldId, nextId, true, targets);
    }

    /**
     * Phương pháp 4 (PRODUCER_RECREATE): Gọi API Tạo mới (Producer POST) lên Server để sinh ID mới,
     * trích xuất ID vừa tạo, thay vào API hiện tại rồi Gửi lên Server.
     */
    public void sendWithProducer(ApiGroup g, Integer explicitProducerGid, Collection<RoleResult> targets) {
        if (g == null) return;
        Integer prodGid = explicitProducerGid != null ? explicitProducerGid
                : (g.producerGid() != null ? g.producerGid() : actionEngine.findProducerGid(g.path()));
        ApiGroup producer = prodGid != null ? group(prodGid) : null;
        if (producer == null) {
            // Tìm API POST cùng tài nguyên trong danh sách groups
            String res = ActionEngine.extractResourceKey(g.path());
            for (ApiGroup cand : groups()) {
                if (cand.gid() != g.gid() && "POST".equalsIgnoreCase(cand.method())
                        && res.equalsIgnoreCase(ActionEngine.extractResourceKey(cand.path()))) {
                    producer = cand;
                    break;
                }
            }
        }
        if (producer == null) {
            log("Phương pháp 4: Không tìm thấy API POST Tạo mới (Producer) cho tài nguyên '"
                    + ActionEngine.extractResourceKey(g.path()) + "'. Tiến hành làm mới các trường Unique và gửi.", g.gid());
            sendWithUniqueRefresh(g, targets);
            return;
        }
        final ApiGroup prodGroup = producer;
        g.setProducerGid(prodGroup.gid());
        Collection<RoleResult> rows = (targets == null || targets.isEmpty()) ? defaultTargets(g, true) : targets;
        sendExec.execute(() -> {
            String newId = executeProducerForNewId(prodGroup, g.gid());
            if (newId != null && !newId.isBlank()) {
                List<String> oldIds = actionEngine.detectIds(g.path(), Blob.bytesOf(g.origRequest()));
                String oldId = oldIds.isEmpty() ? null : oldIds.get(0);
                if (oldId != null) {
                    replaceIdAndSend(g, oldId, newId, true, rows);
                    return;
                }
            }
            replayInternal(g, rows);
        });
    }

    /**
     * Phương pháp 4B: Làm mới các trường Unique (email, username, code, phone, nonce...) trong body rồi Gửi.
     */
    public boolean sendWithUniqueRefresh(ApiGroup g, Collection<RoleResult> targets) {
        if (g == null) return false;
        byte[] orig = Blob.bytesOf(g.origRequest());
        if (orig == null) return false;
        byte[] refreshed = actionEngine.refreshUniqueFields(orig);
        Blob reqBlob = storage.put(g.gid(), GroupCodec.ORIG_REQ, refreshed);
        g.setOriginal(reqBlob, g.origResponse());
        g.setActiveIdNote("Đã làm mới Unique fields");
        touch(g);
        log("Phương pháp 4 (Làm mới Unique): Đã sinh giá trị mới cho các trường Unique (email/code/nonce...) của API #" + g.gid() + " và gửi.", g.gid());
        Collection<RoleResult> rows = (targets == null || targets.isEmpty()) ? defaultTargets(g, true) : targets;
        forceRawGids.add(g.gid());
        replayInternal(g, rows);
        return true;
    }

    private String executeProducerForNewId(ApiGroup producer, int consumerGid) {
        byte[] prodReq = Blob.bytesOf(producer.origRequest());
        if (prodReq == null) return null;
        byte[] freshProdReq = actionEngine.refreshUniqueFields(prodReq);
        try {
            Bridge.Sent sent = bridge.send(producer.service(), freshProdReq);
            byte[] resp = sent.response();
            Integer st = HttpBytes.statusCode(resp);
            if (resp != null && st != null && st >= 200 && st < 300) {
                String createdId = actionEngine.extractCreatedId(resp);
                actionEngine.harvest(producer.method(), producer.path(), freshProdReq, resp, st, producer.gid());
                if (createdId != null) {
                    log("Phương pháp 4 (Producer #" + producer.gid() + " " + producer.method() + " " + producer.path()
                            + "): Đã tạo bản ghi mới trên Server -> thu được ID tươi [" + createdId + "] cho API #" + consumerGid, consumerGid);
                    return createdId;
                }
            }
        } catch (RuntimeException e) {
            log("Gọi Producer #" + producer.gid() + " thất bại: " + e, consumerGid);
        }
        return null;
    }

    /** Queue requests on the paced dispatcher; never blocks. */
    public void replay(ApiGroup g, Collection<RoleResult> results) {
        if (g != null && ActionEngine.isActionMethod(g.method()) && !forceRawGids.contains(g.gid())) {
            ActionEngine.ActionMode mode = effectiveActionMode(g);
            if (mode == ActionEngine.ActionMode.LOCK_2XX
                    && config.lock2xxStrategy == ActionEngine.Lock2xxStrategy.INHERIT_200
                    && g.hasFirstOkSnapshot()) {
                inheritInitial2xx(g, results);
                return;
            }
        }
        replayInternal(g, results);
    }

    private void replayInternal(ApiGroup g, Collection<RoleResult> results) {
        List<RoleResult> todo = results.stream().filter(r -> !BUSY.contains(r.state())).toList();
        if (todo.isEmpty()) {
            return;
        }
        activeSends.addAndGet(todo.size());
        for (RoleResult r : todo) {
            r.setState(ResultState.QUEUED);
            r.setError("");
        }
        Audit.recalc(g);
        changes.dirty(g);
        List<SendDispatcher.Job<Work>> jobs = new ArrayList<>();
        for (RoleResult r : todo) {
            jobs.add(new SendDispatcher.Job<>(new Work(g, r), g.service().key()));
        }
        dispatcher.submit(jobs);
    }

    public int activeSends() {
        return activeSends.get();
    }

    public SendDispatcher.Snapshot queue() {
        return dispatcher.snapshot();
    }

    /** Take every not-yet-sent request off the queue; already received results are kept. */
    public int cancelPending(String reason) {
        List<SendDispatcher.Job<Work>> dropped = dispatcher.cancelAll();
        if (dropped.isEmpty()) {
            return 0;
        }
        Set<ApiGroup> gs = new LinkedHashSet<>();
        for (SendDispatcher.Job<Work> j : dropped) {
            RoleResult r = j.payload.result();
            r.setState(r.status() != null ? ResultState.DONE : ResultState.NOT_SENT);
            r.setError("");
            gs.add(j.payload.group());
        }
        activeSends.updateAndGet(n -> Math.max(0, n - dropped.size()));
        for (ApiGroup g : gs) {
            Audit.recalc(g);
            touch(g);
        }
        changes.structure();
        log(reason + ": " + dropped.size() + " request trong hàng đợi không được gửi.", null);
        return dropped.size();
    }

    /** Every request never sent (captured while crawling only). */
    public int sendAllUnsent() {
        int n = 0;
        for (ApiGroup g : groups()) {
            List<RoleResult> t = new ArrayList<>();
            for (RoleResult r : defaultTargets(g, false)) {
                if (ResultState.NOT_SENT.equals(r.state())) {
                    t.add(r);
                }
            }
            if (!t.isEmpty()) {
                replay(g, t);
                n += t.size();
            }
        }
        return n;
    }

    private byte[] prepareActionRequestBeforeSend(ApiGroup g, byte[] request, ActionEngine.ActionMode mode) {
        if (!ActionEngine.isActionMethod(g.method()) || request == null) {
            return request;
        }
        if (mode == ActionEngine.ActionMode.LOCK_2XX && config.lock2xxStrategy == ActionEngine.Lock2xxStrategy.FORCE_RAW_SEND) {
            return request;
        }
        List<String> ids = actionEngine.detectIds(g.path(), request);
        String currentId = ids.isEmpty() ? null : ids.get(0);
        if (mode == ActionEngine.ActionMode.PRODUCER_RECREATE) {
            Integer pGid = g.producerGid() != null ? g.producerGid() : actionEngine.findProducerGid(g.path());
            ApiGroup prod = pGid != null ? group(pGid) : null;
            if (prod != null && currentId != null) {
                String newId = executeProducerForNewId(prod, g.gid());
                if (newId != null) {
                    g.setActiveIdNote("Producer ID: " + currentId + " -> " + newId);
                    return ActionEngine.replaceIdInRequest(request, currentId, newId);
                }
            }
            return actionEngine.refreshUniqueFields(request);
        }
        if ((mode == ActionEngine.ActionMode.LIVE_ID_POOL || mode == ActionEngine.ActionMode.AUTO_SMART
                || (mode == ActionEngine.ActionMode.LOCK_2XX && config.lock2xxStrategy == ActionEngine.Lock2xxStrategy.REAL_SEND_FRESH_ID))
                && currentId != null && actionEngine.isConsumed(currentId)) {
            String liveId = actionEngine.nextLiveId(g.path(), currentId);
            if (liveId != null) {
                g.setActiveIdNote("Tự đổi ID sống: " + currentId + " -> " + liveId);
                return ActionEngine.replaceIdInRequest(request, currentId, liveId);
            }
        }
        return request;
    }

    private byte[] autoHealActionRequestOn4xx(ApiGroup g, byte[] sentRequest, int statusCode, ActionEngine.ActionMode mode) {
        if (!ActionEngine.isActionMethod(g.method()) || sentRequest == null) {
            return null;
        }
        if (statusCode != 400 && statusCode != 404 && statusCode != 409 && statusCode != 410 && statusCode != 422) {
            return null;
        }
        if (mode == ActionEngine.ActionMode.HOLD_AT_PROXY) {
            return null;
        }
        if (mode == ActionEngine.ActionMode.LOCK_2XX && config.lock2xxStrategy == ActionEngine.Lock2xxStrategy.FORCE_RAW_SEND) {
            return null;
        }
        List<String> ids = actionEngine.detectIds(g.path(), sentRequest);
        String deadId = ids.isEmpty() ? null : ids.get(0);
        if (deadId != null) {
            actionEngine.markConsumed(deadId);
        }
        // Bước 1: Thử thay bằng ID sống trong Giỏ ID (Phương pháp 3)
        if (deadId != null && mode != ActionEngine.ActionMode.PRODUCER_RECREATE) {
            String nextId = actionEngine.nextLiveId(g.path(), deadId);
            if (nextId != null) {
                g.setActiveIdNote("Auto-ID (" + statusCode + "): " + deadId + " -> " + nextId);
                log("Action Auto-Heal (Giỏ ID sống): Gặp HTTP " + statusCode + " do ID [" + deadId
                        + "] đã bị xóa/tiêu thụ -> Tự động thay bằng ID sống [" + nextId + "] và gửi lại.", g.gid());
                return ActionEngine.replaceIdInRequest(sentRequest, deadId, nextId);
            }
        }
        // Bước 2: Thử gọi Producer POST để sinh ID mới (Phương pháp 4)
        if (deadId != null && (mode == ActionEngine.ActionMode.AUTO_SMART || mode == ActionEngine.ActionMode.PRODUCER_RECREATE
                || mode == ActionEngine.ActionMode.LOCK_2XX)) {
            Integer pGid = g.producerGid() != null ? g.producerGid() : actionEngine.findProducerGid(g.path());
            ApiGroup prod = pGid != null ? group(pGid) : null;
            if (prod != null) {
                String createdId = executeProducerForNewId(prod, g.gid());
                if (createdId != null) {
                    g.setActiveIdNote("Producer (" + statusCode + "): " + deadId + " -> " + createdId);
                    return ActionEngine.replaceIdInRequest(sentRequest, deadId, createdId);
                }
            }
        }
        // Bước 3: Thử làm mới các trường Unique nếu là POST tạo mới bị trùng (400/409/422)
        if ("POST".equalsIgnoreCase(g.method())) {
            byte[] refreshed = actionEngine.refreshUniqueFields(sentRequest);
            if (refreshed != sentRequest) {
                g.setActiveIdNote("Auto-Unique (" + statusCode + ")");
                log("Action Auto-Heal (Unique Refresh): Gặp HTTP " + statusCode + " -> Tự động làm mới trường Unique và gửi lại.", g.gid());
                return refreshed;
            }
        }
        return null;
    }

    private void runJob(SendDispatcher.Job<Work> job) {
        ApiGroup g = job.payload.group();
        RoleResult r = job.payload.result();
        if (closed) {
            return;
        }
        synchronized (g) {
            r.setState(ResultState.SENDING);
            Audit.evaluate(g, r);
        }
        changes.dirty(g);
        Double retryAfter = null;
        long started = System.currentTimeMillis();
        boolean forceRaw = forceRawGids.remove(g.gid());
        ActionEngine.ActionMode mode = effectiveActionMode(g);
        try {
            byte[] baseReq = requestOf(g, r);
            if (baseReq == null) {
                throw new IllegalStateException("request could not be built");
            }
            byte[] request = forceRaw ? baseReq : prepareActionRequestBeforeSend(g, baseReq, mode);
            Bridge.Sent sent = bridge.send(g.service(), request);
            byte[] resp = sent.response();
            Integer firstSt = HttpBytes.statusCode(resp);
            if (!forceRaw && firstSt != null) {
                byte[] healedReq = autoHealActionRequestOn4xx(g, request, firstSt, mode);
                if (healedReq != null) {
                    Bridge.Sent retrySent = bridge.send(g.service(), healedReq);
                    if (retrySent.response() != null && retrySent.response().length > 0) {
                        request = healedReq;
                        sent = retrySent;
                        resp = retrySent.response();
                    }
                }
            }
            r.setElapsedMs(sent.elapsedMs() > 0 ? sent.elapsedMs() : System.currentTimeMillis() - started);
            r.incSent();
            Blob stored = resp == null || resp.length == 0 ? null : storage.put(g.gid(), GroupCodec.respName(r), resp);
            synchronized (g) {   // a result is written whole before anyone evaluates it (see Audit.recalc)
                if (resp == null || resp.length == 0) {
                    r.setResponse(null);
                    r.setStatus(null);
                    r.setLength(null);
                    r.setState(ResultState.ERROR);
                    r.setError(sent.error() != null ? sent.error() : "No response (timeout / connection failure)");
                } else {
                    Integer st = HttpBytes.statusCode(resp);
                    int len = HttpBytes.bodyLength(resp);
                    r.setResponse(stored);
                    r.setStatus(st);
                    r.setLength(len);
                    r.setState(ResultState.DONE);
                    r.setError("");
                    if (st != null) {
                        actionEngine.harvest(g.method(), g.path(), request, resp, st, g.gid());
                    }
                    if (st != null && st >= 200 && st < 300 && !g.hasFirstOkSnapshot()) {
                        g.saveFirstOkSnapshot(r.customRequest() != null ? r.customRequest() : g.origRequest(),
                                stored, st, len, g.path());
                    }
                    if (st != null && SendDispatcher.retryable(st)) {
                        retryAfter = SendDispatcher.parseRetryAfter(HttpBytes.header(resp, "Retry-After"), System.currentTimeMillis());
                    }
                    if (r.original()) {
                        g.setOriginal(g.origRequest(), stored);
                        g.setOrigMeta(st, len);
                    }
                    if (g.heldAtProxy() && st != null && st >= 200 && st < 300) {
                        g.setHeldAtProxy(false);
                        g.setActiveIdNote("Đã thực thi thật (" + st + " OK)");
                    }
                }
            }
        } catch (RuntimeException e) {
            synchronized (g) {
                r.setElapsedMs(System.currentTimeMillis() - started);
                r.setState(ResultState.ERROR);
                r.setError(String.valueOf(e.getMessage() != null ? e.getMessage() : e));
            }
        }
        int status = ResultState.DONE.equals(r.state()) && r.status() != null ? r.status() : -1;
        if (dispatcher.willRetry(job, status)) {
            synchronized (g) {
                r.setState(ResultState.RETRY);
                r.setError("HTTP " + status + " - server giới hạn tốc độ, sẽ tự gửi lại (lần " + (job.retries() + 1) + "/"
                        + config.maxRetries + ")");
            }
        }
        Audit.recalc(g);   // before done(): a retry of this row can only start after these colours are set
        boolean finished = dispatcher.done(job, status, retryAfter);
        if (finished && activeSends.updateAndGet(n -> Math.max(0, n - 1)) == 0) {
            changes.structure();
        }
        touch(g);
    }

    // =================================================================== Repeater
    /** Push rows to Burp Repeater (off the caller's thread, in order). Returns the group title. */
    public String toRepeater(ApiGroup g, List<RoleResult> results, boolean compact) {
        Config c = config;
        List<RoleResult> rs = List.copyOf(results);
        captureExec.execute(() -> {
            for (RoleResult r : rs) {
                String name = compact ? Text.compactRepeaterTabName(g.gid(), r.original() ? "00_GOC" : r.roleName(),
                        g.branchId(), c.includeBranchInTab) : r.tabName();
                try {
                    bridge.toRepeater(g.service(), requestOf(g, r), name);
                } catch (RuntimeException e) {
                    log("Đẩy Repeater lỗi (" + r.tabName() + "): " + e, g.gid());
                }
            }
            log("Đã đẩy " + rs.size() + " request sang Burp Repeater" + (compact ? " (tên tab gọn)" : "") + ".", g.gid());
        });
        return Text.groupId(g.gid()) + " " + g.method() + " " + Text.shortPath(g.path(), 28);
    }

    // =================================================================== roles per scope
    /** Bring an API's role rows in line with its effective roles; results of kept roles stay. */
    public boolean syncRoles(ApiGroup g) {
        Config c = config;
        List<RoleScopes.Indexed> wanted = RoleScopes.effective(roles.all(), g.branch(), branchRules, g.roleOverrides());
        Set<String> names = new HashSet<>();
        for (RoleScopes.Indexed i : wanted) {
            names.add(i.role().name());
        }
        List<RoleResult> keep = new ArrayList<>();
        Set<String> have = new HashSet<>();
        for (RoleResult r : g.results()) {
            if (names.contains(r.roleKey())) {
                keep.add(r);
                have.add(r.roleKey());
            }
        }
        List<RoleScopes.Indexed> missing = wanted.stream().filter(i -> !have.contains(i.role().name())).toList();
        if (missing.isEmpty() && keep.size() == g.results().size()) {
            return false;
        }
        keep.addAll(makeResults(g, missing, c));
        keep.sort(Comparator.comparingInt(RoleResult::roleIndex));
        g.setResults(keep);
        Audit.recalc(g);
        touch(g);
        changes.structure();
        return true;
    }

    public List<BranchRule> branchRules() {
        return branchRules;
    }

    /**
     * Switch roles on / off: GLOBAL (the roles' own flag), BRANCH (targets = branch paths, also for later
     * captures) or API (targets = APIs). Returns how many captured APIs changed.
     */
    public int applyRoleScope(Collection<String> roleNames, boolean enable, RoleScopes.Scope scope, Collection<?> targets,
                              boolean applyExisting) {
        List<ApiGroup> affected = new ArrayList<>();
        if (scope == RoleScopes.Scope.GLOBAL) {
            roles.setEnabledByName(roleNames, enable);
            saveRoles();
            if (applyExisting) {
                affected.addAll(groups());
            }
        } else if (scope == RoleScopes.Scope.BRANCH) {
            List<BranchRule> rs = branchRules;
            for (Object t : targets) {
                rs = RoleScopes.withRule(rs, String.valueOf(t), roleNames, enable);
                if (applyExisting) {
                    affected.addAll(groupsUnder(String.valueOf(t)));
                }
            }
            branchRules = rs;
            synchronized (this) {
                stateDirty = true;
            }
        } else {
            for (Object t : targets) {
                ApiGroup g = (ApiGroup) t;
                for (String n : roleNames) {
                    g.setRoleOverride(n, enable);
                }
                affected.add(g);
            }
        }
        int changed = 0;
        Set<ApiGroup> done = new HashSet<>();
        for (ApiGroup g : affected) {
            if (done.add(g) && syncRoles(g)) {
                changed++;
            }
        }
        String what = switch (scope) {
            case GLOBAL -> "toàn bộ";
            case BRANCH -> "nhánh: " + String.join(", ", targets.stream().map(String::valueOf).toList());
            case API -> targets.size() + " API";
        };
        log((enable ? "BẬT " : "TẮT ") + roleNames.size() + " role (" + String.join(", ", roleNames) + ") cho " + what + "; "
                + changed + " API đã bắt được cập nhật.", null);
        changes.structure();
        return changed;
    }

    public int removeBranchRule(String prefix, boolean applyExisting) {
        String clean = Hierarchy.normalize(prefix);
        branchRules = branchRules.stream().filter(r -> !r.prefix().equalsIgnoreCase(clean)).toList();
        synchronized (this) {
            stateDirty = true;
        }
        int changed = 0;
        if (applyExisting) {
            for (ApiGroup g : groupsUnder(clean)) {
                changed += syncRoles(g) ? 1 : 0;
            }
        }
        log("Đã xóa quy tắc role của nhánh '" + clean + "'; " + changed + " API cập nhật.", null);
        return changed;
    }

    public void clearOverrides(ApiGroup g) {
        g.clearRoleOverrides();
        syncRoles(g);
    }

    /** Re-clone an API with the current roles (results of the old rows are dropped). */
    public void rebuild(ApiGroup g) {
        captureExec.execute(() -> safely(() -> {
            List<RoleScopes.Indexed> eff = RoleScopes.effective(roles.all(), g.branch(), branchRules, g.roleOverrides());
            g.setResults(makeResults(g, eff, config));
            applyOriginalProfile(g);
            Audit.recalc(g);
            touch(g);
            changes.structure();
            log("Dựng lại với 1 API gốc + " + g.results().size() + " role hiện tại.", g.gid());
        }));
    }

    public void applyOriginalProfileTo(ApiGroup g) {
        applyOriginalProfile(g);
        touch(g);
    }

    // =================================================================== placement
    public PlacementBook book() {
        return book;
    }

    /** Move APIs to a branch; an explicit move confirms them. Returns how many changed branch. */
    public int moveToBranch(Collection<ApiGroup> gs, int branchId, PlacementBook.Action action, boolean confirm, String note,
                            boolean pushHistory) {
        Branch b = branch(branchId);
        if (b == null || gs.isEmpty()) {
            return 0;
        }
        Config c = config;
        int moved = 0;
        for (ApiGroup g : gs) {
            String old = g.branch();
            boolean changed = !old.equals(b.name());
            if (changed) {
                moved++;
                if (pushHistory) {
                    g.pushPlace(old);
                }
            }
            if (confirm && g.pending()) {
                g.setPending(false);
                g.setDupPending(false);
                g.setPlaceHint(null);
                book.setPending(g.bookKey(), false);
            }
            if (changed || confirm) {
                if (g.bookKey() != null && !g.pending()) {
                    book.setPlace(g.bookKey(), b.name());
                }
                book.event(action, g.bookKey(), g.gid(), g.method(), g.path(), old, b.name(),
                        Text.nz(note) + (confirm && !changed ? " (đã xác nhận)" : ""));
            }
            g.setBranch(b.id(), b.name());
            g.origResult().setTabName(Text.repeaterTabName(g.gid(), g.method(), g.path(), "00_Original_Crawl", c.shortPathLen,
                    b.id(), c.includeBranchInTab));
            for (RoleResult r : g.results()) {
                r.setTabName(Text.repeaterTabName(g.gid(), g.method(), g.path(), r.roleName(), c.shortPathLen, b.id(),
                        c.includeBranchInTab));
            }
            touch(g);
        }
        changes.structure();
        return moved;
    }

    public int moveToPath(Collection<ApiGroup> gs, String path, String note) {
        return moveToBranch(gs, findOrCreateBranch(path, false).id(), PlacementBook.Action.MOVE, true, note, true);
    }

    /** Each API goes back to the branch it was in before its last move. */
    public int revert(Collection<ApiGroup> gs) {
        int n = 0;
        for (ApiGroup g : gs) {
            String prev = g.popPlace();
            if (prev == null) {
                continue;
            }
            moveToBranch(List.of(g), findOrCreateBranch(prev, false).id(), PlacementBook.Action.REVERT, !g.pending(),
                    "về vị trí trước", false);
            n++;
        }
        return n;
    }

    /** Undo one line of the placement log; returns a message for the user. */
    public String revertEvent(PlacementBook.Event ev) {
        switch (ev.action()) {
            case EDIT:
                if (ev.key() != null && book.get(ev.key()) != null) {
                    book.setPlace(ev.key(), ev.src());
                    book.event(PlacementBook.Action.REVERT, ev.key(), null, ev.method(), ev.path(), ev.dst(), ev.src(),
                            "hoàn tác sửa giữ chỗ #" + ev.id());
                    changes.structure();
                    return "Đã trả vị trí giữ chỗ về: " + (ev.src() == null ? "(trống)" : ev.src());
                }
                return "Endpoint của dòng này không còn trong bộ nhớ giữ chỗ.";
            case MANUAL, FORGET, RENAME, DELETE, RECAPTURE:
                return "Thao tác '" + ev.action().label + "' không hoàn tác tự động được.";
            default:
                break;
        }
        ApiGroup g = ev.gid() == null ? null : group(ev.gid());
        if (g == null || (ev.key() != null && !ev.key().equals(g.bookKey()))) {
            return "API của dòng này không còn (đã xóa) - hãy sửa vị trí giữ chỗ ở bảng Giữ chỗ.";
        }
        if (ev.src() == null) {
            return "Dòng này không có vị trí trước để quay về.";
        }
        moveToBranch(List.of(g), findOrCreateBranch(ev.src(), false).id(), PlacementBook.Action.REVERT, !g.pending(),
                "hoàn tác dòng nhật ký #" + ev.id(), true);
        return Text.apiLabel(g.gid()) + " đã về: " + ev.src();
    }

    /** Confirm the place of waiting APIs (a waiting duplicate is kept at its new place). */
    public int confirm(Collection<ApiGroup> gs, String note) {
        int n = keepDuplicates(gs.stream().filter(ApiGroup::dupPending).toList());
        for (ApiGroup g : gs) {
            if (!g.pending()) {
                continue;
            }
            g.setPending(false);
            g.setPlaceHint(null);
            if (g.bookKey() != null) {
                book.setPlace(g.bookKey(), g.branch());
                book.setPending(g.bookKey(), false);
            }
            book.event(PlacementBook.Action.CONFIRM, g.bookKey(), g.gid(), g.method(), g.path(), null, g.branch(), note);
            touch(g);
            n++;
        }
        if (n > 0) {
            log("Đã xác nhận vị trí của " + n + " API.", null);
            changes.structure();
        }
        return n;
    }

    public int keepDuplicates(Collection<ApiGroup> gs) {
        int n = 0;
        for (ApiGroup g : gs) {
            if (g.dupPending()) {
                g.setDupPending(false);
                g.setPending(false);
                book.event(PlacementBook.Action.DUP_KEEP, g.bookKey(), g.gid(), g.method(), g.path(), null, g.branch(),
                        "bản trùng #" + g.dupNo());
                touch(g);
                n++;
            }
        }
        if (n > 0) {
            changes.structure();
        }
        return n;
    }

    /** 'Không giữ': leave the crawl screens, stay in the audit (can be shown again). */
    public int hide(Collection<ApiGroup> gs, String note) {
        int n = 0;
        synchronized (this) {
            for (ApiGroup g : gs) {
                if (groups.remove(g.gid()) != null) {
                    g.setHidden(true);
                    g.setDupPending(false);
                    g.setPending(false);
                    hidden.put(g.gid(), g);
                    dirtyGroups.add(g);
                    book.event(PlacementBook.Action.HIDE, g.bookKey(), g.gid(), g.method(), g.path(), g.branch(), null,
                            Text.blank(note) ? "vẫn lưu trong Audit, bấm Hiện để đưa lại" : note);
                    n++;
                }
            }
            refreshSnapshot();
        }
        changes.structure();
        return n;
    }

    public int show(Collection<ApiGroup> gs) {
        int n = 0;
        synchronized (this) {
            for (ApiGroup g : gs) {
                if (hidden.remove(g.gid()) != null) {
                    g.setHidden(false);
                    groups.put(g.gid(), g);
                    dirtyGroups.add(g);
                    book.event(PlacementBook.Action.SHOW, g.bookKey(), g.gid(), g.method(), g.path(), null, g.branch(), "");
                    n++;
                }
            }
            List<ApiGroup> sorted = new ArrayList<>(groups.values());
            sorted.sort(Comparator.comparingInt(ApiGroup::gid));
            groups.clear();
            for (ApiGroup g : sorted) {
                groups.put(g.gid(), g);
            }
            refreshSnapshot();
        }
        changes.structure();
        return n;
    }

    /**
     * Delete APIs. They keep their place ('giữ chỗ': the confirmed one, else where they were) and are
     * captured again next time; copies left of the same endpoint keep its dedup memory.
     */
    public void remove(Collection<ApiGroup> gs, String note) {
        List<ApiGroup> list = new ArrayList<>(gs);
        synchronized (this) {
            for (ApiGroup g : list) {
                groups.remove(g.gid());
                hidden.remove(g.gid());
                dirtyGroups.remove(g);
                deletedGids.add(g.gid());
            }
            refreshSnapshot();
        }
        for (ApiGroup g : list) {
            dropCopy(g);
        }
        forgetDedup(list);
        for (ApiGroup g : list) {
            logbook.forget(g.gid());
            if (g.bookKey() == null) {
                continue;
            }
            List<ApiGroup> left = copiesOf(g.bookKey());
            if (book.deleted(g.bookKey(), g.branch(), left.isEmpty() ? null : left.get(0).gid()) != null) {
                PlacementBook.Record rec = book.get(g.bookKey());
                book.event(PlacementBook.Action.DELETE, g.bookKey(), g.gid(), g.method(), g.path(), g.branch(), null,
                        Text.blank(note) ? "giữ chỗ: " + rec.place : note);
            }
        }
        synchronized (this) {
            stateDirty = true;
        }
        changes.structure();
    }

    /** Deleted APIs are captured again the next time (their dedup keys are dropped). */
    public int forgetDedup(Collection<ApiGroup> gs) {
        int n = 0;
        synchronized (this) {
            for (ApiGroup g : gs) {
                String key = g.dedupKey();
                if (key == null || !Integer.valueOf(g.gid()).equals(seenGid.get(key))) {
                    continue;
                }
                List<ApiGroup> left = copies.getOrDefault(g.bookKey(), List.of()).stream().filter(c -> c != g).toList();
                if (!left.isEmpty()) {
                    seenGid.put(key, left.get(0).gid());
                    continue;
                }
                seenKeys.remove(key);
                seenGid.remove(key);
                n++;
            }
            stateDirty = true;
        }
        if (n > 0) {
            log("Đã xóa " + n + " API: lần sau đi qua chúng sẽ được bắt lại.", null);
        }
        return n;
    }

    public boolean editPlace(String key, String place) {
        PlacementBook.Record r = book.get(key);
        if (r == null) {
            return false;
        }
        String old = r.place;
        String clean = Text.blank(place) ? null : Hierarchy.normalize(place);
        book.setPlace(key, clean);
        book.event(PlacementBook.Action.EDIT, key, r.gid, r.method, r.path, old, clean, "");
        changes.structure();
        return true;
    }

    public boolean forgetPlace(String key) {
        PlacementBook.Record r = book.forget(key);
        if (r != null) {
            book.event(PlacementBook.Action.FORGET, key, r.gid, r.method, r.path, r.place, null, "");
            changes.structure();
        }
        return r != null;
    }

    public String addManualPlace(String method, String host, String path, String place, boolean secure) {
        String m = Text.blank(method) ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        Service s = new Service(host, secure ? 443 : 80, secure);
        String key = placeKey(m, s, path);
        book.ensure(key, m, host, path);
        book.setPlace(key, Hierarchy.normalize(place));
        book.event(PlacementBook.Action.MANUAL, key, null, m, path, null, Hierarchy.normalize(place), "");
        changes.structure();
        return key;
    }

    /** Drop the dedup memory of one endpoint so its next request is captured again. */
    public int allowRecapture(String key) {
        int n = 0;
        synchronized (this) {
            for (String k : new ArrayList<>(seenKeys)) {
                if (k.equals(key) || k.endsWith("|" + key)) {
                    seenKeys.remove(k);
                    seenGid.remove(k);
                    n++;
                }
            }
            stateDirty = true;
        }
        PlacementBook.Record r = book.get(key);
        book.event(PlacementBook.Action.RECAPTURE, key, r != null ? r.gid : null, r != null ? r.method : "",
                r != null ? r.path : "", null, null, n + " khóa chống trùng đã xóa");
        changes.structure();
        return n;
    }

    public synchronized int resetDedup() {
        int n = seenKeys.size();
        seenKeys.clear();
        seenGid.clear();
        skipped = 0;
        lastSkip = "";
        stateDirty = true;
        log("Dedup cache reset (" + n + " endpoint key(s) forgotten).", null);
        changes.status();
        return n;
    }

    /** Delete every API and branch; remembered places ('giữ chỗ') stay. */
    public void clearAll() {
        cancelPending("Xóa toàn bộ");
        remove(allGroups(), "Xóa toàn bộ");
        synchronized (this) {
            seenKeys.clear();
            seenGid.clear();
            skipped = 0;
            lastSkip = "";
            nextGid = 1;
            nextBranchId = 2;
            activeBranchId = 1;
            branches.clear();
            branches.add(new Branch(1, Hierarchy.DEFAULT_PATH, null));
            stateDirty = true;
        }
        logbook.clearAll();
        changes.structure();
        log("All captured APIs, hierarchy history, and dedup cache reset.", null);
    }

    // =================================================================== Excel list
    public TargetList targets() {
        return targets;
    }

    public HitLog hits() {
        return hits;
    }

    public void setTargets(TargetList t) {
        targets = t;
        String sig = t == null ? null : t.signature();
        if (!java.util.Objects.equals(sig, hits.signature())) {
            hits.reset(sig);
        }
        synchronized (this) {
            stateDirty = true;
        }
        retag();
    }

    /** Match every captured API against the current list (after load / option change). */
    public void retag() {
        for (ApiGroup g : allGroups()) {
            TargetEntry e = targets == null ? null : targetMatch(g.method(), g.path(), g.host());
            if (e != g.target()) {
                g.setTarget(e);
                touch(g);
            }
        }
        changes.structure();
    }

    public void resetHits() {
        hits.reset(targets == null ? null : targets.signature());
        synchronized (this) {
            stateDirty = true;
        }
        changes.structure();
    }

    // =================================================================== copy STT + path
    /**
     * 'STT. path' of an API: STT from its Excel row, else the chosen fallback (counter assigned on the
     * first copy and kept, or the API number); path = its branch.
     */
    public String copyLabel(ApiGroup g, boolean assign) {
        Config c = config;
        Object stt = g.target() != null ? g.target().stt() : null;
        if (Text.blank(stt)) {
            if (c.copySttFallback == Config.CopyFallback.API) {
                stt = g.gid();
            } else if (c.copySttFallback == Config.CopyFallback.COUNTER) {
                if (g.copyStt() == null && assign) {
                    synchronized (this) {
                        Config next = config.copy();
                        g.setCopyStt(next.copyCounter);
                        next.copyCounter++;
                        config = next;
                        prefs.set(PREF_CONFIG, Json.write(next.toMap()));
                    }
                    touch(g);
                }
                stt = g.copyStt();
            }
        }
        return CopyFormat.line(c.copyFormat, stt, g.branch(), g.method(), g.url(), Text.apiLabel(g.gid()), g.endpoint());
    }

    /** Lines for the clipboard (one per API, in API order); the APIs are marked as copied. */
    public String copy(Collection<ApiGroup> gs) {
        List<ApiGroup> sorted = new ArrayList<>(gs);
        sorted.sort(Comparator.comparingInt(ApiGroup::gid));
        List<String> lines = new ArrayList<>();
        for (ApiGroup g : sorted) {
            String line = copyLabel(g, true);
            g.setCopiedLabel(line);
            lines.add(line);
            touch(g);
            log("Đã copy: " + line, g.gid());
        }
        changes.structure();
        return String.join("\n", lines);
    }

    public void resetCopyCounter() {
        for (ApiGroup g : allGroups()) {
            if (g.copyStt() != null) {
                g.setCopyStt(null);
                touch(g);
            }
        }
        Config c = config.copy();
        c.copyCounter = 1;
        applyConfig(c);
    }

    // =================================================================== status
    /** Counters for the status line. */
    public record Stats(int apis, int branches, int pending, int dupWaiting, int hidden, int info, int queued) {
    }

    public Stats stats() {
        List<ApiGroup> gs = groups();
        int pending = 0;
        int dups = 0;
        int info = 0;
        for (ApiGroup g : gs) {
            if (g.pending()) {
                pending++;
            }
            if (g.dupPending()) {
                dups++;
            }
            if (Audit.level(g) == AuditLevel.INFO) {
                info++;
            }
        }
        int nb;
        int nh;
        synchronized (this) {
            nb = branches.size();
            nh = hidden.size();
        }
        return new Stats(gs.size(), nb, pending, dups, nh, info, dispatcher.snapshot().queued());
    }

    // =================================================================== persistence
    public synchronized String workspaceName() {
        return storage.name();
    }

    public synchronized String workspaceLocation() {
        return storage.location();
    }

    /** Save what changed (API documents, workspace state). Runs every 2 s and on close. */
    public void flush() {
        List<ApiGroup> dirty;
        List<Integer> deleted;
        String state = null;
        Storage st;
        synchronized (this) {
            st = storage;
            dirty = new ArrayList<>(dirtyGroups);
            dirtyGroups.clear();
            deleted = new ArrayList<>(deletedGids);
            deletedGids.clear();
            if (stateDirty || book.dirty() || hits.dirty()) {
                state = stateJson();
                stateDirty = false;
            }
        }
        for (int gid : deleted) {
            st.deleteGroup(gid);
        }
        if (!dirty.isEmpty()) {
            st.saveGroups(dirty, GroupCodec::toJson);
        }
        if (state != null) {
            st.saveState(state);
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            bridge.err(Text.EXT_NAME + ": không lưu được workspace: " + e);
        }
    }

    private String stateJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 2);
        List<Object> bs = new ArrayList<>();
        for (Branch b : branches) {
            bs.add(Map.of("id", b.id(), "name", b.name(), "created", b.created()));
        }
        m.put("branches", bs);
        m.put("active_branch", activeBranchId);
        m.put("next_branch", nextBranchId);
        m.put("next_gid", nextGid);
        m.put("recent_dest", new ArrayList<>(recentDest));
        m.put("seen", new ArrayList<>(seenKeys));
        Map<String, Object> sg = new LinkedHashMap<>(seenGid);
        m.put("seen_gid", sg);
        List<Object> rules = new ArrayList<>();
        for (BranchRule r : branchRules) {
            rules.add(Map.of("prefix", r.prefix(), "roles", r.roles()));
        }
        m.put("branch_rules", rules);
        m.put("skipped", skipped);
        m.put("targets", targets == null ? null : targets.toJson());
        m.put("hits", hits.toJson());
        m.put("places", book.toJson());
        return Json.write(m);
    }

    /** Close the current workspace (saving it) and open another one. */
    @SuppressWarnings("unchecked")
    public void open(Storage next) {
        cancelPending("Đổi workspace");
        flushQuietly();
        Map<String, Object> m;
        try {
            String raw = next.loadState();
            m = raw == null || raw.isBlank() ? Map.of() : Json.parseObject(raw);
        } catch (RuntimeException e) {
            m = Map.of();
            bridge.err(Text.EXT_NAME + ": state.json bị lỗi, bắt đầu workspace trống: " + e);
        }
        TargetList t = null;
        String tj = Json.str(m, "targets", null);
        if (tj != null) {
            try {
                t = TargetList.fromJson(tj);
            } catch (RuntimeException ignored) {
                // list dropped, APIs kept
            }
        }
        Map<Integer, TargetEntry> byRow = new HashMap<>();
        if (t != null) {
            for (TargetEntry e : t.entries()) {
                byRow.put(e.rowNo(), e);
            }
        }
        List<ApiGroup> loaded = new ArrayList<>();
        for (String doc : next.loadGroupDocs()) {
            try {
                loaded.add(GroupCodec.fromJson(doc, next, byRow::get));
            } catch (RuntimeException e) {
                bridge.err(Text.EXT_NAME + ": bỏ qua 1 API hỏng trong workspace: " + e);
            }
        }
        loaded.sort(Comparator.comparingInt(ApiGroup::gid));
        Storage old;
        synchronized (this) {
            old = storage;
            storage = next;
            groups.clear();
            hidden.clear();
            copies.clear();
            branches.clear();
            seenKeys.clear();
            seenGid.clear();
            recentDest.clear();
            dirtyGroups.clear();
            deletedGids.clear();
            for (Object o : Json.list(m, "branches")) {
                Map<String, Object> bm = (Map<String, Object>) o;
                branches.add(new Branch((int) Json.num(bm, "id", 1), Json.str(bm, "name", Hierarchy.DEFAULT_PATH),
                        Json.str(bm, "created", null)));
            }
            if (branches.isEmpty()) {
                branches.add(new Branch(1, Hierarchy.DEFAULT_PATH, null));
            }
            activeBranchId = (int) Json.num(m, "active_branch", branches.get(0).id());
            nextBranchId = (int) Json.num(m, "next_branch", branches.stream().mapToInt(Branch::id).max().orElse(1) + 1);
            int maxGid = loaded.stream().mapToInt(ApiGroup::gid).max().orElse(0);
            nextGid = Math.max((int) Json.num(m, "next_gid", 1), maxGid + 1);
            for (Object o : Json.list(m, "recent_dest")) {
                recentDest.add(String.valueOf(o));
            }
            for (Object o : Json.list(m, "seen")) {
                seenKeys.add(String.valueOf(o));
            }
            Json.obj(m, "seen_gid").forEach((k, v) -> {
                if (v instanceof Number n) {
                    seenGid.put(k, n.intValue());
                }
            });
            skipped = (int) Json.num(m, "skipped", 0);
            for (ApiGroup g : loaded) {
                if (g.hidden()) {
                    hidden.put(g.gid(), g);
                } else {
                    groups.put(g.gid(), g);
                }
                if (g.bookKey() != null) {
                    copies.computeIfAbsent(g.bookKey(), k -> new ArrayList<>()).add(g);
                }
            }
            copies.keySet().forEach(this::refreshDupTotals);
            refreshSnapshot();
            stateDirty = false;
        }
        List<BranchRule> rules = new ArrayList<>();
        for (Object o : Json.list(m, "branch_rules")) {
            Map<String, Object> rm = (Map<String, Object>) o;
            Map<String, Boolean> rr = new LinkedHashMap<>();
            Json.obj(rm, "roles").forEach((k, v) -> rr.put(k, Boolean.TRUE.equals(v)));
            rules.add(new BranchRule(Json.str(rm, "prefix", ""), rr));
        }
        branchRules = rules;
        targets = t;
        hits.load(Json.str(m, "hits", null), t == null ? null : t.signature());
        List<Integer> live = loaded.stream().map(ApiGroup::gid).toList();
        book.load(Json.str(m, "places", null), live);
        for (ApiGroup g : loaded) {
            Audit.recalc(g);
        }
        if (old != next) {
            old.close();
        }
        changes.structure();
        log("Đang dùng workspace " + next.name() + " (" + loaded.size() + " API) - " + next.location(), null);
    }

    public void close() {
        closed = true;
        dispatcher.cancelAll();
        flushQuietly();
        captureExec.shutdownNow();
        sendExec.shutdownNow();
        scheduler.shutdownNow();
        synchronized (this) {
            storage.close();
        }
    }
}
