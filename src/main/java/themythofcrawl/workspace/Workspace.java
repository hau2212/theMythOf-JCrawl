package themythofcrawl.workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import themythofcrawl.model.CapturedApi;
import themythofcrawl.model.HierarchyPath;
import themythofcrawl.util.Json;

/**
 * Everything captured for one engagement: branches, captured APIs (bytes on disk), dedup keys,
 * settings and the imported Excel list. Thread-safe; every change marks it dirty for the autosaver.
 */
public final class Workspace {
    public static final int FORMAT_VERSION = 1;

    private final Path dir;
    private final String project;
    private final String name;
    private final long created;
    private final List<String> branches = new ArrayList<>();
    private final List<CapturedApi> apis = new ArrayList<>();
    private final Set<String> seenKeys = new HashSet<>();
    private final Map<String, Object> settings = new LinkedHashMap<>();
    private String activeBranch;
    private String targetsJson;
    private int nextId = 1;
    private volatile boolean dirty;
    private volatile long revision;

    Workspace(Path dir, String project, String name, long created) {
        this.dir = dir;
        this.project = project;
        this.name = name;
        this.created = created;
        this.activeBranch = HierarchyPath.DEFAULT;
        this.branches.add(HierarchyPath.DEFAULT);
    }

    public Path dir() {
        return dir;
    }

    public String project() {
        return project;
    }

    public String name() {
        return name;
    }

    public long created() {
        return created;
    }

    public String displayName() {
        return project + " / " + name;
    }

    private void changed() {
        dirty = true;
        revision++;
    }

    public boolean isDirty() {
        return dirty;
    }

    void markClean() {
        dirty = false;
    }

    /** Increases on every change: lets views refresh only when something happened. */
    public long revision() {
        return revision;
    }

    // ---- APIs
    public synchronized int reserveId() {
        changed();
        return nextId++;
    }

    public synchronized void addApi(CapturedApi api) {
        apis.add(api);
        if (!containsBranch(api.branch)) {
            branches.add(api.branch);
        }
        changed();
    }

    public synchronized List<CapturedApi> apis() {
        return Collections.unmodifiableList(new ArrayList<>(apis));
    }

    public synchronized int apiCount() {
        return apis.size();
    }

    public synchronized void removeApis(Set<Integer> ids) {
        if (apis.removeIf(a -> ids.contains(a.id))) {
            changed();
        }
    }

    public synchronized void clearApis() {
        apis.clear();
        seenKeys.clear();
        changed();
    }

    public void touch() {
        changed();
    }

    /** False when the key was already seen (endpoint captured before). */
    public synchronized boolean markSeen(String key) {
        boolean added = seenKeys.add(key);
        if (added) {
            changed();
        }
        return added;
    }

    public synchronized void resetSeen() {
        seenKeys.clear();
        changed();
    }

    // ---- branches
    public synchronized String activeBranch() {
        return activeBranch;
    }

    public synchronized void setActiveBranch(String branch) {
        String b = HierarchyPath.normalize(branch);
        activeBranch = b;
        if (!containsBranch(b)) {
            branches.add(b);
        }
        changed();
    }

    public synchronized List<String> branches() {
        return Collections.unmodifiableList(new ArrayList<>(branches));
    }

    private boolean containsBranch(String b) {
        for (String x : branches) {
            if (x.equalsIgnoreCase(b)) {
                return true;
            }
        }
        return false;
    }

    // ---- settings / Excel list (stored as given; interpreted by the caller)
    public synchronized Map<String, Object> settings() {
        return new LinkedHashMap<>(settings);
    }

    public synchronized void putSettings(Map<String, Object> values) {
        settings.putAll(values);
        changed();
    }

    public synchronized String targetsJson() {
        return targetsJson;
    }

    public synchronized void setTargetsJson(String json) {
        targetsJson = json;
        changed();
    }

    // ---- (de)serialisation of workspace.json
    synchronized Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT_VERSION);
        m.put("tool", "theMythOfCrawl");
        m.put("project", project);
        m.put("name", name);
        m.put("created", created);
        m.put("saved", System.currentTimeMillis());
        m.put("nextId", nextId);
        m.put("activeBranch", activeBranch);
        m.put("branches", new ArrayList<>(branches));
        List<Object> list = new ArrayList<>();
        for (CapturedApi a : apis) {
            list.add(a.toJson());
        }
        m.put("apis", list);
        m.put("seen", new ArrayList<>(seenKeys));
        m.put("settings", new LinkedHashMap<>(settings));
        m.put("targets", targetsJson);
        return m;
    }

    static Workspace fromJson(Path dir, Map<String, Object> m, String fallbackProject, String fallbackName) {
        Workspace w = new Workspace(dir, Json.str(m, "project", fallbackProject), Json.str(m, "name", fallbackName),
                Json.num(m, "created", System.currentTimeMillis()));
        w.branches.clear();
        for (Object b : Json.list(m, "branches")) {
            if (b != null) {
                w.branches.add(HierarchyPath.normalize(b.toString()));
            }
        }
        for (Object o : Json.list(m, "apis")) {
            if (o instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> am = (Map<String, Object>) o;
                CapturedApi api = CapturedApi.fromJson(am);
                w.apis.add(api);
                if (!w.containsBranch(api.branch)) {
                    w.branches.add(api.branch);
                }
            }
        }
        for (Object k : Json.list(m, "seen")) {
            if (k != null) {
                w.seenKeys.add(k.toString());
            }
        }
        w.settings.putAll(Json.obj(m, "settings"));
        w.targetsJson = Json.str(m, "targets", null);
        int maxId = 0;
        for (CapturedApi a : w.apis) {
            maxId = Math.max(maxId, a.id);
        }
        w.nextId = (int) Math.max(Json.num(m, "nextId", 1), maxId + 1L);
        w.activeBranch = HierarchyPath.normalize(Json.str(m, "activeBranch", HierarchyPath.DEFAULT));
        if (!w.containsBranch(w.activeBranch)) {
            w.branches.add(w.activeBranch);
        }
        w.dirty = false;
        return w;
    }
}
