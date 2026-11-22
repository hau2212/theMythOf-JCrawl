package themythofcrawl.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActivityLog;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.model.QaItem;
import themythofcrawl.project.AccStatus;
import themythofcrawl.project.ProjectConfig;
import themythofcrawl.project.ProjectData;
import themythofcrawl.project.ProjectStore;
import themythofcrawl.project.ProjectSync;
import themythofcrawl.store.Workspaces;

/**
 * V_11.0: the project of the open workspace (its folder = the folder holding the account workspaces): settings,
 * team records, the sync loop and the data every project view reads. One per MainPanel; follows workspace switches.
 */
final class ProjectService {
    static final String PREF_SHOW_MISSING = "tmoc.tree.showMissing";

    private final MainPanel ui;
    private ProjectStore store;
    private ProjectSync sync;
    private Path projectDir;
    private volatile ApiGroup doing;
    private volatile long doingAt;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    ProjectService(MainPanel ui) {
        this.ui = ui;
        BranchTree.missingSource = this::missingForTree;
    }

    void addListener(Runnable r) {
        listeners.addIfAbsent(r);
    }

    private void fire() {
        for (Runnable r : listeners) {
            Ui.runSafe("Dự án", r);
        }
        if (showMissing() && ui.treeTab != null) {
            ui.treeTab.refreshIfVisible();
        }
    }

    // ------------------------------------------------------------------ lifecycle
    synchronized void workspaceChanged() {
        Path ws;
        try {
            ws = Path.of(ui.engine.workspaceLocation());
        } catch (RuntimeException e) {
            return;
        }
        if (Workspaces.info(ws) == null) {
            close();
            return;
        }
        Path pd = ProjectStore.projectDirOf(ws);
        if (store != null && pd.equals(projectDir)) {
            sync.syncSoon();
            return;
        }
        close();
        projectDir = pd;
        store = ProjectStore.open(pd);
        try {
            store.ensureLayout();
        } catch (RuntimeException ignored) {
            // read-only folder: views still work
        }
        ProjectConfig c = store.config();
        if (Text.blank(c.me)) {
            c.me = defaultMe();
        }
        sync = new ProjectSync(store, host);
        sync.addListener(() -> SwingUtilities.invokeLater(this::fire));
        sync.start();
        ActivityLog.add(ActivityLog.Kind.ENGINE, "Dự án: " + c.name + " (" + pd.getFileName() + ")", "");
    }

    synchronized void close() {
        if (sync != null) {
            sync.close();
            sync = null;
        }
        if (store != null) {
            try {
                store.save();
                store.saveRecords();
            } catch (RuntimeException ignored) {
                // nothing to lose: settings are saved on change
            }
        }
        store = null;
        projectDir = null;
    }

    String defaultMe() {
        String m = "";
        try {
            m = ui.engine.inventoryCatalog().memberName();
        } catch (RuntimeException ignored) {
            m = "";
        }
        return Text.blank(m) ? System.getProperty("user.name", "Tôi") : m;
    }

    boolean ready() {
        return store != null;
    }

    ProjectStore store() {
        if (store == null) {
            workspaceChanged();
        }
        return store;
    }

    ProjectSync sync() {
        if (sync == null) {
            workspaceChanged();
        }
        return sync;
    }

    ProjectConfig config() {
        return store().config();
    }

    String me() {
        ProjectStore s = store();
        return s == null || Text.blank(s.config().me) ? defaultMe() : s.config().me;
    }

    boolean canManage() {
        return store() != null && config().canManage(me());
    }

    /** Latest data (built now when the sync has not run yet). */
    ProjectData data() {
        ProjectSync s = sync();
        ProjectData d = s == null ? null : s.data();
        return d != null ? d : buildNow();
    }

    ProjectData buildNow() {
        if (store() == null) {
            return null;
        }
        ui.engine.flush();
        return ProjectData.build(store.config(), ProjectData.loadLocal(Path.of(ui.engine.workspaceLocation())), store.records(), me());
    }

    void syncSoon() {
        if (sync() != null) {
            sync.syncSoon();
        }
    }

