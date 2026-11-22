package themythofcrawl.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.excel.TargetList;
import themythofcrawl.model.Blob;
import themythofcrawl.model.QaItem;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;

/**
 * V_11.4: the project list ("danh sách chuẩn") x accounts, built from the account workspaces on this machine plus the
 * team's records (other people's accounts), with manual marks on top. Every view, sheet and report reads this.
 * <ul>
 * <li>Rows: the standard Excel file, and / or the APIs account A crawled; APIs other accounts found beyond that are
 * "Chỉ B có" until the PIC approves them.</li>
 * <li>Cells: Done / Denied / Error from the account's own request, Missing when never called, Doing while being
 * tested, OOS / anything else when someone set it by hand (hand-set always wins).</li>
 * </ul>
 */
public final class ProjectData {

    public enum Source {
        STANDARD("File chuẩn"),
        CRAWL("Từ crawl A"),
        ADDED("Đã thêm vào danh sách"),
        OUTSIDE("Ngoài danh sách"),
        ONLY("Chỉ một số tài khoản có");

        public final String label;

        Source(String label) {
            this.label = label;
        }

        public boolean inList() {
            return this == STANDARD || this == CRAWL || this == ADDED;
        }
    }

    /** One API seen in an account workspace on this machine. */
    public record ApiFact(int gid, String method, String path, String host, String branch, Integer http, Integer targetRow,
                          String key, List<QaItem> qa, long createdMs, List<String> tags, List<String> bugs, List<Map<String, Object>> roles,
                          boolean hidden) {
        public ApiFact(int gid, String method, String path, String host, String branch, Integer http, Integer targetRow, String key,
                       List<QaItem> qa, long createdMs) {
            this(gid, method, path, host, branch, http, targetRow, key, qa, createdMs, List.of(), List.of(), List.of(), false);
        }
    }

    /** "Dữ liệu người khác · Cao · Đã xác nhận (role B)". */
    static String bugLabel(Map<String, Object> m) {
        themythofcrawl.model.BugMark b = themythofcrawl.model.BugMark.fromMap(m);
        return b.type().label + " · " + b.severity().label + " · " + b.status().label + (b.role().isEmpty() ? "" : " (" + b.role() + ")");
    }

