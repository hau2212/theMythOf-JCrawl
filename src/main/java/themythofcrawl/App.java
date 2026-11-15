package themythofcrawl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import themythofcrawl.capture.CaptureService;
import themythofcrawl.capture.CaptureSettings;
import themythofcrawl.excel.TargetList;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.CapturedApi;
import themythofcrawl.workspace.Workspace;
import themythofcrawl.workspace.WorkspaceManager;
import themythofcrawl.workspace.WorkspaceStore;

/**
 * Application controller, independent of Burp: opens / switches workspaces, remembers the last one
 * per Burp project, feeds captures, imports the Excel API list and exports reports.
 */
public final class App {
    /** Small string store: Burp preferences (global) or the Burp project file (Pro). */
    public interface KeyValue {
        String get(String key);

        void set(String key, String value);
    }

    public static final String PREF_ROOT = "tmoc.workspaceRoot";
    public static final String PREF_LAST_PREFIX = "tmoc.lastWorkspace.";
    public static final String PREF_LAST_ANY = "tmoc.lastWorkspace";
    public static final String PROJECT_LINK = "tmoc.workspaceDir";

    private final WorkspaceManager manager;
    private final CaptureService capture;
    private final KeyValue prefs;
    private final KeyValue projectData;
    private final String projectName;
    private final String projectId;
    private final Consumer<String> log;
    private final ExecutorService captureExecutor;
    private volatile TargetList targets;

