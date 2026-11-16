package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleResult;
import themythofcrawl.ui.kit.Breadcrumb;
import themythofcrawl.ui.theme.Theme;

/**
 * 'Cây' tab: the branch tree alone, full size, with Breadcrumb selection path and the selected API /
 * role's request &amp; response below. The tree + viewers can move into their own window whose title
 * tracks the selected Breadcrumb.
 */
final class TreeTab {
    final JPanel panel = new JPanel(new BorderLayout());
    private final JPanel content = new JPanel(new BorderLayout());
    private final JPanel placeholder = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 40));
    private final MainPanel ui;
    private final Engine engine;
    final BranchTree tree;
    private final Breadcrumb headerBreadcrumb = new Breadcrumb();
    private final JLabel headerMeta = Ui.muted(" ");
    private final JButton windowBtn;
    private final JButton more;
    private final JLabel reqLabel = Ui.bold(" Request - chọn 1 API / role trong cây");
    private final JLabel respLabel = Ui.bold(" Response");
    private final UiHost.Viewer req;
    private final UiHost.Viewer resp;
    private JFrame window;
    boolean stale = true;
    private ApiGroup shownGroup;
    private RoleResult shown;
    private Object shownState;

    TreeTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        tree = new BranchTree(engine);
        tree.onChip((c, g, t, x, y) -> ui.chip(c, g, t, x, y));
        tree.tree.addTreeSelectionListener(e -> onSelected());
        tree.tree.addMouseListener(ApiTab.treePopup(tree.tree, e -> {
            ui.useSource(tree);
            TreeMenus.show(ui, tree, e.getComponent(), e.getX(), e.getY());
        }));
        Ui.key(tree.tree, "ctrl shift C", () -> {
            ui.useSource(tree);
            ui.copy(ui.scope().groups());
        });
        windowBtn = new JButton("Mở cửa sổ riêng") {
            @Override
            public void updateUI() {
                super.updateUI();
                setFont(Theme.current().boldFont);
            }
        };
        windowBtn.setIcon(Icons.themed(t -> Icons.window(t.fg)));
        windowBtn.addActionListener(e -> toggleWindow());
        more = Ui.dropdown("Thao tác", null, "Thao tác nhánh / API", this::menu);
        JPanel bar = Ui.flow(FlowLayout.LEFT, windowBtn,
                Ui.button("Đẩy / Gửi có chọn Role...", t -> Icons.target(t.fg), () -> {
                    ui.useSource(tree);
                    ui.pushDialog(false, null, null);
                }),
                Ui.button("Copy", t -> Icons.copy(t.fg), "Copy 'STT. path' của API / nhánh đang chọn (Ctrl+Shift+C)", () -> {
                    ui.useSource(tree);
                    ui.copy(ui.scope().groups());
                }),
                more,
                Ui.button("", t -> Icons.plus(t.fg), "Mở tất cả các nhánh", () -> tree.expandFolders(true)),
                Ui.button("", t -> Icons.minus(t.fg), "Thu gọn tất cả", () -> tree.expandFolders(false)),
                Ui.info(Help.SELECT, "Chọn nhiều"));

        headerBreadcrumb.setMaxSegments(5);
        headerBreadcrumb.onSegmentClick(seg -> tree.selectBranchPath(seg));
        JPanel headerBar = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        headerBar.setOpaque(false);
        headerBar.setBorder(BorderFactory.createEmptyBorder(2, Theme.SPACE_2, 2, Theme.SPACE_2));
        headerBar.add(headerBreadcrumb, BorderLayout.WEST);
        headerBar.add(headerMeta, BorderLayout.CENTER);

        JPanel head = new JPanel(new BorderLayout());
        head.add(bar, BorderLayout.NORTH);
        head.add(headerBar, BorderLayout.SOUTH);

        req = ui.host.requestViewer(false);
        resp = ui.host.responseViewer();
        JPanel rq = new JPanel(new BorderLayout());
        rq.add(reqLabel, BorderLayout.NORTH);
        rq.add(req.component(), BorderLayout.CENTER);
        JPanel rs = new JPanel(new BorderLayout());
        rs.add(respLabel, BorderLayout.NORTH);
        rs.add(resp.component(), BorderLayout.CENTER);
        JSplitPane viewers = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rq, rs);
        viewers.setResizeWeight(0.5);
        viewers.setPreferredSize(new Dimension(600, 300));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, MainPanel.scroll(tree.tree), viewers);
        split.setResizeWeight(0.55);
        split.setOneTouchExpandable(true);
        content.add(head, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        placeholder.add(new JLabel("Cây Sub-folder đang mở ở cửa sổ riêng."));
        placeholder.add(Ui.button("Gắn lại vào tab", this::closeWindow));
        panel.add(content, BorderLayout.CENTER);
        syncHeaderBreadcrumb();
    }

    private void menu() {
        ui.useSource(tree);
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Gửi mục đang chọn (nhánh / API / role)", t -> Icons.send(t.critical.text()),
                () -> ui.send(ui.scope().groups())));
        m.add(Ui.item("Đẩy mục đang chọn -> Burp Repeater", Icons::repeater, () -> ui.push(ui.scope().groups())));
        m.add(Ui.item("Gửi có chọn Role...", Icons::target, () -> ui.pushDialog(true, null, null)));
        m.add(Ui.item("Copy STT + Path (Ctrl+Shift+C)", t -> Icons.copy(t.muted), () -> ui.copy(ui.scope().groups())));
        m.add(Ui.item("Copy path nhánh", t -> Icons.copy(t.muted), ui::copyBranchPath));
        m.add(Ui.item("Bật / Tắt Role cho mục đang chọn...", Icons::sliders, () -> {
            Node n = tree.selectedNode();
            if (n != null && n.kind == Node.Kind.FOLDER) {
                ui.roleScopeDialog(false, themythofcrawl.model.RoleScopes.Scope.BRANCH, java.util.List.of((String) n.payload), null, null);
            } else {
                ui.roleScopeDialog(false, themythofcrawl.model.RoleScopes.Scope.API, null, tree.selectedGroups(), null);
            }
        }));
        m.addSeparator();
        m.add(Ui.item("Đặt nhánh đang chọn làm ĐÍCH cho request mới", Icons::arrowRight, ui::destinationFromTree));
        m.add(Ui.item("Xác nhận vị trí các API đang chọn / trong nhánh", t -> Icons.check(t.success.text()),
                () -> engine.confirm(tree.selectedGroups(), "")));
        m.add(Ui.item("Chuyển các API đang chọn sang đường dẫn khác...", Icons::move, () -> ui.moveDialog(tree.selectedGroups())));
        m.add(Ui.item("Đưa các API đang chọn về vị trí trước", Icons::undo, () -> ui.revert(tree.selectedGroups())));
        m.add(Ui.item("Thêm Sub-folder con...", Icons::plus, () -> ui.addSubfolder(ui.selectedBranch())));
        m.add(Ui.item("Đổi tên nhánh...", Icons::edit, () -> {
            String b = ui.selectedBranch();
            if (b != null) {
                ui.renameBranchDialog(b);
            }
        }));
        m.add(Ui.item("Xóa nhánh...", t -> Icons.trash(t.danger.text()), () -> ui.deleteBranchDialog(ui.selectedBranch())));
        m.show(more, 0, more.getHeight());
    }

    private void onSelected() {
        if (tree.restoring()) {
            return;
        }
        ui.useSource(tree);
        if (tree.selectionCount() > 1) {
            ui.flash("Đang chọn " + tree.selectionCount() + " mục trong cây = " + tree.selectedGroups().size() + " API");
        }
        Node n = tree.selectedNode();
        syncHeaderBreadcrumb();
        if (n == null) {
            return;
        }
        ApiGroup g = BranchTree.groupOf(n);
        if (g == null) {
            return;
        }
        RoleResult r = n.payload instanceof Object[] arr && arr.length > 1 && arr[1] instanceof RoleResult x ? x : g.origResult();
        showEntry(g, r);
    }

    private void syncHeaderBreadcrumb() {
        String path = currentBreadcrumbPath();
        headerBreadcrumb.setPath(path);
        headerMeta.setText(engine.branches().size() + " nhánh, " + engine.groups().size() + " API  ·  Đích mới: "
                + engine.activeBranch().name());
        if (window != null) {
            window.setTitle(detachedTitle());
        }
    }

    String detachedTitle() {
        return Text.EXT_NAME + " - " + currentBreadcrumbPath();
    }

    String currentBreadcrumbPath() {
        Node n = tree.selectedNode();
        if (n != null) {
            if (n.kind == Node.Kind.FOLDER && n.payload instanceof String p) {
                return p;
            }
            ApiGroup g = BranchTree.groupOf(n);
            if (g != null) {
                return g.branch() + " > " + Text.apiLabel(g.gid()) + " " + g.method() + " " + Text.shortPath(g.path(), 32);
            }
        }
        return engine.activeBranch().name();
    }

    private void showEntry(ApiGroup g, RoleResult r) {
        shownGroup = g;
        shown = r;
        Object st = r.status() != null ? r.status() : r.state();
        reqLabel.setText(" Request [" + Text.groupId(g.gid()) + " | " + r.roleName() + "]  " + g.endpoint());
        respLabel.setText(" Response [" + r.roleName() + " | Status: " + st + " | Length: " + (r.length() != null ? r.length() : "-") + "]");
        req.show(g.service(), engine.requestOf(g, r));
        resp.show(g.service(), engine.responseOf(g, r));
        shownState = r.state() + "/" + r.responseBlob();
    }

    /** A response that arrives for the entry below the tree is shown without re-selecting it. */
    void dirty(Set<ApiGroup> gs) {
        if (shownGroup != null && gs.contains(shownGroup) && shown != null
                && !(shown.state() + "/" + shown.responseBlob()).equals(shownState)) {
            showEntry(shownGroup, shown);
        }
    }

    void refreshIfVisible() {
        if (stale && tree.tree.isShowing()) {
            stale = false;
            tree.rebuild(engine.config().showGhosts);
            syncHeaderBreadcrumb();
        }
    }

    void toggleWindow() {
        if (window != null) {
            closeWindow();
            return;
        }
        window = new JFrame(detachedTitle());
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                closeWindow();
            }
        });
        panel.remove(content);
        panel.add(placeholder, BorderLayout.CENTER);
        panel.revalidate();
        panel.repaint();
        JCheckBox onTop = new JCheckBox("Luôn nằm trên các cửa sổ khác");
        onTop.addActionListener(e -> window.setAlwaysOnTop(onTop.isSelected()));
        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.add(Ui.flow(FlowLayout.LEFT, onTop), BorderLayout.NORTH);
        rootPanel.add(content, BorderLayout.CENTER);
        window.getContentPane().add(rootPanel);
        Theme.track(rootPanel);
        try {
            ui.host.applyTheme(rootPanel);
        } catch (RuntimeException ignored) {
            // outside Burp
        }
        window.setSize(new Dimension(980, 900));
        window.setLocationRelativeTo(ui.root());
        window.setVisible(true);
        windowBtn.setText("Gắn lại vào tab");
        stale = true;
        refreshIfVisible();
        syncHeaderBreadcrumb();
    }

    void closeWindow() {
        if (window == null) {
            return;
        }
        JFrame w = window;
        window = null;
        w.getContentPane().removeAll();
        w.dispose();
        panel.remove(placeholder);
        panel.add(content, BorderLayout.CENTER);
        panel.revalidate();
        panel.repaint();
        windowBtn.setText("Mở cửa sổ riêng");
        stale = true;
    }

    boolean windowOpen() {
        return window != null;
    }

    void dispose() {
        if (window != null) {
            window.dispose();
        }
    }

    // ---- Test / inspection helpers ----
    Breadcrumb headerBreadcrumb() {
        return headerBreadcrumb;
    }

    JFrame detachedWindow() {
        return window;
    }
}

