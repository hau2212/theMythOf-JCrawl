package themythofcrawl.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * Cụm hiển thị (Visual Cluster): gom nhiều API Group trong cùng một nhánh lại để thu gọn / mở rộng
 * cho gọn mắt mà TUYỆT ĐỐI KHÔNG làm thay đổi đường dẫn nhánh ({@link ApiGroup#branch()}), STT copy,
 * vị trí Excel hay bộ nhớ giữ chỗ.
 */
public final class GroupCluster {
    private final String id;
    private volatile String branchPath;
    private volatile String name;
    private volatile String colorKey;
    private volatile boolean collapsed;
    private final LinkedHashSet<Integer> gids = new LinkedHashSet<>();

    public GroupCluster(String id, String branchPath, String name, String colorKey, boolean collapsed, Collection<Integer> gids) {
        this.id = Text.blank(id) ? "CL-" + System.nanoTime() : id.trim();
        this.branchPath = Hierarchy.normalize(branchPath);
        this.name = Text.blank(name) ? "Cụm API" : name.trim();
        this.colorKey = Text.blank(colorKey) ? "accent" : colorKey.trim();
        this.collapsed = collapsed;
        if (gids != null) {
            for (Integer g : gids) {
                if (g != null && g > 0) {
                    this.gids.add(g);
                }
            }
        }
    }

    public String id() { return id; }
    public String branchPath() { return branchPath; }
    public String branchKey() { return Hierarchy.key(branchPath); }
    public String name() { return name; }
    public String colorKey() { return colorKey; }
    public boolean collapsed() { return collapsed; }

    public void setBranchPath(String path) {
        this.branchPath = Hierarchy.normalize(path);
    }

    public void setName(String name) {
        if (!Text.blank(name)) {
            this.name = name.trim();
        }
    }

    public void setColorKey(String colorKey) {
        if (!Text.blank(colorKey)) {
            this.colorKey = colorKey.trim();
        }
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }

    public synchronized Set<Integer> gids() {
        return new LinkedHashSet<>(gids);
    }

    public synchronized boolean contains(int gid) {
        return gids.contains(gid);
    }

    public synchronized boolean addGid(int gid) {
        return gid > 0 && gids.add(gid);
    }

    public synchronized boolean removeGid(int gid) {
        return gids.remove(gid);
    }

    public synchronized boolean isEmpty() {
        return gids.isEmpty();
    }

    public synchronized int size() {
        return gids.size();
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("branch", branchPath);
        m.put("name", name);
        m.put("color", colorKey);
        m.put("collapsed", collapsed);
        m.put("gids", new ArrayList<>(gids));
        return m;
    }

    public static GroupCluster fromMap(Map<String, Object> m) {
        List<Integer> gs = new ArrayList<>();
        for (Object o : Json.list(m, "gids")) {
            if (o instanceof Number n) {
                gs.add(n.intValue());
            }
        }
        return new GroupCluster(
                Json.str(m, "id", "CL-1"),
                Json.str(m, "branch", Hierarchy.DEFAULT_PATH),
                Json.str(m, "name", "Cụm API"),
                Json.str(m, "color", "accent"),
                Json.bool(m, "collapsed", false),
                gs
        );
    }
}
