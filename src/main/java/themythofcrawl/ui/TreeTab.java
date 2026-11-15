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
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleResult;

/**
 * 'Cây' tab: the branch tree alone, full size, with the selected API / role's request and response
 * below (like Proxy history). The tree + viewers can move into their own window.
 */
final class TreeTab {
    final JPanel panel = new JPanel(new BorderLayout());
    private final JPanel content = new JPanel(new BorderLayout());
    private final JPanel placeholder = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 40));
    private final MainPanel ui;
    private final Engine engine;
    final BranchTree tree;
    private final JLabel header = new JLabel(" ");
    private final JButton windowBtn;
    private final JButton more = new JButton("Thao tác ▾");
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
        windowBtn = Ui.button("🗗 Mở cửa sổ riêng", this::toggleWindow);
        windowBtn.setFont(windowBtn.getFont().deriveFont(java.awt.Font.BOLD));
        more.addActionListener(e -> menu());
        JPanel bar = Ui.flow(FlowLayout.LEFT, windowBtn, Ui.button("🎯 Đẩy / Gửi có chọn Role...", () -> {
            ui.useSource(tree);
            ui.pushDialog(false, null, null);
        }), Ui.button("📋 Copy", "Copy 'STT. path' của API / nhánh đang chọn (Ctrl+Shift+C)", () -> {
            ui.useSource(tree);
            ui.copy(ui.scope().groups());
        }), more, Ui.button("+", "Mở tất cả các nhánh", () -> tree.expandFolders(true)),
                Ui.button("−", "Thu gọn tất cả", () -> tree.expandFolders(false)), Ui.info(Help.SELECT, "Chọn nhiều"));
        header.setForeground(Palette.ui().muted);
        header.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 8));
        JPanel head = new JPanel(new BorderLayout());
        head.add(bar, BorderLayout.NORTH);
        head.add(header, BorderLayout.SOUTH);
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
    }

    private void menu() {
        ui.useSource(tree);
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("▶ Gửi mục đang chọn (nhánh / API / role)", () -> ui.send(ui.scope().groups())));
        m.add(Ui.item("📤 Đẩy mục đang chọn -> Burp Repeater", () -> ui.push(ui.scope().groups())));
        m.add(Ui.item("🎯 Gửi có chọn Role...", () -> ui.pushDialog(true, null, null)));
        m.add(Ui.item("📋 Copy STT + Path (Ctrl+Shift+C)", () -> ui.copy(ui.scope().groups())));
        m.add(Ui.item("📋 Copy path nhánh", ui::copyBranchPath));
        m.add(Ui.item("🎛 Bật / Tắt Role cho mục đang chọn...", () -> {
            Node n = tree.selectedNode();
            if (n != null && n.kind == Node.Kind.FOLDER) {
                ui.roleScopeDialog(false, themythofcrawl.model.RoleScopes.Scope.BRANCH, java.util.List.of((String) n.payload), null, null);
            } else {
                ui.roleScopeDialog(false, themythofcrawl.model.RoleScopes.Scope.API, null, tree.selectedGroups(), null);
            }
        }));
        m.addSeparator();
        m.add(Ui.item("➜ Đặt nhánh đang chọn làm ĐÍCH cho request mới", ui::destinationFromTree));
        m.add(Ui.item("✔ Xác nhận vị trí các API đang chọn / trong nhánh", () -> engine.confirm(tree.selectedGroups(), "")));
        m.add(Ui.item("↪ Chuyển các API đang chọn sang đường dẫn khác...", () -> ui.moveDialog(tree.selectedGroups())));
        m.add(Ui.item("↶ Đưa các API đang chọn về vị trí trước", () -> ui.revert(tree.selectedGroups())));
        m.add(Ui.item("➕ Thêm Sub-folder con...", () -> ui.addSubfolder(ui.selectedBranch())));
        m.add(Ui.item("✏ Đổi tên nhánh...", () -> {
            String b = ui.selectedBranch();
            if (b != null) {
                ui.renameBranchDialog(b);
            }
        }));
        m.add(Ui.item("🗑 Xóa nhánh...", () -> ui.deleteBranchDialog(ui.selectedBranch())));
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

    private void showEntry(ApiGroup g, RoleResult r) {
        shownGroup = g;
        shown = r;
        Object st = r.status() != null ? r.status() : r.state();
        reqLabel.setText(" Request [" + themythofcrawl.core.Text.groupId(g.gid()) + " | " + r.roleName() + "]  " + g.endpoint());
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
            header.setText("➜ Request mới sẽ vào: " + engine.activeBranch().name() + "   ·   " + engine.branches().size()
                    + " nhánh, " + engine.groups().size() + " API   ·   Chuột phải để Gửi / Đẩy / Thêm / Sửa / Xóa; "
                    + "chọn 1 API để xem Request / Response bên dưới");
        }
    }

    private void toggleWindow() {
        if (window != null) {
            closeWindow();
            return;
        }
        window = new JFrame("theMythOfCrawl - Cây Sub-folder");
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
        JCheckBox onTop = new JCheckBox("📌 Luôn nằm trên các cửa sổ khác");
        onTop.addActionListener(e -> window.setAlwaysOnTop(onTop.isSelected()));
        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.add(Ui.flow(FlowLayout.LEFT, onTop), BorderLayout.NORTH);
        rootPanel.add(content, BorderLayout.CENTER);
        window.getContentPane().add(rootPanel);
        try {
            ui.host.applyTheme(rootPanel);
        } catch (RuntimeException ignored) {
            // outside Burp
        }
        window.setSize(new Dimension(980, 900));
        window.setLocationRelativeTo(ui.root());
        window.setVisible(true);
        windowBtn.setText("🗗 Gắn lại vào tab");
        stale = true;
        refreshIfVisible();
    }

    private void closeWindow() {
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
        windowBtn.setText("🗗 Mở cửa sổ riêng");
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
}