    public App(Path root, KeyValue prefs, KeyValue projectData, String projectName, String projectId,
               Consumer<String> log, Consumer<String> error) {
        this.prefs = prefs;
        this.projectData = projectData;
        this.projectName = projectName == null || projectName.isBlank() ? "Burp project" : projectName;
        this.projectId = projectId == null ? this.projectName : projectId;
        this.log = log;
        this.manager = new WorkspaceManager(new WorkspaceStore(root), error);
        this.capture = new CaptureService(manager, () -> targets, log);
        this.captureExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "theMythOfCrawl-capture");
            t.setDaemon(true);
            return t;
        });
        manager.addListener(this::onSwitched);
    }

    /** Root folder: the one the user picked before, else next to the extension jar. */
    public static Path resolveRoot(KeyValue prefs, String extensionFile) {
        String chosen = prefs.get(PREF_ROOT);
        if (chosen != null && !chosen.isBlank()) {
            return Paths.get(chosen);
        }
        return WorkspaceStore.defaultRoot(extensionFile);
    }

    public WorkspaceManager manager() {
        return manager;
    }

    public WorkspaceStore store() {
        return manager.store();
    }

    public CaptureService capture() {
        return capture;
    }

    public Workspace workspace() {
        return manager.current();
    }

    public String projectName() {
        return projectName;
    }

    public TargetList targets() {
        return targets;
    }

    /**
     * Opens, in order: the workspace linked to this Burp project (Pro project file), the last one used
     * with this project, the last one used at all, else "Default" under this project.
     */
    public Workspace start() throws IOException {
        for (String candidate : new String[] {
                projectData == null ? null : projectData.get(PROJECT_LINK),
                prefs.get(PREF_LAST_PREFIX + projectId),
                prefs.get(PREF_LAST_ANY)}) {
            if (candidate != null && !candidate.isBlank()
                    && Files.isRegularFile(Paths.get(candidate).resolve(WorkspaceStore.META))) {
                try {
                    return manager.open(Paths.get(candidate));
                } catch (IOException | RuntimeException e) {
                    log.accept("Không mở được workspace " + candidate + ": " + e.getMessage());
                }
            }
        }
        return manager.openOrCreate(projectName, "Default");
    }

    private void onSwitched(Workspace w) {
        String dir = w.dir().toAbsolutePath().toString();
        prefs.set(PREF_LAST_PREFIX + projectId, dir);
        prefs.set(PREF_LAST_ANY, dir);
        if (projectData != null) {
            projectData.set(PROJECT_LINK, dir);
        }
        TargetList t = null;
        if (w.targetsJson() != null && !w.targetsJson().isBlank()) {
            try {
                t = TargetList.fromJson(w.targetsJson());
            } catch (RuntimeException e) {
                log.accept("Danh sách Excel trong workspace bị lỗi, bỏ qua: " + e.getMessage());
            }
        }
        targets = t;
        log.accept("Đang dùng workspace " + w.displayName() + " (" + w.apiCount() + " API) - " + dir);
    }

    public Workspace createWorkspace(String project, String name) throws IOException {
        Workspace w = store().create(project, name);
        manager.switchTo(w);
        return w;
    }

    public void openWorkspace(Path dir) throws IOException {
        manager.open(dir);
    }

    public void deleteWorkspace(Path dir) throws IOException {
        Workspace w = store().open(dir);
        if (workspace() != null && workspace().dir().equals(w.dir())) {
            throw new IOException("Không xóa workspace đang mở - hãy mở workspace khác trước.");
        }
        store().delete(w);
    }

    public void changeRoot(Path root) {
        prefs.set(PREF_ROOT, root.toAbsolutePath().toString());
    }

    // ---- settings of the current workspace
    public CaptureSettings settings() {
        return CaptureService.settingsOf(workspace());
    }

    public void updateSettings(CaptureSettings s) {
        Workspace w = workspace();
        if (w != null) {
            w.putSettings(s.toMap());
        }
    }

    public void setActiveBranch(String branch) {
        Workspace w = workspace();
        if (w != null) {
            w.setActiveBranch(branch);
        }
    }

    // ---- capture
    public void submit(CaptureService.Message m, boolean applyFilters) {
        captureExecutor.execute(() -> {
            CaptureService.Result r = capture.capture(m, applyFilters);
            if (r.api() != null) {
                CapturedApi a = r.api();
                log.accept("Bắt từ " + m.source() + ": " + a.label() + " " + a.method + " " + a.url()
                        + (a.targetStt != null ? " (khớp dòng STT " + a.targetStt + " trong file Excel)" : ""));
            } else if (!applyFilters || "Manual".equals(m.source())) {
                log.accept("Bỏ qua " + m.method() + " " + m.path() + ": " + r.skipReason());
            }
        });
    }

    public void deleteApis(Set<Integer> ids) {
        Workspace w = workspace();
        if (w == null) {
            return;
        }
        w.removeApis(ids);
        for (Integer id : ids) {
            store().deleteMessages(w, id);
        }
    }

    // ---- Excel
    public TargetList loadTargets(Path file, int sheetIndex) throws IOException {
        Xlsx.Sheet sheet = Xlsx.read(file, sheetIndex);
        TargetList t = TargetList.fromRows(sheet.rows(), file.getFileName().toString(), sheet.name());
        if (t.size() == 0) {
            throw new IOException("Không tìm thấy dòng API nào. File cần cột PATH (hoặc URL / Endpoint) "
                    + "chứa đường dẫn bắt đầu bằng '/'.");
        }
        workspace().setTargetsJson(t.toJson());
        targets = t;
        retagApis();
        log.accept("Đã nạp " + t.size() + " API từ " + t.source() + " (sheet " + t.sheet() + ").");
        return t;
    }

    public void clearTargets() {
        workspace().setTargetsJson(null);
        targets = null;
        retagApis();
    }

    /** Re-match every captured API against the current list (after load / option change). */
    public void retagApis() {
        Workspace w = workspace();
        if (w == null) {
            return;
        }
        boolean prefix = settings().targetAllowPrefix;
        for (CapturedApi a : w.apis()) {
            TargetList.Entry e = targets == null ? null : targets.match(a.method, a.path, prefix);
            a.targetStt = e == null ? null : e.stt;
        }
        w.touch();
    }

    /** Coverage: for each Excel row, the captured APIs that matched it. */
    public Map<TargetList.Entry, List<CapturedApi>> coverage() {
        Map<TargetList.Entry, List<CapturedApi>> out = new LinkedHashMap<>();
        TargetList t = targets;
        if (t == null || workspace() == null) {
            return out;
        }
        Map<String, List<CapturedApi>> byStt = new LinkedHashMap<>();
        for (CapturedApi a : workspace().apis()) {
            if (a.targetStt != null) {
                byStt.computeIfAbsent(a.targetStt, k -> new ArrayList<>()).add(a);
            }
        }
        for (TargetList.Entry e : t.entries()) {
            out.put(e, byStt.getOrDefault(e.stt, List.of()));
        }
        return out;
    }

    public byte[] exportWorkbook() throws IOException {
        Workspace w = workspace();
        List<Xlsx.OutSheet> sheets = new ArrayList<>();
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        TargetList t = targets;
        if (t != null && t.size() > 0) {
            List<List<Object>> rows = new ArrayList<>();
            for (Map.Entry<TargetList.Entry, List<CapturedApi>> e : coverage().entrySet()) {
                TargetList.Entry row = e.getKey();
                List<CapturedApi> found = e.getValue();
                List<String> positions = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                for (CapturedApi a : found) {
                    if (!positions.contains(a.branch)) {
                        positions.add(a.branch);
                    }
                    labels.add(a.label());
                }
                CapturedApi first = found.isEmpty() ? null : found.get(0);
                List<Object> cells = new ArrayList<>();
                cells.add(row.stt);
                cells.add(row.methodLabel());
                cells.add(row.path);
                cells.add(!row.position.isEmpty() ? row.position : (positions.isEmpty() ? null : String.join("; ", positions)));
                cells.add(found.isEmpty() ? "Chưa bắt" : "Đã bắt");
                cells.add(found.size());
                cells.add(labels.isEmpty() ? null : String.join(", ", labels));
                cells.add(first == null ? null : first.pathWithoutQuery());
                cells.add(positions.isEmpty() ? null : String.join("; ", positions));
                rows.add(cells);
            }
            List<String> header = new ArrayList<>(t.headers());
            header.addAll(List.of("Trạng thái", "Số lần bắt", "API #", "Path thực tế", "Vị trí tìm thấy"));
            sheets.add(new Xlsx.OutSheet("Độ phủ", header, rows, new int[] {6, 12, 48, 44, 12, 11, 12, 48, 44}));
        }
        List<List<Object>> rows = new ArrayList<>();
        int stt = 1;
        for (CapturedApi a : w.apis()) {
            rows.add(new ArrayList<>(java.util.Arrays.asList(stt++, a.method, a.pathWithoutQuery(), a.branch, a.host,
                    a.url(), a.origStatus >= 0 ? (Object) a.origStatus : null, a.targetStt, a.source,
                    time.format(new Date(a.createdMillis)))));
        }
        sheets.add(new Xlsx.OutSheet("API", List.of("STT", "Method", "Path", "Vị trí tìm thấy", "Host", "URL đầy đủ",
                "Status gốc", "STT trong file Excel", "Nguồn", "Thời gian"), rows,
                new int[] {6, 9, 48, 44, 26, 70, 11, 12, 9, 20}));
        return Xlsx.write(sheets);
    }

    public void shutdown() {
        captureExecutor.shutdownNow();
        manager.shutdown();
    }
}
