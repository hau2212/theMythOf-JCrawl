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
        tree.onMove(ui::moveViaDragDrop);
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
        javax.swing.JButton[] actionRef = new javax.swing.JButton[1];
        javax.swing.JButton actionBtn = Ui.dropdown("Thao tác Nhánh", t -> Icons.withChevron(Icons.folder(t.fg), t.fg),
                "Thêm Sub-folder, Đổi tên, Xem ở tab API, Mở/Thu gọn các nhánh", () -> {
                    ui.useSource(tree);
                    javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
                    m.add(Ui.item("Xem mục đang chọn ở tab API", t -> Icons.search(t.info.text()),
                            () -> ui.jumpToGroup(BranchTree.groupOf(tree.selectedNode()))));
                    m.add(Ui.item("Đẩy / Gửi có chọn Role...", Icons::target,
                            () -> ui.pushDialog(false, null, null)));
                    m.addSeparator();
                    m.add(Ui.item("Thêm Sub-folder con...", Icons::plus,
                            () -> ui.addSubfolder(ui.selectedBranch())));
                    m.add(Ui.item("Thêm Nhánh mới cùng cấp...", Icons::plus, ui::nextSibling));
                    m.add(Ui.item("Đổi tên Nhánh...", Icons::edit, () -> {
                        String b = ui.selectedBranch();
                        if (b != null) {
                            ui.renameBranchDialog(b);
                        }
                    }));
                    m.addSeparator();
                    m.add(Ui.item("Mở tất cả các nhánh", Icons::plus, () -> tree.expandFolders(true)));
                    m.add(Ui.item("Thu gọn tất cả", Icons::minus, () -> tree.expandFolders(false)));
                    m.addSeparator();
                    m.add(Ui.item("Hướng dẫn Chọn nhiều...", Icons::info,
                            () -> Ui.popover(actionRef[0], "Chọn nhiều", Help.SELECT)));
                    m.show(actionRef[0], 0, actionRef[0].getHeight());
                });
        actionRef[0] = actionBtn;

        JPanel bar = Ui.flow(FlowLayout.LEFT,
                Ui.accent("Gửi mục chọn", t -> Icons.send(t.accent.onSolid()), t -> t.accent, () -> {
                    ui.useSource(tree);
                    ui.send(ui.scope().groups());
                }),
                Ui.button("Repeater", t -> Icons.repeater(t.fg), "Đẩy sang Burp Repeater (Ctrl+R)", () -> {
                    ui.useSource(tree);
                    ui.push(ui.scope().groups());
                }),
                actionBtn,
                exportMenu(ui),
                Ui.button("Refresh", t -> Icons.refresh(t.fg), () -> {
                    stale = true;
                    nextOk = 0;
                    refreshIfVisible();
                }));
        panel.add(bar, BorderLayout.NORTH);
        JPanel treePanel = new JPanel(new BorderLayout());
        treePanel.add(MainPanel.scroll(tree.tree), BorderLayout.CENTER);
        JPanel sum = new JPanel(new BorderLayout());
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
        if (n.kind == Node.Kind.FOLDER || n.kind == Node.Kind.APIS) {
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
