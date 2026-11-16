package themythofcrawl.ui;

import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
import java.util.function.Consumer;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.model.PlaceHint;
import themythofcrawl.model.RoleResult;
import themythofcrawl.place.Decisions;
import themythofcrawl.place.PlacementBook;
import themythofcrawl.ui.kit.ThemedTree;

/**
 * The branch tree (Toppage > menu > button "Search" > API > API gốc / roles). Rebuilt from the engine
 * when something changed, keeping what was open and selected; API rows load their children only when
 * opened. Several rows can be selected (Ctrl / Shift + click).
 */
public final class BranchTree {
    public enum Chip { COPY, EXCEL, DUP }

    /** Clicks on the copy glyph / [Excel #STT] / duplicate badge [#n/m] of an API row. */
    public interface ChipHandler {
        void chip(Chip chip, ApiGroup g, JTree tree, int x, int y);
    }

    private final Engine engine;
    public final JTree tree;
    public final DefaultTreeModel model;
    private final Set<Object> collapsed = new HashSet<>();
    private final Set<Object> expanded = new HashSet<>();
    private boolean restoring;
    private ChipHandler chips;

    public BranchTree(Engine engine) {
        this.engine = engine;
        Node root = new Node(Node.Kind.ROOT, "", "Tất cả Group & API", AuditLevel.NONE, "ROOT", 0);
        model = new DefaultTreeModel(root);
        tree = new ThemedTree(model, new TreeRenderer());
        tree.setRowHeight(themythofcrawl.ui.theme.Theme.ROW_TREE);
        tree.setLargeModel(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setToggleClickCount(2);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                Node n = (Node) e.getPath().getLastPathComponent();
                if (!n.loaded) {
                    loadChildren(n);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {
            }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent e) {
                if (!restoring) {
                    Node n = (Node) e.getPath().getLastPathComponent();
                    collapsed.remove(n.key);
                    expanded.add(n.key);
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                if (!restoring) {
                    Node n = (Node) e.getPath().getLastPathComponent();
                    expanded.remove(n.key);
                    collapsed.add(n.key);
                }
            }
        });
        MouseAdapter chipMouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 1 || !SwingUtilities.isLeftMouseButton(e) || chips == null) {
                    return;
                }
                Object[] z = chipAt(e.getX(), e.getY());
                if (z != null) {
                    chips.chip((Chip) z[0], (ApiGroup) z[1], tree, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                tree.setCursor(Cursor.getPredefinedCursor(chipAt(e.getX(), e.getY()) != null ? Cursor.HAND_CURSOR
                        : Cursor.DEFAULT_CURSOR));
            }
        };
        tree.addMouseListener(chipMouse);
        tree.addMouseMotionListener(chipMouse);
    }

    public void onChip(ChipHandler h) {
        chips = h;
    }

    public boolean restoring() {
        return restoring;
    }

    /** Keep an API open in this tree (a returning API shows its 'vị trí cũ / vừa gặp tại' rows). */
    public void expandApi(int gid) {
        expanded.add("G" + gid);
    }

    // ------------------------------------------------------------------ labels
    public static String rolesLabel(ApiGroup g) {
        return g.results().isEmpty() ? "API gốc" : "1 gốc + " + g.results().size() + " role";
    }

    public static String excelBadge(ApiGroup g) {
        return g.target() == null || g.target().sttText().isBlank() ? "" : "[Excel #" + g.target().sttText() + "]";
    }

    public static String dupBadge(ApiGroup g) {
        return g.dupTotal() <= 1 ? "" : "[#" + g.dupNo() + "/" + g.dupTotal() + "]";
    }

    public static String groupLabel(ApiGroup g) {
        String badge = excelBadge(g);
        String dup = dupBadge(g);
        String summary = Audit.summary(g);
        String text = Text.apiLabel(g.gid()) + (dup.isEmpty() ? "" : " " + dup) + "   ·   " + g.method() + " "
                + (badge.isEmpty() ? "" : badge + " ") + Text.shortPath(g.path(), 48) + "   ·   " + rolesLabel(g);
        if (g.pending()) {
            text = text + "   ·   " + Icons.stripLeadingSymbol(Decisions.pendingLabel(g));
        }
        if (summary != null && !summary.startsWith("API gốc")) {
            text += "   ·   " + summary;
        } else if (summary != null && g.results().isEmpty() && summary.contains("(")) {
            text += "   ·   " + summary.substring(summary.indexOf('('));
        }
        return text;
    }

    public static String resultLabel(ApiGroup g, RoleResult r) {
        Object st = r.status() != null ? r.status() : r.state();
        String len = r.length() != null ? r.length() + " B" : "-";
        return r.roleName() + "   ·   " + st + "   ·   " + len + "   ·   " + Audit.badge(g, r);
    }

    // ------------------------------------------------------------------ build
    /** Rebuild from the engine, keeping open folders / APIs and the selection. */
    public void rebuild(boolean showGhosts) {
        List<Object> selected = new ArrayList<>();
        TreePath[] sel = tree.getSelectionPaths();
        if (sel != null) {
            for (TreePath p : sel) {
                selected.add(((Node) p.getLastPathComponent()).key);
            }
        }
        List<ApiGroup> groups = engine.groups();
        List<Branch> branches = engine.branches();
        Map<String, int[]> counts = Audit.prefixCounts(groups);
        Map<String, Integer> pend = new HashMap<>();
        for (ApiGroup g : groups) {
            if (g.pending()) {
                List<String> parts = Hierarchy.split(g.branch());
                for (int i = 1; i <= parts.size(); i++) {
                    pend.merge(Hierarchy.key(parts.subList(0, i)), 1, Integer::sum);
                }
            }
        }
        Map<String, List<PlacementBook.Record>> ghosts = new LinkedHashMap<>();
        Map<String, Integer> ghostCount = new HashMap<>();
        if (showGhosts) {
            for (PlacementBook.Record r : engine.book().records()) {
                if (r.gid == null && r.place != null) {
                    ghosts.computeIfAbsent(Hierarchy.key(r.place), k -> new ArrayList<>()).add(r);
                    List<String> parts = Hierarchy.split(r.place);
                    for (int i = 1; i <= parts.size(); i++) {
                        ghostCount.merge(Hierarchy.key(parts.subList(0, i)), 1, Integer::sum);
                    }
                }
            }
        }
        Node.FolderMeta rootMeta = new Node.FolderMeta("Tất cả Group & API", null,
                branches.size() + " Nhánh · " + groups.size() + " API", null, null, null);
        Node root = new Node(Node.Kind.ROOT, "", "Tất cả Group & API (" + branches.size() + " Nhánh | " + groups.size() + " API)",
                AuditLevel.NONE, "ROOT", 0, rootMeta);
        Map<Object, Node> nodes = new HashMap<>();
        nodes.put(root.key, root);
        Set<Node> open = new LinkedHashSet<>();
        open.add(root);
        List<Node> expand = new ArrayList<>();
        Map<Node, Boolean> reachable = new HashMap<>();
        reachable.put(root, true);
        Consumer<String> ensure = path -> ensureFolder(root, Hierarchy.split(path), nodes, counts, pend, ghostCount, reachable,
                expand);
        Set<String> paths = new LinkedHashSet<>();
        for (Branch b : branches) {
            paths.add(Hierarchy.normalize(b.name()));
        }
        for (ApiGroup g : groups) {
            paths.add(Hierarchy.normalize(g.branch()));
        }
        paths.forEach(ensure);
        for (ApiGroup g : groups) {
            Node leaf = nodes.get("F" + Hierarchy.key(g.branch()));
            Node n = new Node(Node.Kind.GROUP, g, Text.apiLabel(g.gid()) + " " + g.method() + " " + Text.shortPath(g.path(), 48),
                    Audit.display(g), "G" + g.gid(), 0);
            nodes.put(n.key, n);
            if (Boolean.TRUE.equals(reachable.get(leaf)) && expanded.contains(n.key)) {
                fillGroup(n, nodes);
                expand.add(n);
            } else {
                n.lazy();
            }
            leaf.add(n);
        }
        ghosts.forEach((fkey, recs) -> {
            Node folder = nodes.get("F" + fkey);
            if (folder == null) {
                return;
            }
            Node gn = new Node(Node.Kind.GHOSTS, recs, recs.size()
                    + " API đã xóa từng nằm ở đây (giữ chỗ - bắt lại sẽ về đây)", AuditLevel.NONE, "GH" + fkey, 0);
            nodes.put(gn.key, gn);
            if (Boolean.TRUE.equals(reachable.get(folder)) && expanded.contains(gn.key)) {
                fillGhosts(gn, nodes);
                expand.add(gn);
            } else {
                gn.lazy();
            }
            folder.add(gn);
        });
        restoring = true;
        try {
            model.setRoot(root);
            for (Node n : expand) {
                tree.expandPath(new TreePath(n.getPath()));
            }
            List<TreePath> again = new ArrayList<>();
            for (Object k : selected) {
                Node n = nodes.get(k);
                if (n != null) {
                    again.add(new TreePath(n.getPath()));
                }
            }
            if (!again.isEmpty()) {
                tree.setSelectionPaths(again.toArray(new TreePath[0]));
            }
        } finally {
            restoring = false;
        }
    }

    private Node ensureFolder(Node root, List<String> parts, Map<Object, Node> nodes, Map<String, int[]> counts,
                              Map<String, Integer> pend, Map<String, Integer> ghostCount, Map<Node, Boolean> reachable,
                              List<Node> expand) {
        Node parent = root;
        for (int i = 0; i < parts.size(); i++) {
            String k = Hierarchy.key(parts.subList(0, i + 1));
            Node n = nodes.get("F" + k);
            if (n == null) {
                int[] c = counts.getOrDefault(k, new int[3]);
                Audit.Verdict v = Audit.fromCounts(c[0], c[1], c[2]);
                Integer pCount = pend.get(k);
                Integer gCount = ghostCount.get(k);
                String name = parts.get(i);
                Node.FolderMeta meta = new Node.FolderMeta(name, "Cấp " + (i + 1), c[0] + " API", v.summary(),
                        pCount != null && pCount > 0 ? String.valueOf(pCount) : null,
                        gCount != null && gCount > 0 ? String.valueOf(gCount) : null);
                n = new Node(Node.Kind.FOLDER, Hierarchy.join(parts.subList(0, i + 1)),
                        name + " · " + c[0] + " API · " + v.summary(), v.level(), "F" + k, i + 1, meta);
                nodes.put(n.key, n);
                parent.add(n);
                boolean visible = Boolean.TRUE.equals(reachable.get(parent)) && !collapsed.contains(n.key);
                reachable.put(n, visible);
                if (visible) {
                    expand.add(n);
                }
            }
            parent = n;
        }
        return parent;
    }

    private void loadChildren(Node n) {
        if (n.kind == Node.Kind.GROUP) {
            fillGroup(n, null);
        } else if (n.kind == Node.Kind.GHOSTS) {
            fillGhosts(n, null);
        }
        model.nodeStructureChanged(n);
    }

    @SuppressWarnings("unchecked")
    private void fillGhosts(Node n, Map<Object, Node> nodes) {
        n.removeAllChildren();
        List<PlacementBook.Record> recs = new ArrayList<>((List<PlacementBook.Record>) n.payload);
        recs.sort(Comparator.comparing((PlacementBook.Record r) -> Text.nz(r.path)).thenComparing(r -> Text.nz(r.method)));
        for (PlacementBook.Record r : recs) {
            String label = r.method + " " + Text.shortPath(Text.nz(r.path), 60) + "   ·   xóa " + r.deleted
                    + " lần (lần cuối " + (r.deletedAt == null ? "-" : r.deletedAt) + ")   ·   gặp " + r.seen + " lần"
                    + (r.lastFoundAt != null ? "   ·   lần cuối gặp tại " + r.lastFoundAt : "");
            Node c = new Node(Node.Kind.GHOST, r, label, AuditLevel.NONE, "GHO" + r.key, 0);
            n.add(c);
            if (nodes != null) {
                nodes.put(c.key, c);
            }
        }
        n.loaded = true;
    }

    private void fillGroup(Node n, Map<Object, Node> nodes) {
        ApiGroup g = (ApiGroup) n.payload;
        n.removeAllChildren();
        PlaceHint h = g.placeHint();
        List<Node> kids = new ArrayList<>();
        if (h != null) {
            kids.add(new Node(Node.Kind.PLACE, new Object[] {g, h.old(), "old"}, "Vị trí cũ (giữ chỗ): " + h.old()
                    + "   ·   đang đặt ở đây" + (h.oldMissing() ? " · nhánh cũ đã bị xóa nên được tạo lại" : ""),
                    AuditLevel.NONE, "P" + g.gid() + "old", 0));
            kids.add(new Node(Node.Kind.PLACE, new Object[] {g, h.found(), "found"}, "Vừa gặp tại: " + h.found()
                    + "   ·   chuột phải để chuyển sang đây", AuditLevel.NONE, "P" + g.gid() + "found", 0));
        }
        kids.add(new Node(Node.Kind.ORIG, new Object[] {g, g.origResult()}, resultLabel(g, g.origResult()),
                Audit.level(g, g.origResult()), "O" + g.gid(), 0));
        for (RoleResult r : g.results()) {
            kids.add(new Node(Node.Kind.ROLE, new Object[] {g, r}, resultLabel(g, r), Audit.level(g, r),
                    "R" + g.gid() + "/" + r.roleKey(), 0));
        }
        for (Node k : kids) {
            n.add(k);
            if (nodes != null) {
                nodes.put(k.key, k);
            }
        }
        n.loaded = true;
    }

    // ------------------------------------------------------------------ selection
    public Node selectedNode() {
        TreePath p = tree.getSelectionPath();
        return p == null ? null : (Node) p.getLastPathComponent();
    }

    public int selectionCount() {
        return tree.getSelectionCount();
    }

    /** The API of a GROUP / ORIG / ROLE / PLACE row. */
    public static ApiGroup groupOf(Node n) {
        if (n == null) {
            return null;
        }
        if (n.payload instanceof ApiGroup g) {
            return g;
        }
        if (n.payload instanceof Object[] arr && arr.length > 0 && arr[0] instanceof ApiGroup g) {
            return g;
        }
        return null;
    }

    /** APIs of every selected row (a folder brings all its APIs). */
    public List<ApiGroup> selectedGroups() {
        Set<ApiGroup> out = new LinkedHashSet<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths != null) {
            for (TreePath p : paths) {
                Node n = (Node) p.getLastPathComponent();
                if (n.kind == Node.Kind.ROOT) {
                    out.addAll(engine.groups());
                } else if (n.kind == Node.Kind.FOLDER) {
                    out.addAll(engine.groupsUnder((String) n.payload));
                } else {
                    ApiGroup g = groupOf(n);
                    if (g != null) {
                        out.add(g);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Branch of the selected row (a folder, or the branch of the selected API). */
    public String selectedBranch() {
        Node n = selectedNode();
        if (n == null) {
            return null;
        }
        if (n.kind == Node.Kind.FOLDER) {
            return (String) n.payload;
        }
        ApiGroup g = groupOf(n);
        return g != null ? g.branch() : null;
    }

    public void selectGroup(ApiGroup g) {
        Node root = (Node) model.getRoot();
        for (java.util.Enumeration<?> en = root.depthFirstEnumeration(); en.hasMoreElements(); ) {
            Node n = (Node) en.nextElement();
            if (n.kind == Node.Kind.GROUP && n.payload == g) {
                TreePath p = new TreePath(n.getPath());
                tree.setSelectionPath(p);
                tree.scrollPathToVisible(p);
                return;
            }
        }
    }

    /** Open (true) or close every folder. */
    public void expandFolders(boolean open) {
        if (open) {
            collapsed.clear();
            for (int i = 0; i < tree.getRowCount(); i++) {
                Node n = (Node) tree.getPathForRow(i).getLastPathComponent();
                if (n.kind == Node.Kind.ROOT || n.kind == Node.Kind.FOLDER) {
                    tree.expandRow(i);
                }
            }
        } else {
            expanded.clear();
            for (int i = tree.getRowCount() - 1; i > 0; i--) {
                tree.collapseRow(i);
            }
        }
    }

    // ------------------------------------------------------------------ chips
    /** {Chip, ApiGroup} under the mouse on an API row, else null. */
    public Object[] chipAt(int x, int y) {
        TreePath path = tree.getPathForLocation(x, y);
        if (path == null || !(path.getLastPathComponent() instanceof Node n) || n.kind != Node.Kind.GROUP) {
            return null;
        }
        ApiGroup g = (ApiGroup) n.payload;
        Rectangle b = tree.getPathBounds(path);
        if (b == null) {
            return null;
        }
        if (tree.getCellRenderer() instanceof TreeRenderer r) {
            Chip c = r.hitTestChip(tree, n, tree.getRowForPath(path), x - b.x);
            return c != null ? new Object[] {c, g} : null;
        }
        return null;
    }

    public static Collection<ApiGroup> none() {
        return List.of();
    }
}
