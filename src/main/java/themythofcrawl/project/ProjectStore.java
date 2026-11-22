package themythofcrawl.project;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * V_11.0 project folder (the folder holding the account workspaces, normally in the person's own OneDrive):
 * <pre>
 * &lt;project&gt;/
 *   du-an.json                     project settings ({@link ProjectConfig})
 *   &lt;workspace A&gt;/ &lt;workspace B&gt;/  the person's own account workspaces
 *   excel/BangLamViec.xlsx         the team's whole data as Excel, refreshed after every sync
 *   anh-qa/&lt;qa id&gt;/                Q&A screenshots received from the team
 *   bao-cao/noi-bo|tung-tai-khoan|qa|khach-hang/vong-N_date/{goc,chia-se}
 *   sao-luu/google-sheet/          hourly / daily snapshots of every team record
 *   sao-luu/du-an/                 daily copy of the settings + records
 *   nhat-ky/thay-doi.jsonl         who changed what, when
 *   .jcrawl-sync/records.json      every team record (the local copy of the team sheet)
 * </pre>
 */
public final class ProjectStore {
    public static final String CONFIG = "du-an.json";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMdd-HH").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Path dir;
    private ProjectConfig config;
    private final Map<String, SyncRecord> records = new LinkedHashMap<>();
    private final Map<String, SyncRecord> pending = new LinkedHashMap<>();

    private ProjectStore(Path dir, ProjectConfig config) {
        this.dir = dir;
        this.config = config;
    }

    /** The project folder of a workspace folder. */
    public static Path projectDirOf(Path workspaceDir) {
        Path p = workspaceDir.toAbsolutePath().normalize().getParent();
        return p == null ? workspaceDir.toAbsolutePath() : p;
    }

    public static ProjectStore open(Path projectDir) {
        Path f = projectDir.resolve(CONFIG);
        ProjectConfig c = null;
        if (Files.isRegularFile(f)) {
            try {
                c = ProjectConfig.fromJson(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                c = null;
            }
        }
        if (c == null) {
            c = new ProjectConfig();
            c.name = projectDir.getFileName() == null ? "Dự án" : projectDir.getFileName().toString();
        }
        ProjectStore s = new ProjectStore(projectDir, c);
        s.loadRecords();
        return s;
    }

    public Path dir() {
        return dir;
    }

    public synchronized ProjectConfig config() {
        return config;
    }

    public synchronized void replaceConfig(ProjectConfig c) {
        config = c;
    }

    public synchronized void save() {
        write(dir.resolve(CONFIG), config.toJson().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ folders
    public Path folder(String... parts) {
        Path p = dir;
        for (String s : parts) {
            p = p.resolve(s);
        }
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    public Path reportsDir(String kind) {
        return folder("bao-cao", kind);
    }

    public Path mirrorFile() {
        return folder("excel").resolve("BangLamViec.xlsx");
    }

    public Path qaImageDir(String qaId) {
        return folder("anh-qa", safe(qaId));
    }

    public Path syncDir() {
        return folder(".jcrawl-sync");
    }

    /** Creates the standard sub folders (reports by kind, backups, log). */
    public void ensureLayout() {
        for (String k : List.of("noi-bo", "tung-tai-khoan", "qa", "khach-hang")) {
            reportsDir(k);
        }
        folder("sao-luu", "google-sheet");
        folder("sao-luu", "du-an");
        folder("nhat-ky");
        folder("excel");
        folder("anh-qa");
    }

    public static String safe(String s) {
        String t = Text.nz(s).replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").trim();
        return t.isEmpty() ? "_" : (t.length() > 80 ? t.substring(0, 80) : t);
    }

    // ------------------------------------------------------------------ records
    private void loadRecords() {
        Path f = dir.resolve(".jcrawl-sync").resolve("records.json");
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            for (Object o : Json.list(root, "records")) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    SyncRecord r = SyncRecord.fromMap((Map<String, Object>) m);
                    if (!r.id().isEmpty()) {
                        records.put(r.id(), r);
                    }
                }
            }
            for (Object o : Json.list(root, "pending")) {
                SyncRecord r = records.get(String.valueOf(o));
                if (r != null) {
                    pending.put(r.id(), r);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // a broken cache is rebuilt by the next pull
        }
    }

    public synchronized void saveRecords() {
        List<Object> list = new ArrayList<>();
        for (SyncRecord r : records.values()) {
            list.add(r.toMap());
        }
        write(syncDir().resolve("records.json"), Json.write(Map.of("records", list, "pending", new ArrayList<>(pending.keySet())))
                .getBytes(StandardCharsets.UTF_8));
    }

    public synchronized Map<String, SyncRecord> records() {
        return new LinkedHashMap<>(records);
    }

    public synchronized SyncRecord record(String id) {
        return records.get(id);
    }

    /** Keeps the newer of each record; returns the records that changed. */
    public synchronized List<SyncRecord> merge(Collection<SyncRecord> incoming) {
        List<SyncRecord> changed = new ArrayList<>();
        for (SyncRecord r : incoming) {
            if (r == null || r.id().isEmpty()) {
                continue;
            }
            SyncRecord cur = records.get(r.id());
            if (cur == null || r.at() > cur.at() || (r.at() == cur.at() && !r.sameContent(cur))) {
                records.put(r.id(), r);
                if (cur == null || !cur.sameContent(r)) {
                    changed.add(r);
                }
            }
        }
        return changed;
    }

    /** A change made here (mark, note, answer...): stored now, pushed by the next sync. */
    public synchronized SyncRecord putLocal(SyncRecord r) {
        SyncRecord cur = records.get(r.id());
        SyncRecord withBase = r.withBase(cur == null ? 0 : cur.at());
        records.put(r.id(), withBase);
        pending.put(r.id(), withBase);
        log(r.by(), r.type(), r.id(), Json.write(new LinkedHashMap<>(r.data())));
        return withBase;
    }

    public synchronized List<SyncRecord> pending() {
        return new ArrayList<>(pending.values());
    }

    public synchronized void pushed(Collection<SyncRecord> stamped) {
        for (SyncRecord r : stamped) {
            pending.remove(r.id());
            SyncRecord cur = records.get(r.id());
            if (cur == null || r.at() >= cur.at()) {
                records.put(r.id(), r);
            }
        }
    }

    // ------------------------------------------------------------------ marks
    public static String markId(String rowId, String letter) {
        return SyncRecord.MARK + ":" + rowId + ":" + letter;
    }

    public SyncRecord mark(String rowId, String letter, AccStatus st, String note, String by, String via) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("row", rowId);
        d.put("status", st == null ? "" : st.name());
        d.put("note", Text.nz(note));
        d.put("via", Text.nz(via));
        return putLocal(SyncRecord.of(markId(rowId, letter), by, letter, d, by));
    }

    public SyncRecord clearMark(String rowId, String letter, String by) {
        SyncRecord r = new SyncRecord(markId(rowId, letter), SyncRecord.MARK, by, letter, Map.of("row", rowId), System.currentTimeMillis(),
                by, true, 0L);
        return putLocal(r);
    }

    public SyncRecord rowNote(String rowId, String note, String by) {
        return putLocal(SyncRecord.of(SyncRecord.NOTE + ":" + rowId, by, "", Map.of("row", rowId, "note", Text.nz(note)), by));
    }

    // ------------------------------------------------------------------ log
    public void log(String by, String kind, String id, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", TS.format(Instant.now()));
        m.put("by", Text.nz(by));
        m.put("k", Text.nz(kind));
        m.put("id", Text.nz(id));
        m.put("d", Text.nz(detail).length() > 400 ? detail.substring(0, 400) : Text.nz(detail));
        try {
            Path f = folder("nhat-ky").resolve("thay-doi.jsonl");
            Files.writeString(f, Json.write(m) + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | UncheckedIOException ignored) {
            // the log never blocks work
        }
    }

    /** Last n log entries (newest last). */
    public List<Map<String, Object>> logTail(int n) {
        Path f = dir.resolve("nhat-ky").resolve("thay-doi.jsonl");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (String l : lines.subList(Math.max(0, lines.size() - n), lines.size())) {
                try {
                    out.add(Json.parseObject(l));
                } catch (RuntimeException ignored) {
                    // skip a broken line
                }
            }
        } catch (IOException ignored) {
            // empty
        }
        return out;
    }

    // ------------------------------------------------------------------ backups
    /** Snapshot of every record: hourly (kept 7 days) and daily (kept 30 days). Returns the file written, or null. */
    public synchronized Path snapshot(boolean force) {
        Instant now = Instant.now();
        Path hourly = folder("sao-luu", "google-sheet").resolve("gio-" + HOUR.format(now) + ".json.gz");
        Path daily = folder("sao-luu", "google-sheet").resolve("ngay-" + DAY.format(now) + ".json.gz");
        byte[] data = gzip(Json.write(snapshotMap()).getBytes(StandardCharsets.UTF_8));
        Path written = null;
        if (force || !Files.exists(hourly)) {
            write(force ? folder("sao-luu", "google-sheet").resolve("tay-" + HOUR.format(now) + "-" + System.currentTimeMillis() % 100000
                    + ".json.gz") : hourly, data);
            written = hourly;
        }
        if (!Files.exists(daily)) {
            write(daily, data);
            write(folder("sao-luu", "du-an").resolve("du-an-" + DAY.format(now) + ".json"),
                    config.toJson().getBytes(StandardCharsets.UTF_8));
        }
        prune(folder("sao-luu", "google-sheet"), "gio-", 7 * 24);
        prune(folder("sao-luu", "google-sheet"), "ngay-", 30);
        prune(folder("sao-luu", "du-an"), "du-an-", 30);
        return written;
    }

    private Map<String, Object> snapshotMap() {
        List<Object> list = new ArrayList<>();
        for (SyncRecord r : records.values()) {
            list.add(r.toMap());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", config.id);
        m.put("at", System.currentTimeMillis());
        m.put("config", config.toMap(true));
        m.put("records", list);
        return m;
    }

    public record Snapshot(Path file, String label, long at) {
    }

    public List<Snapshot> snapshots() {
        List<Snapshot> out = new ArrayList<>();
        Path d = dir.resolve("sao-luu").resolve("google-sheet");
        if (!Files.isDirectory(d)) {
            return out;
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(d, "*.json.gz")) {
            for (Path p : s) {
                long t = Files.getLastModifiedTime(p).toMillis();
                String n = p.getFileName().toString();
                String kind = n.startsWith("ngay-") ? "Theo ngày" : n.startsWith("tay-") ? "Sao lưu tay" : "Theo giờ";
                out.add(new Snapshot(p, kind + " · " + TS.format(Instant.ofEpochMilli(t)), t));
            }
        } catch (IOException ignored) {
            // none
        }
        out.sort(Comparator.comparingLong(Snapshot::at).reversed());
        return out;
    }

    /** Records of a snapshot file. */
    public static Map<String, SyncRecord> readSnapshot(Path file) throws IOException {
        Map<String, SyncRecord> out = new LinkedHashMap<>();
        byte[] raw = Files.readAllBytes(file);
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
            Map<String, Object> m = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            for (Object o : Json.list(m, "records")) {
                if (o instanceof Map<?, ?> rm) {
                    @SuppressWarnings("unchecked")
                    SyncRecord r = SyncRecord.fromMap((Map<String, Object>) rm);
                    out.put(r.id(), r);
                }
            }
        }
        return out;
    }

    private static void prune(Path d, String prefix, int keep) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(d, prefix + "*")) {
            for (Path p : s) {
                files.add(p);
            }
        } catch (IOException ignored) {
            return;
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (int i = 0; i < files.size() - keep; i++) {
            try {
                Files.deleteIfExists(files.get(i));
            } catch (IOException ignored) {
                // next time
            }
        }
    }

    private static byte[] gzip(byte[] b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(bo)) {
            z.write(b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bo.toByteArray();
    }

    /** Write to a temp file then move (a crash or OneDrive never sees half a file). */
    public static void write(Path f, byte[] data) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Folder of the next client round: bao-cao/khach-hang/vong-N_yyyyMMdd. */
    public Path nextRoundDir() {
        Path base = reportsDir("khach-hang");
        int n = 1;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(base, "vong-*")) {
            for (Path p : s) {
                String name = p.getFileName().toString();
                int us = name.indexOf('_');
                try {
                    n = Math.max(n, Integer.parseInt(name.substring(5, us < 0 ? name.length() : us)) + 1);
                } catch (RuntimeException ignored) {
                    // not a round folder
                }
            }
        } catch (IOException ignored) {
            // first round
        }
        return base.resolve("vong-" + n + "_" + DAY.format(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant()));
    }

    public List<Path> rounds() {
        List<Path> out = new ArrayList<>();
        Path base = dir.resolve("bao-cao").resolve("khach-hang");
        if (!Files.isDirectory(base)) {
            return out;
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(base, "vong-*")) {
            for (Path p : s) {
                if (Files.isDirectory(p)) {
                    out.add(p);
                }
            }
        } catch (IOException ignored) {
            // none
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    public static String time(long ms) {
        return ms <= 0 ? "" : TS.format(Instant.ofEpochMilli(ms));
    }
}
