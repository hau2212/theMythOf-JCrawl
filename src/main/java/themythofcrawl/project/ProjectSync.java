package themythofcrawl.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.QaItem;

/**
 * V_11.4 "Làm chung": every few seconds (default 12) this person's JCrawl
 * <ol>
 * <li>reads its own account workspaces, sends what changed (cells, rows, Q&A + screenshots, hand marks, presence,
 * project settings for PIC / PIC phụ) to the team Google Sheet,</li>
 * <li>pulls what everyone else sent / typed in the sheet, applies client answers to its own Q&A,</li>
 * <li>redraws the human sheets when it is the elected renderer (PIC first),</li>
 * <li>rewrites the person's own Excel copy (BangLamViec.xlsx in their OneDrive) and takes hourly / daily snapshots.</li>
 * </ol>
 * Without a sheet it still keeps the Excel copy and snapshots. Never sends tokens / cookies: only statuses, notes, Q&A.
 */
public final class ProjectSync implements AutoCloseable {

    public enum State {
        OFF("Làm chung: tắt"), OK("Đồng bộ: ổn"), WAIT("Đồng bộ: đang chờ"), ERROR("Đồng bộ: lỗi");

        public final String label;

        State(String label) {
            this.label = label;
        }
    }

    public record Status(State state, String text, long lastOkMs, int pending, int conflicts, boolean renderer, String mirrorNote) {
    }

    /** What the sync needs from the app. */
    public interface Host {
        Path workspaceDir();

        void flush();

        /** A client / sheet answer for one of this person's Q&A items (open workspace); true when applied. */
        boolean applyAnswer(String qaId, QaItem.Msg msg);

        /** V_11.5: a client screenshot for one of this person's Q&A items; true when attached. */
        default boolean applyImage(String qaId, byte[] image) {
            return false;
        }

        /** Row + letter being tested right now (presence), or null. */
        default String[] doing() {
            return null;
        }

        default void changed(ProjectData d, Status s) {
        }
    }

    private final ProjectStore store;
    private final Host host;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService timer;
    private ScheduledFuture<?> task;
    private volatile ProjectData data;
    private volatile Status status = new Status(State.OFF, State.OFF.label, 0, 0, 0, false, "");
    private int skip;
    private int failures;

    // persisted sync state
    private long since;
    private final Map<String, String> pushedHash = new LinkedHashMap<>();
    private final Map<String, String> images = new LinkedHashMap<>();
    private final Map<String, Long> appliedAnswers = new LinkedHashMap<>();
    /** V_11.5 details: "letter/gid" -> "signature|fileId". */
    private final Map<String, String> details = new LinkedHashMap<>();
    public static final int DETAIL_LIMIT = 256 * 1024;
    private long projectPushedAt;
    private long renderedVersion;
    private long lastRenderMs;
    private long lastPresenceMs;
    private String mirrorSig = "";
    private long lastMirrorMs;
    private boolean helloDone;

    public ProjectSync(ProjectStore store, Host host) {
        this.store = store;
        this.host = host;
        loadState();
    }

    public ProjectStore store() {
        return store;
    }

    public ProjectData data() {
        return data;
    }

    public Status status() {
        return status;
    }

    public void addListener(Runnable r) {
        listeners.addIfAbsent(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void fire() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a listener never stops the sync
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle
    public synchronized void start() {
        stop();
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jcrawl-project-sync");
            t.setDaemon(true);
            return t;
        });
        int sec = hubOn() ? Math.max(8, store.config().syncSeconds) : 30;
        task = timer.scheduleWithFixedDelay(this::safeCycle, 2, sec, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    @Override
    public void close() {
        stop();
        saveState();
    }

    /** Runs one cycle soon on the sync thread (after a mark, a new Q&A...). */
    public void syncSoon() {
        ScheduledExecutorService t = timer;
        if (t != null && !t.isShutdown()) {
            t.execute(this::safeCycle);
        } else {
            Thread th = new Thread(this::safeCycle, "jcrawl-project-sync-once");
            th.setDaemon(true);
            th.start();
        }
    }

    private void safeCycle() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            cycle();
        } catch (RuntimeException e) {
            setStatus(State.ERROR, "Lỗi: " + e.getMessage(), 0);
        } finally {
            running.set(false);
        }
    }

