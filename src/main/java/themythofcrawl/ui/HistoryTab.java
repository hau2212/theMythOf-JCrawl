package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JSplitPane;

import themythofcrawl.app.Engine;
import themythofcrawl.model.ApiGroup;

/**
 * 'Lịch sử' tab: the branch tree (right-click for every action) next to the visual audit report
 * (KPIs, branch cards with role heatmap, Role x API matrix, and copyable text report).
 */
final class HistoryTab {
    final JPanel panel = new JPanel(new BorderLayout(4, 4));
    private final MainPanel ui;
    private final Engine engine;
    final BranchTree tree;
    final HistoryReportView report;
    boolean stale = true;
    private long nextOk;

    HistoryTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        tree = new BranchTree(engine);
        report = new HistoryReportView(ui::jumpToGroup, ui::flash);
        tree.onChip((c, g, t, x, y) -> ui.chip(c, g, t, x, y));
        tree.tree.addTreeSelectionListener(e -> {
            if (!tree.restoring()) {
                ui.useSource(tree);
                syncReportToSelection();
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
                Ui.button("Gửi mục đang chọn", t -> Icons.send(t.fg), () -> {
                    ui.useSource(tree);
                    ui.send(ui.scope().groups());
                }),
                Ui.button("Đẩy -> Burp Repeater", t -> Icons.repeater(t.fg), () -> {
                    ui.useSource(tree);
                    ui.push(ui.scope().groups());
                }),
                Ui.button("Đẩy / Gửi có chọn Role...", t -> Icons.target(t.fg), () -> {
                    ui.useSource(tree);
                    ui.pushDialog(false, null, null);
                }),
                Ui.button("Sub-folder con...", t -> Icons.plus(t.fg), () -> {
                    ui.useSource(tree);
                    ui.addSubfolder(ui.selectedBranch());
                }),
                Ui.button("Nhánh mới...", t -> Icons.plus(t.fg), ui::nextSibling),
                Ui.button("Đổi tên...", t -> Icons.edit(t.fg), () -> {
                    ui.useSource(tree);
                    String b = ui.selectedBranch();
                    if (b != null) {
                        ui.renameBranchDialog(b);
                    }
                }),
                Ui.button("Xem ở tab API", t -> Icons.search(t.info.text()),
                        () -> ui.jumpToGroup(BranchTree.groupOf(tree.selectedNode()))),
                exportMenu(ui),
                Ui.button("Refresh", t -> Icons.refresh(t.fg), () -> {
                    stale = true;
                    nextOk = 0;
                    refreshIfVisible();
                }),
                Ui.button("", t -> Icons.plus(t.fg), "Mở tất cả các nhánh", () -> tree.expandFolders(true)),
                Ui.button("", t -> Icons.minus(t.fg), "Thu gọn tất cả", () -> tree.expandFolders(false)),
                Ui.info(Help.SELECT, "Chọn nhiều"));
        panel.add(bar, BorderLayout.NORTH);
        JPanel treePanel = new JPanel(new BorderLayout());
        treePanel.setBorder(BorderFactory.createTitledBorder(
                "Cây phân cấp Sub-folder & API (Chuột phải vào mục bất kỳ để Gửi / Đổi tên / Xem chi tiết)"));
        treePanel.add(MainPanel.scroll(tree.tree), BorderLayout.CENTER);
        JPanel sum = new JPanel(new BorderLayout());
        sum.setBorder(BorderFactory.createTitledBorder(
                "Bản tổng hợp Audit (Thẻ theo nhánh · Ma trận Role x API · Văn bản)"));
        sum.add(report, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, sum);
        split.setResizeWeight(0.36);
        panel.add(split, BorderLayout.CENTER);
    }

    private void syncReportToSelection() {
        Node n = tree.selectedNode();
        if (n == null) {
            return;
        }
        if (n.kind == Node.Kind.FOLDER) {
            report.scrollToBranchPath((String) n.payload);
        } else {
            ApiGroup g = BranchTree.groupOf(n);
            if (g != null) {
                report.scrollToGroup(g);
            }
        }
    }

    private static javax.swing.JButton exportMenu(MainPanel ui) {
        javax.swing.JButton[] ref = new javax.swing.JButton[1];
        javax.swing.JButton b = Ui.dropdown("Xuất", t -> Icons.withChevron(Icons.export(t.fg), t.fg),
                "Xuất Excel (.xlsx), báo cáo (.txt / .md) hoặc ma trận JSON", () -> {
                    javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
                    m.add(Ui.item("Xuất Excel (.xlsx)...", t -> Icons.excel(t.success.text()), ui::exportExcel));
                    m.add(Ui.item("Export Báo cáo (.txt/.md)...", t -> Icons.document(t.info.text()), ui::exportHistory));
                    m.add(Ui.item("Ma trận Role x API (.json)...", t -> Icons.codeBraces(t.dup.text()), ui::exportMatrix));
                    m.show(ref[0], 0, ref[0].getHeight());
                });
        ref[0] = b;
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
        report.setData(engine.branches(), engine.groups(), engine.targets());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        nextOk = System.currentTimeMillis() + Math.min(5000, ms * 8);
    }
}
