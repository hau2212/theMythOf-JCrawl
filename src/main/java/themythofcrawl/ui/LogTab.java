package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Logbook;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.model.RoleResult;
import themythofcrawl.ui.kit.ThemedTree;

/**
 * 'Log' tab: branch > API > (log lines + role results), plus 'Hệ thống & thao tác'. Every level is
 * created only when opened, so thousands of APIs cost one row per branch until you look.
 */
final class LogTab {
    final JPanel panel = new JPanel(new BorderLayout(4, 4));
    private final MainPanel ui;
    private final Engine engine;
    private final DefaultTreeModel model;
    private final JTree tree;
    private final Set<Object> expanded = new HashSet<>();
    private final JLabel hint = new JLabel();
    private boolean restoring;
    private long nextOk;
    boolean stale = true;

    LogTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        model = new DefaultTreeModel(new Node(Node.Kind.ROOT, "", "Nhật ký", AuditLevel.NONE, "ROOT", 0));
        tree = new ThemedTree(model, new TreeRenderer());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(26);
        tree.setLargeModel(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                Node n = (Node) e.getPath().getLastPathComponent();
                if (!n.loaded) {
                    load(n);
                    model.nodeStructureChanged(n);
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
                    expanded.add(((Node) e.getPath().getLastPathComponent()).key);
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                if (!restoring) {
                    expanded.remove(((Node) e.getPath().getLastPathComponent()).key);
                }
            }
        });
        Ui.onDoubleClick(tree, this::jump);
        JPanel bar = Ui.flow(FlowLayout.LEFT, Ui.accent("Xuất Excel (.xlsx)...", Palette.GREEN, ui::exportExcel),
                Ui.button("Mở các nhánh", this::expandBranches), Ui.button("Thu gọn tất cả", this::collapseAll),
                Ui.button("Xóa nhật ký hệ thống", () -> {
                    engine.logbook().clearSystem();
                    stale = true;
                    refreshIfVisible();
                }), Ui.button("Copy dòng chọn", () -> {
                    Node n = selected();
                    if (n != null) {
                        Ui.copyToClipboard(n.label);
                    }
                }), hint);
        hint.setText("   Mỗi nhánh > API > chi tiết chỉ được tải khi bạn bấm mở. Click đúp 1 API để xem ở tab API.");
        hint.setForeground(Palette.mix(Palette.ui().fg, Palette.ui().bg, 0.42));
        panel.add(bar, BorderLayout.NORTH);
        panel.add(MainPanel.scroll(tree), BorderLayout.CENTER);
    }

    /** New lines: only the open parts of the tree care, the rest is built when opened. */
    void append(List<Logbook.Line> lines) {
        if (!lines.isEmpty()) {
            stale = true;
        }
    }

    void refreshIfVisible() {
        if (!stale || !tree.isShowing()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextOk) {
            return;
        }
        stale = false;
        long t0 = System.nanoTime();
        rebuild();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        nextOk = System.currentTimeMillis() + Math.min(5000, Math.max(400, ms * 8));
    }

    private Node selected() {
        TreePath p = tree.getSelectionPath();
        return p == null ? null : (Node) p.getLastPathComponent();
    }

    private void rebuild() {
        Node sel = selected();
        Object selKey = sel == null ? null : sel.key;
        List<ApiGroup> groups = engine.groups();
        Map<String, List<ApiGroup>> byPath = new LinkedHashMap<>();
        for (Branch b : engine.branches()) {
            byPath.put(Hierarchy.normalize(b.name()), new ArrayList<>());
        }
        for (ApiGroup g : groups) {
            byPath.computeIfAbsent(Hierarchy.normalize(g.branch()), k -> new ArrayList<>()).add(g);
        }
        Node root = new Node(Node.Kind.ROOT, "", "Nhật ký", AuditLevel.NONE, "ROOT", 0);
        Map<Object, Node> nodes = new HashMap<>();
        List<Node> expand = new ArrayList<>();
        expand.add(root);
        byPath.forEach((path, gs) -> {
            if (gs.isEmpty()) {
                return;
            }
            Audit.Verdict v = Audit.branch(gs);
            Node n = new Node(Node.Kind.LOGBRANCH, gs, path + "   ·   " + gs.size() + " API   ·   " + v.summary(), v.level(),
                    "LB" + Hierarchy.key(path), Hierarchy.depth(path));
            n.lazy();
            nodes.put(n.key, n);
            root.add(n);
        });
        Node sys = new Node(Node.Kind.LOGSYS, null, "Hệ thống & thao tác   ·   " + engine.logbook().system().size() + " dòng",
                AuditLevel.NONE, "LS", 0);
        sys.lazy();
        nodes.put(sys.key, sys);
        root.add(sys);
        for (int i = 0; i < root.getChildCount(); i++) {
            Node b = (Node) root.getChildAt(i);
            if (!expanded.contains(b.key)) {
                continue;
            }
            load(b);
            expand.add(b);
            for (int j = 0; j < b.getChildCount(); j++) {
                Node a = (Node) b.getChildAt(j);
                nodes.put(a.key, a);
                if (expanded.contains(a.key)) {
                    load(a);
                    expand.add(a);
                    for (int k = 0; k < a.getChildCount(); k++) {
                        Node c = (Node) a.getChildAt(k);
                        nodes.put(c.key, c);
                    }
                }
            }
        }
        expanded.retainAll(nodes.keySet());
        restoring = true;
        try {
            model.setRoot(root);
            for (Node n : expand) {
                tree.expandPath(new TreePath(n.getPath()));
            }
            Node target = selKey == null ? null : nodes.get(selKey);
            if (target != null) {
                tree.setSelectionPath(new TreePath(target.getPath()));
            }
        } finally {
            restoring = false;
        }
    }

    @SuppressWarnings("unchecked")
    private void load(Node n) {
        n.removeAllChildren();
        switch (n.kind) {
            case LOGBRANCH -> {
                for (ApiGroup g : (List<ApiGroup>) n.payload) {
                    String s = Audit.summary(g);
                    Node c = new Node(Node.Kind.LOGAPI, g, Text.apiLabel(g.gid()) + "   ·   " + g.method() + " "
                            + Text.shortPath(g.path(), 60) + "   ·   " + g.created() + "   ·   " + (s == null ? "" : s)
                            + "   ·   " + engine.logbook().count(g.gid()) + " dòng log", Audit.display(g), "LA" + g.gid(), 0);
                    c.lazy();
                    n.add(c);
                }
            }
            case LOGAPI -> {
                ApiGroup g = (ApiGroup) n.payload;
                int i = 0;
                for (Logbook.Line l : engine.logbook().of(g.gid())) {
                    n.add(new Node(Node.Kind.LOGLINE, l, l.time() + "   " + l.text(), AuditLevel.NONE, "LL" + g.gid() + "/" + i++, 0));
                }
                List<RoleResult> all = new ArrayList<>();
                all.add(g.origResult());
                all.addAll(g.results());
                for (RoleResult r : all) {
                    n.add(new Node(r == g.origResult() ? Node.Kind.ORIG : Node.Kind.ROLE, new Object[] {g, r},
                            BranchTree.resultLabel(g, r), Audit.level(g, r), "LR" + g.gid() + "/" + r.id(), 0));
                }
            }
            case LOGSYS -> {
                int i = 0;
                for (Logbook.Line l : engine.logbook().system()) {
                    n.add(new Node(Node.Kind.LOGLINE, l, l.time() + "   " + l.text(), AuditLevel.NONE, "LSL" + i++, 0));
                }
            }
            default -> { }
        }
        n.loaded = true;
    }

    private void expandBranches() {
        for (int i = tree.getRowCount() - 1; i >= 0; i--) {
            Node n = (Node) tree.getPathForRow(i).getLastPathComponent();
            if (n.kind == Node.Kind.LOGBRANCH) {
                tree.expandRow(i);
            }
        }
    }

    private void collapseAll() {
        for (int i = tree.getRowCount() - 1; i >= 0; i--) {
            tree.collapseRow(i);
        }
        expanded.clear();
    }

    private void jump() {
        Node n = selected();
        if (n == null) {
            return;
        }
        ApiGroup g = n.kind == Node.Kind.LOGAPI ? (ApiGroup) n.payload : BranchTree.groupOf(n);
        if (g == null) {
            return;
        }
        RoleResult r = n.payload instanceof Object[] a && a.length > 1 && a[1] instanceof RoleResult x ? x : null;
        ui.jumpToGroup(g);
        if (r != null) {
            ui.api.select(g, r);
        }
    }
}
