package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;

import themythofcrawl.app.Engine;
import themythofcrawl.export.Reports;
import themythofcrawl.model.ApiGroup;

/**
 * 'Lịch sử' tab: the branch tree (right-click for every action) next to the audit summary
 * (branches, APIs in order, the result of each role) that can be exported as a report.
 */
final class HistoryTab {
    final JPanel panel = new JPanel(new BorderLayout(4, 4));
    private final MainPanel ui;
    private final Engine engine;
    final BranchTree tree;
    private final JTextArea summary = new JTextArea();
    boolean stale = true;
    private long nextOk;

    HistoryTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        tree = new BranchTree(engine);
        tree.onChip((c, g, t, x, y) -> ui.chip(c, g, t, x, y));
        tree.tree.addTreeSelectionListener(e -> {
            if (!tree.restoring()) {
                ui.useSource(tree);
            }
        });
        tree.tree.addMouseListener(ApiTab.treePopup(tree.tree, e -> {
            ui.useSource(tree);
            TreeMenus.show(ui, tree, e.getComponent(), e.getX(), e.getY());
        }));
        Ui.key(tree.tree, "ctrl shift C", () -> {
            ui.useSource(tree);
            ui.copy(ui.scope().groups());
        });
        Ui.onDoubleClick(tree.tree, () -> {
            Node n = tree.selectedNode();
            ApiGroup g = BranchTree.groupOf(n);
            if (g != null && n.kind == Node.Kind.GROUP) {
                ui.jumpToGroup(g);
            }
        });
        JPanel bar = Ui.flow(FlowLayout.LEFT,
                Ui.button("▶ Gửi mục đang chọn", () -> {
                    ui.useSource(tree);
                    ui.send(ui.scope().groups());
                }),
                Ui.button("📤 Đẩy -> Burp Repeater", () -> {
                    ui.useSource(tree);
                    ui.push(ui.scope().groups());
                }),
                Ui.button("🎯 Đẩy / Gửi có chọn Role...", () -> {
                    ui.useSource(tree);
                    ui.pushDialog(false, null, null);
                }),
                Ui.button("+ Sub-folder con...", () -> {
                    ui.useSource(tree);
                    ui.addSubfolder(ui.selectedBranch());
                }),
                Ui.button("+ Nhánh mới...", ui::nextSibling),
                Ui.button("✏ Đổi tên...", () -> {
                    ui.useSource(tree);
                    String b = ui.selectedBranch();
                    if (b != null) {
                        ui.renameBranchDialog(b);
                    }
                }),
                Ui.button("🔍 Xem ở tab API", () -> ui.jumpToGroup(BranchTree.groupOf(tree.selectedNode()))),
                exportMenu(ui),
                Ui.button("↻ Refresh", () -> {
                    stale = true;
                    nextOk = 0;
                    refreshIfVisible();
                }),
                Ui.button("+", "Mở tất cả các nhánh", () -> tree.expandFolders(true)),
                Ui.button("−", "Thu gọn tất cả", () -> tree.expandFolders(false)),
                Ui.info(Help.SELECT, "Chọn nhiều"));
        panel.add(bar, BorderLayout.NORTH);
        JPanel treePanel = new JPanel(new BorderLayout());
        treePanel.setBorder(BorderFactory.createTitledBorder(
                "Cây phân cấp Sub-folder & API (Chuột phải vào mục bất kỳ để Gửi / Đổi tên / Xem chi tiết)"));
        treePanel.add(MainPanel.scroll(tree.tree), BorderLayout.CENTER);
        summary.setEditable(false);
        summary.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel sum = new JPanel(new BorderLayout());
        sum.setBorder(BorderFactory.createTitledBorder(
                "Bản tổng hợp Audit (Danh sách Chức năng, API theo thứ tự & kết quả các Role)"));
        sum.add(MainPanel.scroll(summary), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, sum);
        split.setResizeWeight(0.42);
        panel.add(split, BorderLayout.CENTER);
    }

    private static javax.swing.JButton exportMenu(MainPanel ui) {
        javax.swing.JButton b = new javax.swing.JButton("📄 Xuất ▾");
        b.setToolTipText("Xuất Excel (.xlsx), báo cáo (.txt / .md) hoặc ma trận JSON");
        b.addActionListener(e -> {
            javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
            m.add(Ui.item("📊 Xuất Excel (.xlsx)...", ui::exportExcel));
            m.add(Ui.item("📄 Export Báo cáo (.txt/.md)...", ui::exportHistory));
            m.add(Ui.item("{ } Ma trận Role x API (.json)...", ui::exportMatrix));
            m.show(b, 0, b.getHeight());
        });
        return b;
    }

    /** Rebuilt when shown and something changed; big trees are spaced out by their own cost. */
    void refreshIfVisible() {
        if (!stale || !tree.tree.isShowing() || System.currentTimeMillis() < nextOk) {
            return;
        }
        stale = false;
        long t0 = System.nanoTime();
        tree.rebuild(engine.config().showGhosts);
        int caret = summary.getCaretPosition();
        String text = Reports.history(engine.branches(), engine.groups());
        if (!text.equals(summary.getText())) {
            summary.setText(text);
            summary.setCaretPosition(Math.min(caret, text.length()));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        nextOk = System.currentTimeMillis() + Math.min(5000, ms * 8);
    }
}
