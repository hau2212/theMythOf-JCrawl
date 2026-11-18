package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.kit.ThemedTree;
import themythofcrawl.ui.theme.Theme;

/**
 * 'Log' tab: branch > API > (log lines + role results), plus 'Hệ thống & thao tác', with live
 * search and type-filter pills. Every level is still created only when opened (lazy).
 */
final class LogTab {
    enum LogKind {
        ALL("Tất cả", t -> t.accent, null),
        CAPTURE("Bắt", t -> t.info, t -> Icons.bolt(t.info.text(), 12)),
        SEND("Gửi", t -> t.success, t -> Icons.send(t.success.text(), 12)),
        ERROR("Lỗi", t -> t.danger, t -> Icons.error(t.danger.text(), 12)),
        MOVE("Di chuyển", t -> t.warning, t -> Icons.move(t.warning.text(), 12)),
        SYSTEM("Hệ thống", t -> t.neutral, t -> Icons.gear(t.muted, 12));

        final String label;
        final Function<Theme, Theme.Token> token;
        final Function<Theme, Icon> icon;

        LogKind(String label, Function<Theme, Theme.Token> token, Function<Theme, Icon> icon) {
            this.label = label;
            this.token = token;
            this.icon = icon;
        }
    }

    final JPanel panel = new JPanel(new BorderLayout(4, 4));
    private final MainPanel ui;
    private final Engine engine;
    private final DefaultTreeModel model;
    private final JTree tree;
    private final Set<Object> expanded = new HashSet<>();
    private final SearchField search = new SearchField("Tìm log (API, path, nội dung)...", 16);
    private final Map<LogKind, Pill> filterPills = new EnumMap<>(LogKind.class);
    private LogKind activeKind = LogKind.ALL;
    private final JLabel hint;
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
        tree.setRowHeight(Theme.ROW_TREE);
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
        tree.addMouseListener(ApiTab.treePopup(tree, e -> logMenu().show(e.getComponent(), e.getX(), e.getY())));
        hint = Ui.muted("  Mỗi nhánh > API > chi tiết tải lười khi mở. Click đúp API để nhảy tới.");

        JPanel filterBar = Ui.flow(FlowLayout.LEFT, search);
        for (LogKind k : LogKind.values()) {
            Pill p = new Pill(k.label, k.token, k.icon);
            p.setSolidWhenActive(true);
            p.setActive(k == activeKind);
            p.onClick(() -> setFilter(k));
            filterPills.put(k, p);
            filterBar.add(p);
        }
        search.onChange(q -> {
            stale = true;
            nextOk = 0;
            rebuild();
        });

        JButton[] moreRef = new JButton[1];
        JButton more = Ui.dropdown("Thao tác", t -> Icons.withChevron(Icons.gear(t.fg), t.fg),
                "Mở/Thu gọn nhánh, Copy dòng chọn, Xóa nhật ký hệ thống",
                () -> logMenu().show(moreRef[0], 0, moreRef[0].getHeight()));
        moreRef[0] = more;

        JPanel actions = Ui.flow(FlowLayout.RIGHT,
                Ui.accent("Xuất Excel", t -> Icons.excel(t.success.onSolid()), t -> t.success, ui::exportExcel),
                more);

        JPanel north = new JPanel(new BorderLayout(Theme.SPACE_1, 0));
        north.setOpaque(false);
        north.setBorder(BorderFactory.createEmptyBorder(2, Theme.SPACE_1, 2, Theme.SPACE_1));
        north.add(filterBar, BorderLayout.CENTER);
        north.add(actions, BorderLayout.EAST);