    /** Letter of the open workspace (A, B...). */
    String myLetter() {
        Path ws = Path.of(ui.engine.workspaceLocation()).toAbsolutePath().normalize();
        ProjectData d = data();
        if (d != null) {
            for (ProjectData.LocalAccount l : d.locals()) {
                if (l.dir().toAbsolutePath().normalize().equals(ws)) {
                    return l.letter();
                }
            }
        }
        String m = Workspaces.metaValue(ws, "letter");
        return m.isEmpty() ? "A" : m;
    }

    void saveConfig(String what) {
        ProjectConfig c = config();
        c.touch(me());
        store.save();
        store.log(me(), "project", "project", what);
        syncSoon();
    }

    void mark(String rowId, String letter, AccStatus st, String note) {
        store().mark(rowId, letter, st, note, me(), "JCrawl");
        store.saveRecords();
        syncSoon();
    }

    void clearMark(String rowId, String letter) {
        store().clearMark(rowId, letter, me());
        store.saveRecords();
        syncSoon();
    }

    void note(String rowId, String note) {
        store().rowNote(rowId, note, me());
        store.saveRecords();
        syncSoon();
    }

    void setDoing(ApiGroup g) {
        doing = g;
        doingAt = System.currentTimeMillis();
    }

    /** Row id of an API of the open workspace. */
    static String rowIdOf(ApiGroup g, ProjectData d) {
        if (g == null) {
            return null;
        }
        if (g.target() != null && d != null && d.row(ProjectData.stdRowId(g.target().rowNo())) != null) {
            return ProjectData.stdRowId(g.target().rowNo());
        }
        String bk = g.bookKey();
        String keyPath = bk != null && bk.lastIndexOf('|') >= 0 ? bk.substring(bk.lastIndexOf('|') + 1) : g.path();
        return ProjectData.keyRowId(Engine.endpointKey(g.method(), keyPath));
    }

    // ------------------------------------------------------------------ tree: project APIs not called yet
    boolean showMissing() {
        return ui.uiPrefs != null && "true".equalsIgnoreCase(ui.uiPrefs.get(PREF_SHOW_MISSING));
    }

    void setShowMissing(boolean on) {
        if (ui.uiPrefs != null) {
            ui.uiPrefs.set(PREF_SHOW_MISSING, Boolean.toString(on));
        }
    }

    private Map<String, List<ProjectData.Row>> missingForTree() {
        if (!showMissing() || store == null || sync == null || sync.data() == null) {
            return null;
        }
        ProjectData d = sync.data();
        String me = myLetter();
        Map<String, List<ProjectData.Row>> out = new LinkedHashMap<>();
        for (ProjectData.Row r : d.listRows()) {
            ProjectData.Cell c = r.cells.get(me);
            if (c == null || c.status == AccStatus.MISSING && !c.manual) {
                String key = Hierarchy.key(Text.blank(r.branch) ? Hierarchy.DEFAULT_PATH : r.branch);
                out.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ sync host
    private final ProjectSync.Host host = new ProjectSync.Host() {
        @Override
        public Path workspaceDir() {
            try {
                return Path.of(ui.engine.workspaceLocation());
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public void flush() {
            ui.engine.flush();
        }

        @Override
        public boolean applyAnswer(String qaId, QaItem.Msg msg) {
            AtomicBoolean done = new AtomicBoolean(false);
            Runnable r = () -> {
                for (ApiGroup g : ui.engine.groups()) {
                    for (QaItem q : g.qa()) {
                        if (q.id().equals(qaId)) {
                            if (!msg.text().equals(q.answer())) {
                                ui.engine.putQa(g, q.withMessage(msg));
                                ui.flash("Khách hàng trả lời " + q.code() + ": " + Text.shortPath(msg.text(), 60));
                            }
                            done.set(true);
                            return;
                        }
                    }
                }
            };
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    r.run();
                } else {
                    SwingUtilities.invokeAndWait(r);
                }
            } catch (Exception e) {
                return false;
            }
            return done.get();
        }

        @Override
        public String[] doing() {
            ApiGroup g = doing;
            if (g == null || System.currentTimeMillis() - doingAt > 180_000) {
                return null;
            }
            ProjectSync s = sync;
            String rid = rowIdOf(g, s == null ? null : s.data());
            return rid == null ? null : new String[] {rid, myLetter()};
        }

        @Override
        public void changed(ProjectData d, ProjectSync.Status s) {
            // listeners fire on the EDT
        }
    };
}