    public boolean hubOn() {
        ProjectConfig c = store.config();
        return c.syncOn && !Text.blank(c.sheetUrl);
    }

    private SheetHub hub() {
        ProjectConfig c = store.config();
        return new SheetHub(c.sheetUrl, c.sheetPass);
    }

    // ------------------------------------------------------------------ one cycle
    /** One full cycle (also called directly by tests). */
    public synchronized void cycle() {
        String me = me();
        host.flush();
        Path ws = host.workspaceDir();
        List<ProjectData.LocalAccount> locals = ws == null ? List.of() : ProjectData.loadLocal(ws);
        ProjectConfig cfg = store.config();
        if (cfg.me.isEmpty()) {
            cfg.me = me;
        }
        ProjectData d = ProjectData.build(cfg, locals, store.records(), me);
        int conflicts = 0;
        if (hubOn()) {
            if (skip > 0) {
                skip--;
            } else {
                try {
                    conflicts = exchange(d, me);
                    d = ProjectData.build(cfg, locals, store.records(), me);
                    renderIfElected(d, me);
                    failures = 0;
                    setStatus(State.OK, "Đã đồng bộ lúc " + ProjectStore.time(System.currentTimeMillis()), conflicts);
                } catch (SheetHub.HubException e) {
                    failures++;
                    skip = e.retry ? Math.min(6, failures) : 0;
                    setStatus(e.retry ? State.WAIT : State.ERROR, e.getMessage() + (e.retry ? " · sẽ thử lại" : ""), conflicts);
                } catch (IOException e) {
                    failures++;
                    skip = Math.min(6, failures);
                    setStatus(State.WAIT, "Mất kết nối: " + e.getMessage() + " · sẽ thử lại", conflicts);
                }
            }
        } else {
            setStatus(State.OFF, "Chưa bật làm chung (dữ liệu vẫn lưu trên máy và trong OneDrive)", 0);
        }
        data = d;
        mirror(d);
        try {
            store.snapshot(false);
        } catch (RuntimeException ignored) {
            // backups never stop work
        }
        store.saveRecords();
        saveState();
        host.changed(d, status);
        fire();
    }

    private String me() {
        String m = store.config().me;
        return Text.blank(m) ? System.getProperty("user.name", "Tôi") : m;
    }

    private void setStatus(State st, String text, int conflicts) {
        Status old = status;
        long ok = st == State.OK ? System.currentTimeMillis() : old.lastOkMs();
        status = new Status(st, text, ok, store.pending().size(), conflicts > 0 ? conflicts : (data == null ? 0 : data.conflicts().size()),
                old.renderer(), old.mirrorNote());
    }