    /** An account workspace on this machine. */
    public record LocalAccount(String letter, String name, Path dir, FileStorage storage, List<ApiFact> apis, TargetList targets) {
        public byte[] image(int gid, String name) {
            try {
                Blob b = storage.blob(gid, name);
                return b == null ? null : b.bytes();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public interface Images {
        byte[] image(String name);
    }

    public static final class Cell {
        public AccStatus status = AccStatus.MISSING;
        public Integer http;
        public int apis;
        public boolean manual;
        public String manualVia = "";
        public String note = "";
        public String by = "";
        public long at;
        public boolean info;
        public boolean qaOpen;
        public boolean qaAny;
        public String doingBy = "";
        public boolean conflict;
        public final List<Integer> gids = new ArrayList<>();
        public final Map<String, AccStatus> byMember = new LinkedHashMap<>();
        public final java.util.Set<String> tags = new java.util.LinkedHashSet<>();
        public final java.util.Set<String> bugs = new java.util.LinkedHashSet<>();
        /** HTTP of each member's best result on this cell. */
        public final Map<String, Integer> httpBy = new LinkedHashMap<>();
        /** Role replay summary: role name -> HTTP status (V_11.5). */
        public final Map<String, Long> roles = new java.util.TreeMap<>();

        /**
         * V_11.5: the column is the web account, the parentheses name the people: "Done (an)", "Done (hau, an)",
         * "Denied (an) 403", "Done (hau) · Xung đột: Denied (an) 403", "Doing (an)", "OOS (hau, đặt tay)",
         * "Done (khách hàng)", "Missing". Always starts with the status word (Excel COUNTIFS count it).
         */
        public String text(String letter) {
            StringBuilder sb = new StringBuilder();
            if (manual) {
                String via = manualVia == null ? "" : manualVia;
                String who = via.startsWith("Khách hàng") ? "khách hàng"
                        : (by.isEmpty() ? "" : by + ", ") + (via.equals("Google Sheet") ? "trên Google Sheet" : "đặt tay");
                sb.append(status.word).append(" (").append(who).append(')');
            } else if (status == AccStatus.MISSING) {
                sb.append(status.word);
            } else if (status == AccStatus.DOING && !doingBy.isEmpty() && byMember.isEmpty()) {
                sb.append(status.word).append(" (").append(doingBy).append(')');
            } else {
                List<String> same = new ArrayList<>();
                byMember.forEach((m, st) -> {
                    if (st == status) {
                        same.add(m);
                    }
                });
                if (same.isEmpty() && !by.isEmpty()) {
                    same.add(by);
                }
                sb.append(status.word);
                if (!same.isEmpty()) {
                    sb.append(" (").append(String.join(", ", same)).append(')');
                }
                if (http != null && status != AccStatus.DONE && status != AccStatus.DOING) {
                    sb.append(' ').append(http);
                }
                if (conflict) {
                    List<String> other = new ArrayList<>();
                    byMember.forEach((m, st) -> {
                        if (st != status && st != AccStatus.DOING) {
                            Integer h = httpBy.get(m);
                            other.add(st.word + " (" + m + ")" + (h != null && st != AccStatus.DONE ? " " + h : ""));
                        }
                    });
                    if (!other.isEmpty()) {
                        sb.append(" · Xung đột: ").append(String.join(" · ", other));
                    }
                }
                if (!doingBy.isEmpty()) {
                    sb.append(" · đang làm: ").append(doingBy);
                }
            }
            if (info) {
                sb.append(" · Info");
            }
            if (qaOpen) {
                sb.append(" · Q&A");
            }
            return sb.toString();
        }
    }

    public static final class Row {
        public final String id;
        public String stt = "";
        public String method = "";
        public String path = "";
        public String host = "";
        public String branch = "";
        public Source source;
        public boolean excluded;
        public String note = "";
        public long firstSeen = Long.MAX_VALUE;
        public final Map<String, Cell> cells = new LinkedHashMap<>();
        public final List<QaRef> qa = new ArrayList<>();
        public String bac = "";

        Row(String id, Source source) {
            this.id = id;
            this.source = source;
        }

        public Cell cell(String letter) {
            return cells.computeIfAbsent(letter, k -> new Cell());
        }

        public int reached() {
            int n = 0;
            for (Cell c : cells.values()) {
                n += c.status == AccStatus.DONE ? 1 : 0;
            }
            return n;
        }

        public String onlyLetters() {
            List<String> l = new ArrayList<>();
            cells.forEach((k, c) -> {
                if (c.apis > 0 || c.status == AccStatus.DONE) {
                    l.add(k);
                }
            });
            return String.join(", ", l);
        }

        public String sourceLabel() {
            return source == Source.ONLY ? "Chỉ " + onlyLetters() + " có" : source.label;
        }

        public String tester() {
            Cell best = null;
            for (Cell c : cells.values()) {
                if (!c.by.isEmpty() && (best == null || c.at > best.at)) {
                    best = c;
                }
            }
            return best == null ? "" : best.by;
        }

        public long testedAt() {
            long t = 0;
            for (Cell c : cells.values()) {
                t = Math.max(t, c.at);
            }
            return t;
        }
    }

    /** A Q&A / Info item on a row, with where its images come from. */
    public record QaRef(String rowId, String letter, String member, QaItem item, Images images, boolean local, int gid, String raw) {
        public QaRef(String rowId, String letter, String member, QaItem item, Images images, boolean local, int gid) {
            this(rowId, letter, member, item, images, local, gid, letter);
        }
    }

    public record Conflict(SyncRecord record) {
    }

    private final ProjectConfig cfg;
    private final List<ProjectConfig.Account> accounts;
    private final List<Row> rows;
    private final Map<String, Row> byId;
    private final List<LocalAccount> locals;
    private final List<SyncRecord> conflicts;

    private ProjectData(ProjectConfig cfg, List<ProjectConfig.Account> accounts, List<Row> rows, List<LocalAccount> locals,
                        List<SyncRecord> conflicts) {
        this.cfg = cfg;
        this.accounts = accounts;
        this.rows = rows;
        this.locals = locals;
        this.conflicts = conflicts;
        this.byId = new LinkedHashMap<>();
        for (Row r : rows) {
            byId.put(r.id, r);
        }
    }

    public ProjectConfig config() {
        return cfg;
    }

    public List<ProjectConfig.Account> accounts() {
        return accounts;
    }

    public List<String> letters() {
        return accounts.stream().map(ProjectConfig.Account::letter).toList();
    }

    public List<Row> rows() {
        return rows;
    }

    public Row row(String id) {
        return byId.get(id);
    }

    public List<LocalAccount> locals() {
        return locals;
    }

    /** What this machine's own account workspaces found, per row and letter (what gets pushed to the team). */
    public record LocalCell(String rowId, String letter, AccStatus status, Integer http, int apis, String method, String path,
                            String host, String branch, long at, java.util.Set<String> tags, java.util.Set<String> bugs,
                            Map<String, Long> roles) {
    }

    private final List<LocalCell> localCells = new ArrayList<>();

    public List<LocalCell> localCells() {
        return localCells;
    }

    public List<SyncRecord> conflicts() {
        return conflicts;
    }

    public boolean isLocal(String letter) {
        return locals.stream().anyMatch(l -> l.letter().equalsIgnoreCase(letter));
    }

    /** Rows of the project list (not excluded), in order. */
    public List<Row> listRows() {
        return rows.stream().filter(r -> !r.excluded && r.source.inList()).toList();
    }

    /** Every row not excluded (list + outside + only). */
    public List<Row> visibleRows() {
        return rows.stream().filter(r -> !r.excluded).toList();
    }

    public List<QaRef> allQa() {
        List<QaRef> out = new ArrayList<>();
        for (Row r : rows) {
            out.addAll(r.qa);
        }
        out.sort(Comparator.comparingLong(q -> q.item().atMs()));
        return out;
    }

    // ------------------------------------------------------------------ local accounts from disk
    /** Account workspaces on this machine: the family of {@code anyWorkspace} (A = root unless the meta says otherwise). */
    public static List<LocalAccount> loadLocal(Path anyWorkspace) {
        List<LocalAccount> out = new ArrayList<>();
        List<Workspaces.Info> fam = Workspaces.family(anyWorkspace);
        Set<String> used = new LinkedHashSet<>();
        for (Workspaces.Info i : fam) {
            String l = Workspaces.metaValue(i.dir(), "letter").trim().toUpperCase(Locale.ROOT);
            if (!l.isEmpty()) {
                used.add(l);
            }
        }
        char next = 'A';
        for (Workspaces.Info i : fam) {
            String letter = Workspaces.metaValue(i.dir(), "letter").trim().toUpperCase(Locale.ROOT);
            if (letter.isEmpty()) {
                while (used.contains(String.valueOf(next)) && next < 'Z') {
                    next++;
                }
                letter = String.valueOf(next);
                used.add(letter);
            }
            out.add(loadAccount(i, letter));
        }
        return out;
    }

    public static LocalAccount loadAccount(Workspaces.Info i, String letter) {
        FileStorage st = new FileStorage(i.dir(), i.name());
        TargetList tl = null;
        try {
            String state = st.loadState();
            if (state != null && !state.isBlank()) {
                Object t = Json.parseObject(state).get("targets");
                tl = t instanceof String ts && !ts.isBlank() ? TargetList.fromJson(ts) : null;
            }
        } catch (RuntimeException ignored) {
            tl = null;
        }
        List<ApiFact> apis = new ArrayList<>();
        java.nio.file.Path apiRoot = i.dir().resolve("apis");
        if (java.nio.file.Files.isDirectory(apiRoot)) {
            try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(apiRoot)) {
                for (java.nio.file.Path d : ds) {
                    java.nio.file.Path meta = d.resolve("meta.json");
                    try {
                        if (!java.nio.file.Files.isRegularFile(meta)) {
                            continue;
                        }
                        long mt = java.nio.file.Files.getLastModifiedTime(meta).toMillis();
                        long sz = java.nio.file.Files.size(meta);
                        Cached c = CACHE.get(meta);
                        if (c == null || c.mtime != mt || c.size != sz) {
                            c = new Cached(mt, sz, parse(java.nio.file.Files.readString(meta, java.nio.charset.StandardCharsets.UTF_8)));
                            CACHE.put(meta, c);
                        }
                        if (c.fact != null) {
                            apis.add(c.fact);
                        }
                    } catch (java.io.IOException | RuntimeException ignored) {
                        // a file being written: next cycle
                    }
                }
            } catch (java.io.IOException ignored) {
                // no API yet
            }
        }
        apis.sort(Comparator.comparingInt(ApiFact::gid));
        String name = Text.blank(i.account()) ? i.name() : i.account();
        return new LocalAccount(letter, name, i.dir(), st, apis, tl);
    }

    /** Parsed API files by path + time + size: each cycle only re-reads what changed. */
    private record Cached(long mtime, long size, ApiFact fact) {
    }

    private static final Map<java.nio.file.Path, Cached> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    static ApiFact parse(String doc) {
        Map<String, Object> g;
        try {
            g = Json.parseObject(doc);
        } catch (RuntimeException e) {
            return null;
        }
        String method = Json.str(g, "method", "GET");
        String path = Json.str(g, "path", "/");
        String bk = Json.str(g, "book_key", null);
        String keyPath = bk != null && bk.lastIndexOf('|') >= 0 ? bk.substring(bk.lastIndexOf('|') + 1) : path;
        Object row = g.get("target_row");
        Object os = g.get("orig_status");
        List<QaItem> qa = new ArrayList<>();
        for (Object o : Json.list(g, "qa")) {
            if (o instanceof Map<?, ?> qm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) qm;
                qa.add(QaItem.fromMap(mm));
            }
        }
        List<String> tags = new ArrayList<>();
        for (Object o : Json.list(g, "tags")) {
            tags.add(String.valueOf(o));
        }
        List<String> bugs = new ArrayList<>();
        for (Object o : Json.list(g, "bugs")) {
            if (o instanceof Map<?, ?> bm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> b = (Map<String, Object>) bm;
                themythofcrawl.model.BugMark mark = themythofcrawl.model.BugMark.fromMap(b);
                if (mark.status().open()) {
                    bugs.add(bugLabel(b));
                }
            }
        }
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Object o : Json.list(g, "results")) {
            if (o instanceof Map<?, ?> rm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) rm;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("role", Json.str(r, "name", ""));
                x.put("status", Json.num(r, "status", 0));
                x.put("length", Json.num(r, "length", 0));
                x.put("state", Json.str(r, "state", ""));
                roles.add(x);
            }
        }
        return new ApiFact((int) Json.num(g, "gid", 0), method, path, Json.str(g, "host", ""), Json.str(g, "branch", ""),
                os instanceof Number n ? n.intValue() : null, row instanceof Number n ? n.intValue() : null,
                Engine.endpointKey(method, keyPath), qa, Json.num(g, "created_ms", 0), tags, bugs, roles, Json.bool(g, "hidden", false));
    }

    // ------------------------------------------------------------------ build
    /** @param letter the column it shows in (after merges); {@code raw} the workspace's own letter (what is sent) */
    private record Obs(String letter, String member, Integer http, AccStatus status, int gid, long at, String method, String path,
                       String host, String branch, boolean remote, List<String> tags, List<String> bugs, Map<String, Long> roles,
                       String raw) {
    }

    static Map<String, Long> roleSummary(List<Map<String, Object>> results) {
        Map<String, Long> out = new java.util.TreeMap<>();
        for (Map<String, Object> r : results) {
            long st = Json.num(r, "status", 0);
            String name = Json.str(r, "role", "");
            if (!name.isEmpty() && st > 0) {
                out.put(name, st);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> longs(Object o) {
        Map<String, Long> out = new java.util.TreeMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (v instanceof Number n) {
                    out.put(String.valueOf(k), n.longValue());
                }
            });
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                out.add(String.valueOf(x));
            }
        }
        return out;
    }

    public static String keyRowId(String key) {
        return "K:" + key;
    }

    public static String stdRowId(int rowNo) {
        return "R" + rowNo;
    }

    /**
     * @param me this person's name (owner of the local accounts' data)
     * @param records every team record known here (may be empty)
     */
    public static ProjectData build(ProjectConfig cfg, List<LocalAccount> locals, Map<String, SyncRecord> records, String me) {
        String who = Text.nz(me);
        Map<String, SyncRecord> recs = records == null ? Map.of() : records;
        // accounts: configured + local + seen in records (registered in the project settings)
        for (LocalAccount l : locals) {
            ProjectConfig.Account a = cfg.ensureAccount(l.letter(), l.name());
            if (a.owner().isEmpty() && !who.isEmpty()) {
                cfg.accounts.set(cfg.accounts.indexOf(a), new ProjectConfig.Account(a.letter(), a.name(), who, a.aliases(), a.mergedInto(),
                        a.fps(), a.createdBy().isEmpty() ? who : a.createdBy(), a.createdAt(), a.mergedBy()));
            }
        }
        for (SyncRecord r : recs.values()) {
            if (SyncRecord.CELL.equals(r.type()) && !r.account().isEmpty()) {
                cfg.ensureAccount(r.account(), r.str("account_name"));
            }
        }
        // merged web accounts (B "sys" merged into A "system") show under the account they were merged into
        List<ProjectConfig.Account> accounts = new ArrayList<>(cfg.activeAccounts());
        String first = accounts.isEmpty() ? "A" : accounts.get(0).letter();

        // standard rows
        TargetList targets = null;
        for (LocalAccount l : locals) {
            if (l.targets() != null && l.letter().equalsIgnoreCase(first)) {
                targets = l.targets();
            }
        }
        if (targets == null) {
            for (LocalAccount l : locals) {
                if (l.targets() != null) {
                    targets = l.targets();
                    break;
                }
            }
        }
        if (targets == null && cfg.targetsJson != null) {
            try {
                targets = TargetList.fromJson(cfg.targetsJson);
            } catch (RuntimeException ignored) {
                targets = null;
            }
        }
        boolean useStd = targets != null && cfg.baseline != ProjectConfig.BaselineMode.FROM_A;
        Map<String, Row> rows = new LinkedHashMap<>();
        if (useStd) {
            for (TargetEntry e : targets.entries()) {
                Row r = new Row(stdRowId(e.rowNo()), Source.STANDARD);
                r.stt = e.sttText();
                r.method = e.methodLabel();
                r.path = e.path();
                r.branch = e.position();
                r.host = Text.nz(e.host());
                rows.put(r.id, r);
            }
        }

        // observations
        Map<String, List<Obs>> obs = new LinkedHashMap<>();
        Set<String> localLetters = new LinkedHashSet<>();
        List<QaRef> qaRefs = new ArrayList<>();
        for (LocalAccount l : locals) {
            localLetters.add(l.letter());
            for (ApiFact a : l.apis()) {
                String rid = a.targetRow() != null && rows.containsKey(stdRowId(a.targetRow())) ? stdRowId(a.targetRow()) : keyRowId(a.key());
                obs.computeIfAbsent(rid, k -> new ArrayList<>()).add(new Obs(cfg.resolve(l.letter()), who, a.http(), AccStatus.ofHttp(a.http()), a.gid(), a.createdMs(),
                        a.method(), a.path(), a.host(), a.branch(), false, a.tags(), a.bugs(), roleSummary(a.roles()), l.letter()));
                for (QaItem q : a.qa()) {
                    qaRefs.add(new QaRef(rid, cfg.resolve(l.letter()), q.by().isEmpty() ? who : q.by(), q, n -> l.image(a.gid(), n), true, a.gid(),
                            l.letter()));
                }
            }
        }
        // remote cells (other people's accounts, or the same account tested by someone else)
        for (SyncRecord r : recs.values()) {
            if (!SyncRecord.CELL.equals(r.type()) || r.deleted()) {
                continue;
            }
            if (localLetters.contains(r.account()) && r.owner().equalsIgnoreCase(who)) {
                continue;   // our own data: computed fresh above
            }
            String rid = r.str("row");
            if (rid.isEmpty()) {
                continue;
            }
            if (rid.startsWith("R") && !rows.containsKey(rid)) {
                continue;   // a standard row of an Excel list this machine does not use
            }
            long http = r.num("http");
            AccStatus st = r.str("status").isEmpty() ? AccStatus.ofHttp(http > 0 ? (int) http : null) : parseName(r.str("status"));
            obs.computeIfAbsent(rid, k -> new ArrayList<>()).add(new Obs(cfg.resolve(r.account()), r.owner(), http > 0 ? (int) http : null,
                    st == null ? AccStatus.DOING : st, (int) r.num("gid"), r.at(), r.str("method"), r.str("path"), r.str("host"),
                    r.str("branch"), true, strings(r.data().get("tags")), strings(r.data().get("bugs")), longs(r.data().get("roles")),
                    r.account()));
        }
        // remote Q&A
        Set<String> localQaIds = new LinkedHashSet<>();
        for (QaRef q : qaRefs) {
            localQaIds.add(q.item().id());
        }
        for (SyncRecord r : recs.values()) {
            if (!SyncRecord.QA.equals(r.type()) || r.deleted()) {
                continue;
            }
            Object im = r.data().get("item");
            if (!(im instanceof Map<?, ?> m)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            QaItem item = QaItem.fromMap((Map<String, Object>) m);
            if (localQaIds.contains(item.id())) {
                continue;
            }
            qaRefs.add(new QaRef(r.str("row"), cfg.resolve(r.account()), r.owner(), item, null, false, (int) r.num("gid"), r.account()));
        }

        // key rows
        for (Map.Entry<String, List<Obs>> e : obs.entrySet()) {
            String rid = e.getKey();
            Row r = rows.get(rid);
            if (r == null) {
                boolean byFirst = e.getValue().stream().anyMatch(o -> o.letter().equalsIgnoreCase(first));
                Source src;
                if (cfg.added.stream().anyMatch(a -> a.id().equals(rid))) {
                    src = Source.ADDED;
                } else if (byFirst && cfg.baseline == ProjectConfig.BaselineMode.FROM_A) {
                    src = Source.CRAWL;
                } else if (byFirst && cfg.baseline == ProjectConfig.BaselineMode.BOTH) {
                    src = useStd ? Source.OUTSIDE : Source.CRAWL;
                } else if (byFirst) {
                    src = Source.OUTSIDE;
                } else {
                    src = Source.ONLY;
                }
                r = new Row(rid, src);
                rows.put(rid, r);
            }
            for (Obs o : e.getValue()) {
                if (r.method.isEmpty()) {
                    r.method = Text.nz(o.method());
                }
                if (r.path.isEmpty()) {
                    r.path = Text.stripQuery(Text.nz(o.path()));
                }
                if (r.host.isEmpty()) {
                    r.host = Text.nz(o.host());
                }
                if (r.branch.isEmpty() || o.letter().equalsIgnoreCase(first) && !Text.blank(o.branch())) {
                    r.branch = Text.nz(o.branch());
                }
                if (o.at() > 0) {
                    r.firstSeen = Math.min(r.firstSeen, o.at());
                }
            }
        }
        // approved rows nobody has seen yet still show
        for (ProjectConfig.Added a : cfg.added) {
            if (!rows.containsKey(a.id())) {
                Row r = new Row(a.id(), Source.ADDED);
                r.method = a.method();
                r.path = a.path();
                r.branch = a.branch();
                r.host = a.host();
                rows.put(r.id, r);
            }
        }
        // ROW records fill the meta of rows only known remotely
        for (SyncRecord rr : recs.values()) {
            if (SyncRecord.ROW.equals(rr.type()) && !rr.deleted()) {
                Row r = rows.get(rr.str("row").isEmpty() ? rr.id().substring(4) : rr.str("row"));
                if (r != null) {
                    if (r.method.isEmpty()) {
                        r.method = rr.str("method");
                    }
                    if (r.path.isEmpty()) {
                        r.path = rr.str("path");
                    }
                    if (r.branch.isEmpty()) {
                        r.branch = rr.str("branch");
                    }
                }
            }
        }

        // cells
        for (Row r : rows.values()) {
            for (ProjectConfig.Account a : accounts) {
                r.cell(a.letter());
            }
            r.excluded = cfg.excluded.contains(r.id);
        }
        for (Map.Entry<String, List<Obs>> e : obs.entrySet()) {
            Row r = rows.get(e.getKey());
            Map<String, Map<String, AccStatus>> perLetterMember = new LinkedHashMap<>();
            for (Obs o : e.getValue()) {
                Cell c = r.cell(o.letter());
                c.apis++;
                c.tags.addAll(o.tags());
                c.bugs.addAll(o.bugs());
                c.roles.putAll(o.roles());
                if (o.gid() > 0 && !o.remote()) {
                    c.gids.add(o.gid());
                }
                AccStatus st = o.status();
                Map<String, AccStatus> pm = perLetterMember.computeIfAbsent(o.letter(), k -> new LinkedHashMap<>());
                AccStatus prev = pm.get(o.member());
                if (prev == null || rank(st) > rank(prev)) {
                    pm.put(o.member(), st);
                    c.httpBy.put(o.member(), o.http());
                }
                if (rank(st) > rank(c.status)) {
                    c.status = st;
                    c.http = o.http();
                }
                if (o.at() >= c.at) {
                    c.at = o.at();
                    c.by = o.member();
                }
            }
            perLetterMember.forEach((letter, pm) -> {
                Cell c = r.cell(letter);
                c.byMember.putAll(pm);
                Set<AccStatus> finals = new LinkedHashSet<>();
                for (AccStatus s : pm.values()) {
                    if (s == AccStatus.DONE || s == AccStatus.DENIED || s == AccStatus.ERROR) {
                        finals.add(s == AccStatus.ERROR ? AccStatus.DENIED : s);
                    }
                }
                c.conflict = finals.size() > 1;
            });
        }
        // presence: Doing
        long now = System.currentTimeMillis();
        for (SyncRecord p : recs.values()) {
            if (SyncRecord.PRESENCE.equals(p.type()) && !p.deleted() && now - p.at() < 120_000) {
                Row r = rows.get(p.str("row"));
                if (r != null && !p.account().isEmpty()) {
                    Cell c = r.cell(cfg.resolve(p.account()));
                    c.doingBy = p.owner();
                    if (c.status == AccStatus.MISSING) {
                        c.status = AccStatus.DOING;
                    }
                }
            }
        }
        // marks (hand-set wins) + notes
        List<SyncRecord> conflicts = new ArrayList<>();
        for (SyncRecord m : recs.values()) {
            if (SyncRecord.MARK.equals(m.type()) && !m.deleted()) {
                Row r = rows.get(m.str("row"));
                if (r == null) {
                    continue;
                }
                Cell c = r.cell(cfg.resolve(m.account()));
                AccStatus st = m.str("status").isEmpty() ? null : parseName(m.str("status"));
                if (st != null) {
                    c.status = st;
                    c.manual = true;
                    c.manualVia = m.str("via");
                    c.by = m.by();
                    c.at = Math.max(c.at, m.at());
                }
                c.note = m.str("note");
            } else if (SyncRecord.NOTE.equals(m.type()) && !m.deleted()) {
                Row r = rows.get(m.str("row"));
                if (r != null) {
                    r.note = m.str("note");
                }
            } else if (SyncRecord.CONFLICT.equals(m.type()) && !m.deleted()) {
                conflicts.add(m);
            }
        }
        // Q&A on rows; answers written in the sheet / client files
        Map<String, SyncRecord> answers = new LinkedHashMap<>();
        for (SyncRecord a : recs.values()) {
            if (SyncRecord.QANS.equals(a.type()) && !a.deleted()) {
                answers.put(a.id().substring(SyncRecord.QANS.length() + 1), a);
            }
        }
        for (QaRef q : qaRefs) {
            Row r = rows.get(q.rowId());
            if (r == null) {
                continue;
            }
            QaItem it = q.item();
            SyncRecord ans = answers.get(it.id());
            if (ans != null && ans.at() > it.updatedAtMs() && !ans.str("answer").equals(it.answer())) {
                it = it.withMessage(new QaItem.Msg(ans.by().isEmpty() ? "Khách hàng" : ans.by(), ans.at(), ans.str("answer"), true));
            }
            QaRef ref = new QaRef(q.rowId(), q.letter(), q.member(), it, q.images(), q.local(), q.gid(), q.raw());
            r.qa.add(ref);
            Cell c = r.cell(q.letter());
            if (it.kind() == QaItem.Kind.INFO && it.status() != QaItem.Status.CLOSED) {
                c.info = true;
            }
            if (it.kind() == QaItem.Kind.QA) {
                c.qaAny = true;
                c.qaOpen |= it.status().waiting();
            }
        }
        // BAC hint: a lower account reaches what a higher one is blocked from
        for (Row r : rows.values()) {
            List<String> notes = new ArrayList<>();
            for (int i = 0; i < accounts.size(); i++) {
                Cell hi = r.cells.get(accounts.get(i).letter());
                if (hi == null || hi.status != AccStatus.DENIED) {
                    continue;
                }
                for (int j = i + 1; j < accounts.size(); j++) {
                    Cell lo = r.cells.get(accounts.get(j).letter());
                    if (lo != null && lo.status == AccStatus.DONE) {
                        notes.add(accounts.get(j).letter() + " gọi được, " + accounts.get(i).letter() + " bị chặn");
                    }
                }
            }
            r.bac = notes.isEmpty() ? "" : "Cần xem: " + String.join("; ", notes);
        }

        // order + STT for crawl rows
        List<Row> out = new ArrayList<>(rows.values());
        List<Row> std = out.stream().filter(r -> r.source == Source.STANDARD).toList();
        List<Row> rest = new ArrayList<>(out.stream().filter(r -> r.source != Source.STANDARD).toList());
        rest.sort(Comparator.comparingInt((Row r) -> r.source.ordinal()).thenComparing(r -> r.branch.toLowerCase(Locale.ROOT))
                .thenComparingLong(r -> r.firstSeen).thenComparing(r -> r.path));
        List<Row> ordered = new ArrayList<>(std);
        ordered.addAll(rest);
        int n = 0;
        for (Row r : ordered) {
            if (r.source != Source.STANDARD && r.stt.isEmpty() && (r.source == Source.CRAWL || r.source == Source.ADDED)) {
                n++;
                r.stt = (std.isEmpty() ? "" : "+") + n;
            }
        }
        ProjectData built = new ProjectData(cfg, accounts, ordered, locals, conflicts);
        Map<String, LocalCell> lc = new LinkedHashMap<>();
        for (Map.Entry<String, List<Obs>> e : obs.entrySet()) {
            for (Obs o : e.getValue()) {
                if (o.remote()) {
                    continue;
                }
                String k = e.getKey() + "|" + o.raw();
                LocalCell cur = lc.get(k);
                Row r = rows.get(e.getKey());
                if (cur == null) {
                    lc.put(k, new LocalCell(e.getKey(), o.raw(), o.status(), o.http(), 1, r.method, r.path, r.host, r.branch, o.at(),
                            new java.util.TreeSet<>(o.tags()), new java.util.TreeSet<>(o.bugs()), new java.util.TreeMap<>(o.roles())));
                } else {
                    boolean better = rank(o.status()) > rank(cur.status());
                    cur.tags().addAll(o.tags());
                    cur.bugs().addAll(o.bugs());
                    cur.roles().putAll(o.roles());
                    lc.put(k, new LocalCell(e.getKey(), o.raw(), better ? o.status() : cur.status(), better ? o.http() : cur.http(),
                            cur.apis() + 1, cur.method(), cur.path(), cur.host(), cur.branch(), Math.max(cur.at(), o.at()), cur.tags(),
                            cur.bugs(), cur.roles()));
                }
            }
        }
        built.localCells.addAll(lc.values());
        return built;
    }

    static AccStatus parseName(String s) {
        try {
            return AccStatus.valueOf(s);
        } catch (RuntimeException e) {
            return AccStatus.parse(s);
        }
    }

    static int rank(AccStatus s) {
        return switch (s) {
            case DONE -> 5;
            case DENIED -> 4;
            case ERROR -> 3;
            case DOING -> 2;
            case OOS -> 1;
            case MISSING -> 0;
        };
    }

    // ------------------------------------------------------------------ filters & stats
    /** missing:B / done:A / denied:B / error:C / oos:A / diff / only / outside / qa / info / bac / text:abc / list / all. */
    public boolean matches(Row r, String spec) {
        String s = Text.nz(spec).trim();
        if (s.isEmpty() || s.equals("all")) {
            return !r.excluded;
        }
        int c = s.indexOf(':');
        String k = (c < 0 ? s : s.substring(0, c)).toLowerCase(Locale.ROOT);
        String v = c < 0 ? "" : s.substring(c + 1).trim();
        if (r.excluded && !k.equals("excluded")) {
            return false;
        }
        return switch (k) {
            case "missing", "done", "denied", "error", "oos", "doing" -> {
                Cell cell = r.cells.get(v.toUpperCase(Locale.ROOT));
                yield cell != null && cell.status.name().equalsIgnoreCase(k);
            }
            case "diff" -> {
                int reached = r.reached();
                yield reached < accounts.size() && r.cells.values().stream().anyMatch(x -> x.status != AccStatus.MISSING);
            }
            case "only" -> !r.source.inList();
            case "list" -> r.source.inList();
            case "qa" -> r.qa.stream().anyMatch(q -> q.item().kind() == QaItem.Kind.QA);
            case "info" -> r.qa.stream().anyMatch(q -> q.item().kind() == QaItem.Kind.INFO);
            case "bac" -> !r.bac.isEmpty();
            case "bug" -> r.cells.values().stream().anyMatch(x -> !x.bugs.isEmpty());
            case "person" -> r.cells.values().stream().anyMatch(x -> x.byMember.containsKey(v) || x.manual && x.by.equalsIgnoreCase(v)
                    || x.doingBy.equalsIgnoreCase(v)) || r.qa.stream().anyMatch(q -> q.item().by().equalsIgnoreCase(v));
            case "conflict" -> r.cells.values().stream().anyMatch(x -> x.conflict);
            case "excluded" -> r.excluded;
            case "text" -> (r.path + " " + r.branch + " " + r.stt + " " + r.note).toLowerCase(Locale.ROOT)
                    .contains(v.toLowerCase(Locale.ROOT));
            default -> true;
        };
    }

    public List<Row> filter(String spec) {
        return rows.stream().filter(r -> matches(r, spec)).toList();
    }

    public record Stats(String letter, int list, int done, int denied, int error, int missing, int doing, int oos, int info,
                        int qaOpen, int qaAnswered, int onlyThis) {
        public double coverage() {
            return list == 0 ? 0 : (double) done / list;
        }
    }

    public Stats stats(String letter) {
        int list = 0;
        int done = 0;
        int denied = 0;
        int error = 0;
        int missing = 0;
        int doing = 0;
        int oos = 0;
        int info = 0;
        int qaOpen = 0;
        int qaAns = 0;
        int only = 0;
        for (Row r : listRows()) {
            list++;
            Cell c = r.cells.get(letter);
            AccStatus s = c == null ? AccStatus.MISSING : c.status;
            switch (s) {
                case DONE -> done++;
                case DENIED -> denied++;
                case ERROR -> error++;
                case MISSING -> missing++;
                case DOING -> doing++;
                case OOS -> oos++;
                default -> {
                }
            }
            if (s == AccStatus.DONE && r.reached() == 1) {
                only++;
            }
        }
        for (Row r : visibleRows()) {
            for (QaRef q : r.qa) {
                if (!q.letter().equalsIgnoreCase(letter)) {
                    continue;
                }
                if (q.item().kind() == QaItem.Kind.INFO) {
                    info++;
                } else if (q.item().status().waiting()) {
                    qaOpen++;
                } else if (q.item().status() == QaItem.Status.ANSWERED) {
                    qaAns++;
                }
            }
        }
        return new Stats(letter, list, done, denied, error, missing, doing, oos, info, qaOpen, qaAns, only);
    }

    /** V_11.5: what one person (team member) did: web accounts, APIs tested, results, hand marks, questions. */
    public record PersonStats(String person, java.util.Set<String> letters, int apis, int done, int denied, int error, int manual,
                              int qa) {
    }

    public List<PersonStats> people() {
        Map<String, int[]> n = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, java.util.Set<String>> acc = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Row r : visibleRows()) {
            r.cells.forEach((letter, c) -> {
                c.byMember.forEach((person, st) -> {
                    int[] x = n.computeIfAbsent(person, k -> new int[6]);
                    acc.computeIfAbsent(person, k -> new java.util.TreeSet<>()).add(letter);
                    x[0]++;
                    x[1] += st == AccStatus.DONE ? 1 : 0;
                    x[2] += st == AccStatus.DENIED ? 1 : 0;
                    x[3] += st == AccStatus.ERROR ? 1 : 0;
                });
                if (c.manual && !c.by.isEmpty() && !c.manualVia.startsWith("Khách hàng")) {
                    n.computeIfAbsent(c.by, k -> new int[6])[4]++;
                }
            });
            for (QaRef q : r.qa) {
                if (!q.item().by().isEmpty()) {
                    n.computeIfAbsent(q.item().by(), k -> new int[6])[5]++;
                }
            }
        }
        List<PersonStats> out = new ArrayList<>();
        n.forEach((p, x) -> out.add(new PersonStats(p, acc.getOrDefault(p, java.util.Set.of()), x[0], x[1], x[2], x[3], x[4], x[5])));
        return out;
    }

    /** Rows where every account in {@code all} is Done and none in {@code none} is. */
    public int countDone(List<String> all, List<String> none) {
        int n = 0;
        for (Row r : listRows()) {
            boolean ok = true;
            for (String l : all) {
                Cell c = r.cells.get(l);
                ok &= c != null && c.status == AccStatus.DONE;
            }
            for (String l : none) {
                Cell c = r.cells.get(l);
                ok &= c == null || c.status != AccStatus.DONE;
            }
            n += ok ? 1 : 0;
        }
        return n;
    }

    /** Rows at least one account reached. */
    public int countAny() {
        int n = 0;
        for (Row r : listRows()) {
            n += r.reached() > 0 ? 1 : 0;
        }
        return n;
    }
}