        panel.add(north, BorderLayout.NORTH);
        panel.add(MainPanel.scroll(tree), BorderLayout.CENTER);
    }

    private JPopupMenu logMenu() {
        JPopupMenu m = new JPopupMenu();
        Node sel = selected();
        m.add(Ui.item("Mở API ở tab API (Click đúp)", Icons::search, sel != null && sel.payload instanceof ApiGroup, this::jump));
        m.add(Ui.item("Copy dòng đang chọn", Icons::copy, sel != null, () -> {
            Node n = selected();
            if (n != null) {
                Ui.copyToClipboard(n.label);
            }
        }));
        m.addSeparator();
        m.add(Ui.item("Mở tất cả các nhánh", Icons::plus, this::expandBranches));
        m.add(Ui.item("Thu gọn tất cả", Icons::minus, this::collapseAll));
        m.addSeparator();
        m.add(Ui.item("Xuất Excel (.xlsx)...", Icons::excel, ui::exportExcel));
        m.add(Ui.item("Xóa nhật ký hệ thống", t -> Icons.trash(t.danger.text()), () -> {
            engine.logbook().clearSystem();
            stale = true;
            nextOk = 0;
            rebuild();
        }));
        return m;
    }

    static LogKind classify(Logbook.Line l) {
        if (l == null) {
            return LogKind.SYSTEM;
        }
        String raw = Icons.stripInlineSymbols(Text.nz(l.text()));
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("lỗi") || lower.contains("error") || lower.contains("thất bại")) {
            return LogKind.ERROR;
        }
        if (lower.contains("bắt từ") || lower.contains("skipped ") || lower.contains("manual trigger")
                || lower.contains("gặp api trùng") || lower.contains("quay về chỗ cũ")) {
            return LogKind.CAPTURE;
        }
        if (lower.contains("gửi") || lower.contains("repeater") || lower.contains("chỉ crawl")
                || lower.contains("dựng lại")) {
            return LogKind.SEND;
        }
        if (lower.contains("đích") || lower.contains("chuyển") || lower.contains("đổi tên")
                || lower.contains("xóa nhánh") || lower.contains("đã xóa ") || lower.contains("xác nhận vị trí")
                || lower.contains("giữ chỗ") || lower.contains("vị trí")) {
            return LogKind.MOVE;
        }
        return l.gid() == null ? LogKind.SYSTEM : LogKind.CAPTURE;
    }

    void setFilter(LogKind kind) {
        this.activeKind = kind == null ? LogKind.ALL : kind;
        filterPills.forEach((k, pill) -> {
            pill.setActive(k == this.activeKind);
            if (k.icon != null) {
                pill.setIcon(t -> switch (k) {
                    case CAPTURE -> Icons.bolt(k == this.activeKind ? t.info.onSolid() : t.info.text(), 12);
                    case SEND -> Icons.send(k == this.activeKind ? t.success.onSolid() : t.success.text(), 12);
                    case ERROR -> Icons.error(k == this.activeKind ? t.danger.onSolid() : t.danger.text(), 12);
                    case MOVE -> Icons.move(k == this.activeKind ? t.warning.onSolid() : t.warning.text(), 12);
                    default -> Icons.gear(k == this.activeKind ? t.neutral.onSolid() : t.muted, 12);
                });
            }
        });
        stale = true;
        nextOk = 0;
        rebuild();
    }

    private boolean filtering() {
        return activeKind != LogKind.ALL || !searchQuery().isEmpty();
    }

    private String searchQuery() {
        return search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
    }

    private boolean matchesLine(Logbook.Line l, ApiGroup g, String q) {
        if (activeKind != LogKind.ALL && classify(l) != activeKind) {
            return false;
        }
        if (q.isEmpty()) {
            return true;
        }
        if (Text.nz(l.text()).toLowerCase(Locale.ROOT).contains(q)
                || Text.nz(l.time()).toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (g != null) {
            return Text.apiLabel(g.gid()).toLowerCase(Locale.ROOT).contains(q)
                    || Text.nz(g.method()).toLowerCase(Locale.ROOT).contains(q)
                    || Text.nz(g.path()).toLowerCase(Locale.ROOT).contains(q);
        }
        return false;
    }

    private List<ApiGroup> visibleGroupsForBranch(List<ApiGroup> gs, String path) {
        if (!filtering()) {
            return gs;
        }
        String q = searchQuery();
        boolean pathHit = !q.isEmpty() && path.toLowerCase(Locale.ROOT).contains(q) && activeKind == LogKind.ALL;
        List<ApiGroup> out = new ArrayList<>();
        for (ApiGroup g : gs) {
            if (pathHit) {
                out.add(g);
                continue;
            }
            boolean apiMatch = !q.isEmpty() && activeKind == LogKind.ALL && (Text.apiLabel(g.gid()).toLowerCase(Locale.ROOT).contains(q)
                    || Text.nz(g.method()).toLowerCase(Locale.ROOT).contains(q)
                    || Text.nz(g.path()).toLowerCase(Locale.ROOT).contains(q));
            if (apiMatch) {
                out.add(g);
                continue;
            }
            for (Logbook.Line l : engine.logbook().of(g.gid())) {
                if (matchesLine(l, g, q)) {
                    out.add(g);
                    break;
                }
            }
        }
        return out;
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

    void rebuild() {
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
            List<ApiGroup> vis = visibleGroupsForBranch(gs, path);
            if (vis.isEmpty()) {
                return;
            }
            Audit.Verdict v = Audit.branch(vis);
            Node n = new Node(Node.Kind.LOGBRANCH, vis, path + "   ·   " + vis.size() + " API   ·   " + v.summary(), v.level(),
                    "LB" + Hierarchy.key(path), Hierarchy.depth(path));
            n.lazy();
            nodes.put(n.key, n);
            root.add(n);
        });
        String q = searchQuery();
        List<Logbook.Line> sysAll = engine.logbook().system();
        int sysVisible = 0;
        for (Logbook.Line l : sysAll) {
            if (!filtering() || matchesLine(l, null, q)) {
                sysVisible++;
            }
        }
        if (!filtering() || sysVisible > 0) {
            Node sys = new Node(Node.Kind.LOGSYS, null, "Hệ thống & thao tác   ·   " + sysVisible + " dòng",
                    AuditLevel.NONE, "LS", 0);
            sys.lazy();
            nodes.put(sys.key, sys);
            root.add(sys);
        }
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
        String q = searchQuery();
        switch (n.kind) {
            case LOGBRANCH -> {
                for (ApiGroup g : (List<ApiGroup>) n.payload) {
                    String s = Audit.summary(g);
                    List<Logbook.Line> lines = engine.logbook().of(g.gid());
                    long visCount = filtering() ? lines.stream().filter(l -> matchesLine(l, g, q)).count() : lines.size();
                    Node c = new Node(Node.Kind.LOGAPI, g, Text.apiLabel(g.gid()) + "   ·   " + g.method() + " "
                            + Text.shortPath(g.path(), 60) + "   ·   " + g.created() + "   ·   " + (s == null ? "" : s)
                            + "   ·   " + visCount + " dòng log", Audit.display(g), "LA" + g.gid(), 0);
                    c.lazy();
                    n.add(c);
                }
            }
            case LOGAPI -> {
                ApiGroup g = (ApiGroup) n.payload;
                int i = 0;
                for (Logbook.Line l : engine.logbook().of(g.gid())) {
                    if (!filtering() || matchesLine(l, g, q)) {
                        n.add(new Node(Node.Kind.LOGLINE, l, l.time() + "   " + l.text(), AuditLevel.NONE,
                                "LL" + g.gid() + "/" + i, 0));
                    }
                    i++;
                }
                if (!filtering() || activeKind == LogKind.ALL) {
                    List<RoleResult> all = new ArrayList<>();
                    all.add(g.origResult());
                    all.addAll(g.results());
                    for (RoleResult r : all) {
                        n.add(new Node(r == g.origResult() ? Node.Kind.ORIG : Node.Kind.ROLE, new Object[] {g, r},
                                BranchTree.resultLabel(g, r), Audit.level(g, r), "LR" + g.gid() + "/" + r.id(), 0));
                    }
                }
            }
            case LOGSYS -> {
                int i = 0;
                for (Logbook.Line l : engine.logbook().system()) {
                    if (!filtering() || matchesLine(l, null, q)) {
                        n.add(new Node(Node.Kind.LOGLINE, l, l.time() + "   " + l.text(), AuditLevel.NONE, "LSL" + i, 0));
                    }
                    i++;
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

    // ---- Test / inspection helpers ----
    SearchField searchField() {
        return search;
    }

    Map<LogKind, Pill> filterPills() {
        return filterPills;
    }

    LogKind activeKind() {
        return activeKind;
    }

    JTree logTree() {
        return tree;
    }
}