    // ------------------------------------------------------------------ push / pull
    private int exchange(ProjectData d, String me) throws IOException {
        ProjectConfig cfg = store.config();
        SheetHub hub = hub();
        if (!helloDone) {
            SheetHub.Hello h = hub.hello(cfg.id, cfg.name);
            if (!h.project().isEmpty() && !h.project().equals(cfg.id)) {
                throw new SheetHub.HubException("Google Sheet này thuộc dự án khác (" + h.name() + ")", false);
            }
            helloDone = true;
        }
        uploadImages(d, hub);
        if (cfg.shareDetails) {
            uploadDetails(d, hub);
        }
        uploadClientImages(hub);
        List<SyncRecord> out = new ArrayList<>();
        Map<String, SyncRecord> mine = localRecords(d, me);
        for (SyncRecord r : mine.values()) {
            String h = hash(r);
            if (!h.equals(pushedHash.get(r.id()))) {
                out.add(r);
            }
        }
        // things this person sent before and no longer has (API deleted, Q&A removed)
        for (String id : new ArrayList<>(pushedHash.keySet())) {
            String type = SyncRecord.typeOf(id);
            if (!mine.containsKey(id) && (SyncRecord.CELL.equals(type) || SyncRecord.QA.equals(type) || SyncRecord.ROW.equals(type)
                    || SyncRecord.API.equals(type))) {
                SyncRecord cur = store.record(id);
                if (cur != null && !cur.deleted()) {
                    out.add(new SyncRecord(id, type, me, cur.account(), Map.of(), System.currentTimeMillis(), me, true, 0L));
                } else {
                    pushedHash.remove(id);
                }
            }
        }
        out.addAll(store.pending());
        int conflicts = 0;
        if (!out.isEmpty()) {
            SheetHub.Pushed p = hub.push(out);
            conflicts = p.conflicts();
            store.pushed(p.stamped());
            for (SyncRecord r : p.stamped()) {
                pushedHash.put(r.id(), hash(r));
                if (SyncRecord.PROJECT.equals(r.type())) {
                    projectPushedAt = cfg.updatedAt;
                }
            }
        }
        SheetHub.Pulled pulled = hub.pull(Math.max(0, since - 5000));
        List<SyncRecord> changed = store.merge(pulled.records());
        if (pulled.serverTime() > 0) {
            since = pulled.serverTime();
        }
        Set<String> localQa = new LinkedHashSet<>();
        for (ProjectData.QaRef q : d.allQa()) {
            if (q.local()) {
                localQa.add(q.item().id());
            }
        }
        apply(changed, me, localQa);
        return conflicts;
    }

    static String hash(SyncRecord r) {
        return r.owner() + "|" + r.account() + "|" + r.deleted() + "|" + Json.write(new TreeMap<>(r.data()));
    }

    /** The records describing this person's own data right now. */
    Map<String, SyncRecord> localRecords(ProjectData d, String me) {
        ProjectConfig cfg = store.config();
        Map<String, SyncRecord> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        Set<String> rowIds = new LinkedHashSet<>();
        for (ProjectData.LocalCell c : d.localCells()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("row", c.rowId());
            m.put("status", c.status().name());
            m.put("http", c.http() == null ? 0 : c.http());
            m.put("apis", c.apis());
            m.put("method", c.method());
            m.put("path", c.path());
            m.put("host", c.host());
            m.put("branch", c.branch());
            ProjectConfig.Account a = cfg.account(c.letter());
            m.put("account_name", a == null ? "" : a.name());
            if (!c.tags().isEmpty()) {
                m.put("tags", new ArrayList<>(c.tags()));
            }
            if (!c.bugs().isEmpty()) {
                m.put("bugs", new ArrayList<>(c.bugs()));
            }
            if (!c.roles().isEmpty()) {
                m.put("roles", new LinkedHashMap<>(c.roles()));
            }
            String id = SyncRecord.CELL + ":" + c.rowId() + ":" + c.letter() + ":" + me;
            out.put(id, new SyncRecord(id, SyncRecord.CELL, me, c.letter(), m, now, me, false, 0L));
            if (c.rowId().startsWith("K:")) {
                rowIds.add(c.rowId());
            }
        }
        for (String rid : rowIds) {
            ProjectData.Row r = d.row(rid);
            if (r == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("row", rid);
            m.put("method", r.method);
            m.put("path", r.path);
            m.put("host", r.host);
            m.put("branch", r.branch);
            String id = SyncRecord.ROW + ":" + rid;
            out.put(id, new SyncRecord(id, SyncRecord.ROW, me, "", m, now, me, false, 0L));
        }
        for (ProjectData.Row r : d.rows()) {
            for (ProjectData.QaRef q : r.qa) {
                if (!q.local()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("row", q.rowId());
                m.put("gid", q.gid());
                m.put("item", q.item().toMap());
                Map<String, Object> im = new LinkedHashMap<>();
                for (QaItem.QaImage i : q.item().imgs()) {
                    String fid = images.get(q.item().id() + "/" + i.name());
                    if (fid != null) {
                        im.put(i.name(), fid);
                    }
                }
                m.put("images", im);
                String id = SyncRecord.QA + ":" + q.item().id();
                out.put(id, new SyncRecord(id, SyncRecord.QA, me, q.letter(), m, now, me, false, 0L));
            }
        }
        if (cfg.shareDetails) {
            for (ProjectData.LocalAccount l : d.locals()) {
                for (ProjectData.ApiFact a : l.apis()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String rid = a.targetRow() != null && d.row(ProjectData.stdRowId(a.targetRow())) != null
                            ? ProjectData.stdRowId(a.targetRow()) : ProjectData.keyRowId(a.key());
                    m.put("row", rid);
                    m.put("gid", a.gid());
                    m.put("method", a.method());
                    m.put("path", a.path());
                    m.put("host", a.host());
                    m.put("branch", a.branch());
                    m.put("http", a.http() == null ? 0 : a.http());
                    m.put("created", a.createdMs());
                    m.put("hidden", a.hidden());
                    m.put("tags", new ArrayList<>(a.tags()));
                    m.put("bugs", new ArrayList<>(a.bugs()));
                    m.put("roles", new ArrayList<Object>(a.roles()));
                    String det = details.get(l.letter() + "/" + a.gid());
                    m.put("file", det == null || det.indexOf('|') < 0 ? "" : det.substring(det.lastIndexOf('|') + 1));
                    String id = SyncRecord.API + ":" + l.letter() + ":" + me + ":" + a.gid();
                    out.put(id, new SyncRecord(id, SyncRecord.API, me, l.letter(), m, now, me, false, 0L));
                }
            }
        }
        String[] doing = host.doing();
        if (doing != null && doing.length == 2 && now - lastPresenceMs > 45_000) {
            String id = SyncRecord.PRESENCE + ":" + me;
            out.put(id, new SyncRecord(id, SyncRecord.PRESENCE, me, doing[1], Map.of("row", doing[0], "beat", now / 45_000), now, me,
                    false, 0L));
            lastPresenceMs = now;
        }
        if (cfg.canManage(me) && cfg.updatedAt > projectPushedAt) {
            String id = SyncRecord.PROJECT;
            Map<String, Object> m = new LinkedHashMap<>(cfg.toMap(true));
            out.put(id, new SyncRecord(id, SyncRecord.PROJECT, me, "", m, now, me, false, 0L));
        }
        return out;
    }

    private void uploadImages(ProjectData d, SheetHub hub) throws IOException {
        int budget = 6;
        for (ProjectData.Row r : d.rows()) {
            for (ProjectData.QaRef q : r.qa) {
                if (!q.local() || q.images() == null) {
                    continue;
                }
                for (QaItem.QaImage i : q.item().imgs()) {
                    String key = q.item().id() + "/" + i.name();
                    if (images.containsKey(key) || budget <= 0) {
                        continue;
                    }
                    byte[] b = q.images().image(i.name());
                    if (b == null) {
                        continue;
                    }
                    SheetHub.Uploaded u = hub.uploadImage(q.item().id(), i.name(), b);
                    images.put(key, u.id());
                    budget--;
                }
            }
        }
    }

    /** Request / response of each own API (masked), uploaded once per change (V_11.5, only when the PIC turned it on). */
    private void uploadDetails(ProjectData d, SheetHub hub) throws IOException {
        int budget = 20;
        for (ProjectData.LocalAccount l : d.locals()) {
            for (ProjectData.ApiFact a : l.apis()) {
                if (budget <= 0) {
                    return;
                }
                String key = l.letter() + "/" + a.gid();
                String sig = signature(l, a.gid());
                String cur = details.get(key);
                if (cur != null && cur.startsWith(sig + "|")) {
                    continue;
                }
                byte[] json = detailJson(l, a).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                SheetHub.Uploaded u = hub.uploadFile("api-" + l.letter() + "-" + a.gid(), "chi-tiet.json", "application/json", json);
                details.put(key, sig + "|" + u.id());
                budget--;
            }
        }
    }

    private static String signature(ProjectData.LocalAccount l, int gid) {
        StringBuilder sb = new StringBuilder();
        for (String f : List.of("meta.json", "orig.req", "orig.resp")) {
            Path p = l.dir().resolve("apis").resolve(String.valueOf(gid)).resolve(f);
            try {
                sb.append(Files.isRegularFile(p) ? Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis() : "-").append(';');
            } catch (IOException e) {
                sb.append("?;");
            }
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    /** Masked request / response (tokens, cookies, passwords, JWT), each cut at 256 KB. */
    static String detailJson(ProjectData.LocalAccount l, ProjectData.ApiFact a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("letter", l.letter());
        m.put("gid", a.gid());
        boolean cut = false;
        String[] parts = new String[2];
        String[] names = {"orig.req", "orig.resp"};
        for (int i = 0; i < 2; i++) {
            byte[] b = l.image(a.gid(), names[i]);
            String t = b == null ? "" : new String(b, java.nio.charset.StandardCharsets.UTF_8);
            if (t.length() > DETAIL_LIMIT) {
                t = t.substring(0, DETAIL_LIMIT);
                cut = true;
            }
            parts[i] = themythofcrawl.core.ActivityLog.redact(t);
        }
        m.put("request", parts[0]);
        m.put("response", parts[1]);
        m.put("truncated", cut);
        m.put("roles", new ArrayList<Object>(a.roles()));
        return Json.write(m);
    }

    /** A teammate's API details: from the local cache, else downloaded once. Null when not shared / not uploaded yet. */
    public Map<String, Object> detail(String fileId) throws IOException {
        if (Text.blank(fileId)) {
            return null;
        }
        Path f = store.syncDir().resolve("chi-tiet").resolve(ProjectStore.safe(fileId) + ".json");
        if (!Files.isRegularFile(f)) {
            ProjectStore.write(f, hub().downloadImage(fileId));
        }
        return Json.parseObject(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Client screenshots waiting to be uploaded (ClientRound for a Q&A of another workspace). */
    private void uploadClientImages(SheetHub hub) throws IOException {
        for (SyncRecord r : store.pending()) {
            if (!SyncRecord.QAIMG.equals(r.type()) || !r.str("file").isEmpty() || r.str("local").isEmpty()) {
                continue;
            }
            Path p = store.dir().resolve(r.str("local"));
            if (!Files.isRegularFile(p)) {
                continue;
            }
            SheetHub.Uploaded u = hub.uploadFile(r.str("qa"), p.getFileName().toString(), "image/png", Files.readAllBytes(p));
            Map<String, Object> data = new LinkedHashMap<>(r.data());
            data.put("file", u.id());
            store.putLocal(new SyncRecord(r.id(), r.type(), r.owner(), r.account(), data, System.currentTimeMillis(), r.by(), false, 0L));
        }
    }

    /** Applies what came from the team: project settings (from PIC / PIC phụ) and answers to this person's Q&A. */
    private void apply(List<SyncRecord> changed, String me, Set<String> localQa) {
        ProjectConfig cfg = store.config();
        for (SyncRecord r : changed) {
            if (SyncRecord.PROJECT.equals(r.type()) && !r.deleted() && !r.by().equalsIgnoreCase(me)) {
                long theirs = r.num("updated_at");
                if (theirs > cfg.updatedAt && cfg.canManage(r.by()) || cfg.updatedAt == 0) {
                    cfg.applyShared(new LinkedHashMap<>(r.data()));
                    projectPushedAt = cfg.updatedAt;
                    store.save();
                    store.log(r.by(), "project", "project", "Nhận cài đặt dự án từ " + r.by());
                }
            }
            if (SyncRecord.QAIMG.equals(r.type()) && !r.deleted() && !r.str("file").isEmpty() && localQa.contains(r.str("qa"))
                    && !appliedAnswers.containsKey("img:" + r.id())) {
                try {
                    byte[] img = hub().downloadImage(r.str("file"));
                    if (host.applyImage(r.str("qa"), img)) {
                        appliedAnswers.put("img:" + r.id(), r.at());
                        store.log(r.by(), "qaimg", r.str("qa"), "Ảnh khách hàng gắn vào Q&A");
                    }
                } catch (IOException ignored) {
                    // next cycle
                }
                continue;
            }
            if (SyncRecord.QANS.equals(r.type()) && !r.deleted()) {
                String qaId = r.id().substring(SyncRecord.QANS.length() + 1);
                if (!localQa.contains(qaId)) {
                    continue;   // someone else's question: their JCrawl applies it
                }
                Long done = appliedAnswers.get(qaId);
                if (done != null && done >= r.at()) {
                    continue;
                }
                String text = r.str("answer");
                QaItem.Msg msg = new QaItem.Msg(r.by().isEmpty() ? "Khách hàng" : r.by(), r.at(), text, true);
                if (host.applyAnswer(qaId, msg)) {
                    appliedAnswers.put(qaId, r.at());
                    store.log(r.by(), "qans", qaId, text);
                }
            }
        }
    }

    // ------------------------------------------------------------------ rendering the team sheet
    /** PIC (online) first, then PIC phụ, then members, by name: only one person redraws the human sheets. */
    String renderer(String me) {
        ProjectConfig cfg = store.config();
        long now = System.currentTimeMillis();
        Set<String> online = new LinkedHashSet<>();
        online.add(me);
        for (SyncRecord r : store.records().values()) {
            if (SyncRecord.PRESENCE.equals(r.type()) && !r.deleted() && now - r.at() < 150_000) {
                online.add(r.owner());
            }
        }
        return online.stream().min(Comparator.comparingInt((String n) -> cfg.roleOf(n).ordinal()).thenComparing((String n) -> n.toLowerCase(java.util.Locale.ROOT)))
                .orElse(me);
    }

    private void renderIfElected(ProjectData d, String me) throws IOException {
        boolean elected = renderer(me).equalsIgnoreCase(me);
        status = new Status(status.state(), status.text(), status.lastOkMs(), status.pending(), status.conflicts(), elected,
                status.mirrorNote());
        if (!elected) {
            return;
        }
        long version = 0;
        for (SyncRecord r : store.records().values()) {
            version = Math.max(version, r.at());
        }
        long now = System.currentTimeMillis();
        if (version <= renderedVersion || now - lastRenderMs < 40_000 && renderedVersion > 0) {
            return;
        }
        ProjectWorkbook.Options o = new ProjectWorkbook.Options(d.letters(), store.config().activeProfile, false, 320, false,
                store.logTail(200), null, store.config().name, (q, name) -> {
                    String fid = images.get(q.item().id() + "/" + name);
                    if (fid == null) {
                        SyncRecord rec = store.record(SyncRecord.QA + ":" + q.item().id());
                        Object im = rec == null ? null : rec.data().get("images");
                        if (im instanceof Map<?, ?> mm && mm.get(name) != null) {
                            fid = String.valueOf(mm.get(name));
                        }
                    }
                    return fid == null ? "" : SheetHub.driveLink(fid);
                });
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (ProjectWorkbook.Table t : ProjectWorkbook.tables(d, store.config().sheets, o)) {
            sheets.add(sheetPayload(t));
        }
        hub().render(version, sheets);
        renderedVersion = version;
        lastRenderMs = now;
    }

    static Map<String, Object> sheetPayload(ProjectWorkbook.Table t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name());
        m.put("headerRow", t.headerRow());
        m.put("header", t.header());
        List<Object> rows = new ArrayList<>();
        List<Object> tones = new ArrayList<>();
        for (List<Object> row : t.rows()) {
            List<Object> vr = new ArrayList<>();
            List<Object> tr = new ArrayList<>();
            for (Object v : row) {
                String tone = "";
                Object val = v;
                if (v instanceof Xlsx.StyledCell sc) {
                    val = sc.value();
                    tone = toneCode(sc.tone());
                }
                if (val instanceof Xlsx.Formula f) {
                    tone = tone.isEmpty() ? toneCode(f.tone()) : tone;
                    val = "=" + f.expr();
                }
                if (val == null) {
                    val = "";
                }
                if (!(val instanceof Number) && !(val instanceof Boolean)) {
                    String s = String.valueOf(val);
                    val = s.length() > 49000 ? s.substring(0, 49000) : s;
                }
                vr.add(val);
                tr.add(tone);
            }
            rows.add(vr);
            tones.add(tr);
        }
        m.put("rows", rows);
        m.put("tones", tones);
        List<Object> w = new ArrayList<>();
        for (int x : t.widths()) {
            w.add(x);
        }
        m.put("widths", w);
        m.put("hidden", new ArrayList<>(t.hidden()));
        Map<String, Object> ed = new LinkedHashMap<>();
        t.editable().forEach((k, v) -> ed.put(String.valueOf(k), v));
        m.put("editable", ed);
        return m;
    }

    static String toneCode(Xlsx.CellTone t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case DONE_GREEN -> "DONE";
            case DENIED_SLATE -> "DENIED";
            case ERROR_RED -> "ERROR";
            case MISSING_RED -> "MISSING";
            case DOING_BLUE -> "DOING";
            case OOS_GRAY -> "OOS";
            case CONFLICT_ORANGE -> "CONFLICT";
            case INFO_YELLOW -> "INFO";
            case QA_PURPLE -> "QA";
            case BOLD -> "B";
            case TITLE -> "T";
            case HINT -> "HINT";
            case HEADER -> "H";
            default -> "";
        };
    }

    /** Rebuilds the whole team sheet from this machine's copy (the sheet was emptied / lost / replaced). */
    public int rebuildSheet() throws IOException {
        SheetHub hub = hub();
        ProjectConfig cfg = store.config();
        hub.hello(cfg.id, cfg.name);
        List<SyncRecord> all = new ArrayList<>(store.records().values());
        if (data != null) {
            all.addAll(localRecords(data, me()).values());
        }
        SheetHub.Pushed p = hub.push(all);
        store.pushed(p.stamped());
        renderedVersion = 0;
        lastRenderMs = 0;
        store.log(me(), "restore", "sheet", "Dựng lại Google Sheet: " + all.size() + " bản ghi");
        return all.size();
    }

    /** Puts chosen records of a snapshot back (they become the newest version). */
    public int restore(List<SyncRecord> chosen) {
        int n = 0;
        for (SyncRecord r : chosen) {
            store.putLocal(new SyncRecord(r.id(), r.type(), r.owner(), r.account(), r.data(), System.currentTimeMillis(), me(), r.deleted(),
                    0L));
            n++;
        }
        store.log(me(), "restore", "snapshot", "Khôi phục " + n + " bản ghi từ bản lưu");
        return n;
    }

    // ------------------------------------------------------------------ personal Excel copy
    private void mirror(ProjectData d) {
        long now = System.currentTimeMillis();
        StringBuilder sig = new StringBuilder();
        long maxAt = 0;
        for (SyncRecord r : store.records().values()) {
            maxAt = Math.max(maxAt, r.at());
        }
        sig.append(maxAt).append('|').append(d.rows().size()).append('|');
        for (ProjectData.LocalCell c : d.localCells()) {
            sig.append(c.rowId()).append(c.letter()).append(c.status()).append(';');
        }
        for (ProjectData.QaRef q : d.allQa()) {
            sig.append(q.item().id()).append(q.item().updatedAtMs()).append(';');
        }
        String s = Integer.toHexString(sig.toString().hashCode()) + sig.length();
        if (s.equals(mirrorSig) || now - lastMirrorMs < 15_000 && !mirrorSig.isEmpty()) {
            return;
        }
        try {
            ProjectWorkbook.Options o = ProjectWorkbook.Options.internal(d).withLog(store.logTail(300)).withRemote(this::remoteImage);
            byte[] x = ProjectWorkbook.xlsx(d, store.config().sheets, o);
            ProjectStore.write(store.mirrorFile(), x);
            mirrorSig = s;
            lastMirrorMs = now;
            status = new Status(status.state(), status.text(), status.lastOkMs(), status.pending(), status.conflicts(), status.renderer(),
                    "Excel của bạn: " + store.mirrorFile().getFileName() + " · " + ProjectStore.time(now));
        } catch (IOException | RuntimeException e) {
            status = new Status(status.state(), status.text(), status.lastOkMs(), status.pending(), status.conflicts(), status.renderer(),
                    "Chưa ghi được Excel (đang mở trong Excel?) · sẽ thử lại");
        }
    }

    /** A teammate's screenshot: from the local cache (anh-qa/), else downloaded once from the team Drive folder. */
    public byte[] remoteImage(ProjectData.QaRef q, String name) {
        Path f = store.qaImageDir(q.item().id()).resolve(ProjectStore.safe(name));
        try {
            if (Files.isRegularFile(f)) {
                return Files.readAllBytes(f);
            }
            SyncRecord rec = store.record(SyncRecord.QA + ":" + q.item().id());
            Object im = rec == null ? null : rec.data().get("images");
            if (hubOn() && im instanceof Map<?, ?> mm && mm.get(name) != null) {
                byte[] b = hub().downloadImage(String.valueOf(mm.get(name)));
                ProjectStore.write(f, b);
                return b;
            }
        } catch (IOException | RuntimeException ignored) {
            // shown as a missing picture
        }
        return null;
    }

    // ------------------------------------------------------------------ state
    private Path stateFile() {
        return store.syncDir().resolve("state.json");
    }

    private void loadState() {
        try {
            Path f = stateFile();
            if (!Files.isRegularFile(f)) {
                return;
            }
            Map<String, Object> m = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            since = Json.num(m, "since", 0);
            projectPushedAt = Json.num(m, "project_pushed", 0);
            renderedVersion = Json.num(m, "rendered", 0);
            Json.obj(m, "pushed").forEach((k, v) -> pushedHash.put(k, String.valueOf(v)));
            Json.obj(m, "images").forEach((k, v) -> images.put(k, String.valueOf(v)));
            Json.obj(m, "answers").forEach((k, v) -> appliedAnswers.put(k, v instanceof Number n ? n.longValue() : 0L));
            Json.obj(m, "details").forEach((k, v) -> details.put(k, String.valueOf(v)));
        } catch (IOException | RuntimeException ignored) {
            // start fresh: a full push / pull
        }
    }

    private synchronized void saveState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("since", since);
        m.put("project_pushed", projectPushedAt);
        m.put("rendered", renderedVersion);
        m.put("pushed", new LinkedHashMap<>(pushedHash));
        m.put("images", new LinkedHashMap<>(images));
        m.put("answers", new LinkedHashMap<>(appliedAnswers));
        m.put("details", new LinkedHashMap<>(details));
        try {
            ProjectStore.write(stateFile(), Json.write(m).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            // next cycle
        }
    }

    /** Forget what was sent (next cycle sends everything again), e.g. after connecting a new sheet. */
    public synchronized void resetSent() {
        pushedHash.clear();
        since = 0;
        renderedVersion = 0;
        helloDone = false;
        projectPushedAt = 0;
        saveState();
    }
}
