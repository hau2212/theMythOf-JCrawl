package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.event.RowSorterEvent;
import javax.swing.event.RowSorterListener;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;

import themythofcrawl.app.Changes;
import themythofcrawl.app.Engine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/** 'API' tab: branch tree / table, API list, the selected API's rows (API gốc + roles), grouped Repeater, viewers. */
final class ApiTab {
    final JPanel panel = new JPanel(new BorderLayout());
    private final MainPanel ui;
    private final Engine engine;
    final BranchTree tree;
    final GroupTableModel groupModel;
    final JTable table;
    final BranchTableModel branchModel;
    final JTable branchTable;
    final ResultTableModel resultModel = new ResultTableModel();
    final JTable results;
    private final JTabbedPane leftTabs = new JTabbedPane();
    private final JComboBox<String> viewMode = new JComboBox<>(new String[] {"Theo từng API (Mặc định)", "Lọc theo Nhánh đang chọn"});
    private final EnumSet<AuditLevel> activeFilters = EnumSet.noneOf(AuditLevel.class);
    private final Pill filterAll = new Pill("Tất cả", t -> t.accent).setSolidWhenActive(true).setActive(true);
    private final Map<AuditLevel, Pill> filterPills = new EnumMap<>(AuditLevel.class);
    private final SearchField search = new SearchField("Tìm API (#, method, path)...", 16);
    private final JLabel filterCount = Ui.muted("0 API");
    private final Pill resOkPill = new Pill("OK 0", t -> t.success, t -> Icons.check(t.success.text(), 12));
    private final Pill resInfoPill = new Pill("Lệch 0", t -> t.warning, t -> Icons.alertMark(t.warning.text(), 12));
    private final Pill resWarnPill = new Pill("Bypass 0", t -> t.critical, t -> Icons.warn(t.critical.text(), 12));
    private final Pill resErrPill = new Pill("Lỗi 0", t -> t.danger, t -> Icons.error(t.danger.text(), 12));
    private final JLabel resMetaLabel = Ui.muted("Chưa chọn API");
    private final JButton pill = new JButton("Chưa chọn API Group") {
        @Override
        public void updateUI() {
            super.updateUI();
            Theme t = Theme.current();
            setBackground(t.dup.fill());
            setForeground(t.dup.text());
            setFont(t.boldFont);
        }
    };
    private final JPanel roleTabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1)) {
        @Override
        public void updateUI() {
            super.updateUI();
            if (ApiTab.this.ui != null) {
                refreshBar();
            }
        }
    };
    private final JLabel reqHeader = Ui.bold(" Request (Sửa trực tiếp & bấm 'Send Tab này')");
    private final JLabel respHeader = Ui.bold(" Response");
    private final UiHost.Viewer reqViewer;
    private final UiHost.Viewer respViewer;
    private final JComboBox<String> pathEditor = new JComboBox<>();
    private boolean expandedTabs = true;
    private boolean treeStale = true;
    private boolean branchStale = true;
    private boolean selecting;

    ApiTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        tree = new BranchTree(engine);
        tree.onMove(ui::moveViaDragDrop);
        groupModel = new GroupTableModel(engine, g -> engine.hits().count(g.target()));
        table = new ThemedTable(groupModel);
        branchModel = new BranchTableModel(engine);
        branchTable = new ThemedTable(branchModel);
        results = new ThemedTable(resultModel);
        reqViewer = ui.host.requestViewer(true);
        respViewer = ui.host.responseViewer();
        panel.add(actionBar(), BorderLayout.NORTH);

        // left: branch tree + branch table
        tree.onChip((c, g, t, x, y) -> ui.chip(c, g, t, x, y));
        tree.tree.addTreeSelectionListener(e -> onTreeSelected());
        tree.tree.addMouseListener(popup(tree.tree, this::treePopup));
        Ui.key(tree.tree, "ctrl shift C", () -> {
            ui.useSource(tree);
            ui.copy(ui.scope().groups());
        });
        Ui.style(branchTable);
        branchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        branchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onBranchRowSelected();
            }
        });
        AuditRenderer br = new AuditRenderer(branchModel, null, 3, null).numberCols(2);
        for (int i = 0; i < branchTable.getColumnCount(); i++) {
            branchTable.getColumnModel().getColumn(i).setCellRenderer(br);
        }
        Ui.widths(branchTable, 64, 165, 54, 165, 115);
        ColumnMemory.install(branchTable, "branches", ui.uiPrefs);
        Ui.onPopup(branchTable, e -> branchMenu().show(e.getComponent(), e.getX(), e.getY()));
        leftTabs.addTab("Cây Sub-folder", MainPanel.scroll(tree.tree));
        leftTabs.addTab("Bảng Nhánh", MainPanel.scroll(branchTable));
        leftTabs.addChangeListener(e -> refreshVisible(true));
        JPanel left = new JPanel(new BorderLayout(2, 2));
        left.setBorder(BorderFactory.createTitledBorder("Cây phân cấp Sub-folder (Chuột phải để Gửi / Thêm / Sửa / Xóa)"));
        left.add(leftTabs, BorderLayout.CENTER);
        JButton[] moreRef = new JButton[1];
        JButton more = Ui.compact(Ui.dropdown("Thao tác", null, "Thao tác nhánh",
                () -> branchMenu().show(moreRef[0], 0, moreRef[0].getHeight())));
        moreRef[0] = more;
        left.add(Ui.flow(FlowLayout.LEFT, Ui.compact(Ui.button("+ Sub-folder", () -> ui.addSubfolder(ui.selectedBranch()))),
                Ui.compact(Ui.button("+ Nhánh", ui::nextSibling)), Ui.compact(Ui.button("Xem tất cả", this::showAll)), more,
                Ui.info(Help.SELECT, "Chọn nhiều")), BorderLayout.SOUTH);

        // right: the API table
        Ui.style(table);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !selecting) {
                ui.useSource(table);
                List<ApiGroup> sel = selectedGroups();
                showGroup(sel.isEmpty() ? null : sel.get(0));
            }
        });
        Ui.onPopup(table, e -> groupMenu().show(e.getComponent(), e.getX(), e.getY()));
        Ui.key(table, "ctrl shift C", () -> {
            ui.useSource(table);
            ui.copy(selectedGroups());
        });
        AuditRenderer gr = new AuditRenderer(groupModel, 7, 3, 4, 0)
                .pathCol(6, r -> {
                    ApiGroup g = groupModel.at(r);
                    return g == null ? null : g.url();
                })
                .numberCols(0, 8)
                .timeCols(12);
        int[] widths = {72, 46, 200, 195, 72, 130, 235, 50, 68, 95, 58, 52, 66, 230, 44, 115, 70};
        TableColumnModel cm = table.getColumnModel();
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);   // keep readable widths, scroll sideways
        for (int i = 0; i < GroupTableModel.COLS.length; i++) {
            cm.getColumn(i).setPreferredWidth(widths[i]);
            cm.getColumn(i).setCellRenderer(gr);
        }
        cm.getColumn(GroupTableModel.COPY_COL).setMinWidth(40);
        cm.getColumn(GroupTableModel.COPY_COL).setMaxWidth(48);
        cm.getColumn(GroupTableModel.COPY_COL).setCellRenderer(new AuditRenderer(groupModel, null, null, null, AuditRenderer.Mode.COPY)
                .tooltip(r -> "Bấm để copy: " + engine.copyLabel(groupModel.at(r), false)));
        cm.getColumn(GroupTableModel.EXCEL_COL).setMinWidth(84);
        cm.getColumn(GroupTableModel.EXCEL_COL).setCellRenderer(new AuditRenderer(groupModel, null, null, null, AuditRenderer.Mode.LINK)
                .tooltip(r -> excelTip(groupModel.at(r))));
        cm.getColumn(GroupTableModel.DUP_COL).setMinWidth(62);
        cm.getColumn(GroupTableModel.DUP_COL).setCellRenderer(new AuditRenderer(groupModel, null, null, null, AuditRenderer.Mode.DUP)
                .tooltip(r -> groupModel.at(r) != null && groupModel.at(r).dupTotal() > 1 ? "API trùng: bấm để xem nhanh các bản" : null));
        pathEditor.setEditable(true);
        cm.getColumn(GroupTableModel.PATH_COL).setCellEditor(new DefaultCellEditor(pathEditor));
        // 'Copy' first, 'Excel' and 'Trùng' right after Method (model indexes unchanged)
        cm.moveColumn(GroupTableModel.COPY_COL, 0);
        cm.moveColumn(GroupTableModel.EXCEL_COL, 6);
        cm.moveColumn(GroupTableModel.DUP_COL, 7);
        ColumnMemory.install(table, "api", ui.uiPrefs);
        MouseAdapter cells = new MouseAdapter() {
            private int[] cell(MouseEvent e) {
                int vr = table.rowAtPoint(e.getPoint());
                int vc = table.columnAtPoint(e.getPoint());
                if (vr < 0 || vc < 0) {
                    return null;
                }
                int mc = table.convertColumnIndexToModel(vc);
                return mc == GroupTableModel.COPY_COL || mc == GroupTableModel.EXCEL_COL || mc == GroupTableModel.DUP_COL
                        ? new int[] {table.convertRowIndexToModel(vr), mc} : null;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int[] c = cell(e);
                if (c == null || e.getClickCount() != 1 || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                ApiGroup g = groupModel.at(c[0]);
                BranchTree.Chip chip = c[1] == GroupTableModel.COPY_COL ? BranchTree.Chip.COPY
                        : c[1] == GroupTableModel.EXCEL_COL ? BranchTree.Chip.EXCEL : BranchTree.Chip.DUP;
                ui.chip(chip, g, table, e.getX(), e.getY());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                table.setCursor(Cursor.getPredefinedCursor(cell(e) != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        };
        table.addMouseListener(cells);
        table.addMouseMotionListener(cells);
        JPanel groupPanel = new JPanel(new BorderLayout(0, 2));
        groupPanel.setBorder(BorderFactory.createTitledBorder(
                "Danh sách API (Click đúp cột 'Group Path' để chọn Nhánh | Chuột phải để Gửi / Đẩy Repeater)"));
        groupPanel.add(buildFilterStrip(), BorderLayout.NORTH);
        groupPanel.add(MainPanel.scroll(table), BorderLayout.CENTER);
        JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, groupPanel);
        top.setResizeWeight(0.30);

        // bottom: API gốc + roles, grouped Repeater bar, viewers
        Ui.style(results);
        results.setRowSorter(new PinnedOrigRowSorter(resultModel));
        results.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        results.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showResult(selectedResult());
                refreshBar();
            }
        });
        Ui.onDoubleClick(results, this::pushSelectedRoles);
        Ui.onPopup(results, e -> resultMenu().show(e.getComponent(), e.getX(), e.getY()));
        AuditRenderer rr = new AuditRenderer(resultModel, 4, 2, null).numberCols(5, 7, 8).deltaCol(6);
        for (int i = 0; i < results.getColumnCount(); i++) {
            results.getColumnModel().getColumn(i).setCellRenderer(rr);
        }
        Ui.widths(results, 72, 185, 220, 82, 58, 78, 92, 74, 54, 340);
        ColumnMemory.install(results, "results", ui.uiPrefs);
        JPanel resPanel = new JPanel(new BorderLayout());
        resPanel.setBorder(BorderFactory.createTitledBorder(
                "So sánh 1 API GỐC (Acc Crawl) & N Roles (Click đúp hoặc Chuột phải để Gửi / Đẩy Repeater)"));
        resPanel.add(buildResultSummaryBar(), BorderLayout.NORTH);
        resPanel.add(MainPanel.scroll(results), BorderLayout.CENTER);

        pill.setIcon(Icons.themed(t -> Icons.pair(Icons.folder(t.dup.text()),
                expandedTabs ? Icons.chevronDown(t.dup.text(), 10) : Icons.chevronRight(t.dup.text(), 10), 4)));
        pill.addActionListener(e -> pillMenu());
        JScrollPane tabsScroll = new JScrollPane(roleTabs, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tabsScroll.setBorder(BorderFactory.createEmptyBorder());
        tabsScroll.setPreferredSize(new Dimension(420, 32));
        tabsScroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 8));   // thin, under the chips
        tabsScroll.getHorizontalScrollBar().setUnitIncrement(40);
        roleTabs.addContainerListener(new java.awt.event.ContainerAdapter() {
            @Override
            public void componentAdded(java.awt.event.ContainerEvent e) {
                int h = roleTabs.getPreferredSize().height + 10;
                if (tabsScroll.getPreferredSize().height != h) {
                    tabsScroll.setPreferredSize(new Dimension(420, h));
                    tabsScroll.revalidate();
                }
            }
        });
        JButton sendOne = Ui.accent("Send Tab này", t -> Icons.send(t.critical.onSolid()), t -> t.critical, this::sendCurrentTab);
        JButton[] barMoreRef = new JButton[1];
        JButton barMore = Ui.dropdown("Thêm", null, "Thao tác với API Group / Tab", () -> {
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("Khôi phục Request về trạng thái ban đầu", Icons::undo, this::resetRequest));
            m.add(Ui.item("Đẩy Tab Role hiện tại -> Burp Repeater", Icons::repeater, this::pushSelectedRoles));
            m.add(Ui.item("Đẩy cả API Group -> Burp Repeater", Icons::repeater, () -> ui.push(currentGroup())));
            m.show(barMoreRef[0], 0, barMoreRef[0].getHeight());
        });
        barMoreRef[0] = barMore;
        JPanel bar = new JPanel(new BorderLayout(4, 1));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bar.add(Ui.flow(FlowLayout.LEFT, pill), BorderLayout.WEST);
        bar.add(tabsScroll, BorderLayout.CENTER);
        bar.add(Ui.flow(FlowLayout.RIGHT, sendOne,
                Ui.button("Send Group", t -> Icons.sendAll(t.fg), () -> ui.send(listOf(currentGroup()))), barMore),
                BorderLayout.EAST);
        JPanel reqPanel = new JPanel(new BorderLayout());
        reqPanel.add(reqHeader, BorderLayout.NORTH);
        reqPanel.add(reqViewer.component(), BorderLayout.CENTER);
        JPanel respPanel = new JPanel(new BorderLayout());
        respPanel.add(respHeader, BorderLayout.NORTH);
        respPanel.add(respViewer.component(), BorderLayout.CENTER);
        JSplitPane viewers = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqPanel, respPanel);
        viewers.setResizeWeight(0.5);
        JPanel repeater = new JPanel(new BorderLayout(2, 2));
        repeater.add(bar, BorderLayout.NORTH);
        repeater.add(viewers, BorderLayout.CENTER);
        JSplitPane detail = new JSplitPane(JSplitPane.VERTICAL_SPLIT, resPanel, repeater);
        detail.setResizeWeight(0.30);
        JSplitPane master = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, detail);
        master.setResizeWeight(0.36);
        panel.add(master, BorderLayout.CENTER);
    }

    private JButton findSendBranch;

    private JPanel actionBar() {
        JButton sendApi = Ui.accent("Gửi API (N Roles)", t -> Icons.send(t.critical.onSolid()), t -> t.critical,
                () -> ui.send(selectedOrScope()));
        sendApi.setToolTipText("Gửi API / phạm vi đang chọn với N Roles (Ctrl+Enter)");
        findSendBranch = Ui.button("Gửi Nhánh", t -> Icons.sendAll(t.fg), this::sendBranchSelection);
        JButton[] sendRef = new JButton[1];
        JButton sendMenu = Ui.dropdown("Gửi", t -> Icons.withChevron(Icons.send(t.fg), t.fg), "Tùy chọn gửi request (Ctrl+Enter)",
                () -> sendMenu().show(sendRef[0], 0, sendRef[0].getHeight()));
        sendRef[0] = sendMenu;
        JButton[] repRef = new JButton[1];
        JButton repMenu = Ui.dropdown("Burp Repeater", t -> Icons.withChevron(Icons.repeater(t.fg), t.fg), "Đẩy sang Burp Repeater (Ctrl+R)",
                () -> repeaterMenu().show(repRef[0], 0, repRef[0].getHeight()));
        repRef[0] = repMenu;
        JButton[] toolsRef = new JButton[1];
        JButton tools = Ui.dropdown("Công cụ", t -> Icons.withChevron(Icons.gear(t.fg), t.fg), "Công cụ & xuất báo cáo",
                () -> toolsMenu().show(toolsRef[0], 0, toolsRef[0].getHeight()));
        toolsRef[0] = tools;
        JButton copy = Ui.button("Copy STT + Path", t -> Icons.copy(t.fg), "Copy 'STT. path' của API / nhánh đang chọn (Ctrl+Shift+C)",
                () -> ui.copy(ui.scope().groups()));
        viewMode.addActionListener(e -> applyViewMode());
        JPanel bar = new JPanel(new BorderLayout(4, 2));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bar.add(Ui.flow(FlowLayout.LEFT, sendApi, findSendBranch, sendMenu, repMenu, tools, copy, Ui.info(Help.COPY, "Copy")),
                BorderLayout.WEST);
        bar.add(Ui.flow(FlowLayout.RIGHT, new JLabel("Xem:"), viewMode), BorderLayout.EAST);
        return bar;
    }

    private JPanel buildFilterStrip() {
        search.setToolTipText("Lọc nhanh bảng API theo #, method, path, nhánh hoặc Excel STT (Ctrl+F)");
        search.onChange(q -> {
            groupModel.setTextFilter(q);
            updateFilterStrip();
        });

        filterAll.setPadding(1, 8, 1, 8);
        filterAll.setToolTipText("Hiển thị tất cả API (bỏ lọc trạng thái)");
        filterAll.onClick(this::clearAuditFilters);

        registerFilterPill(AuditLevel.OK, "OK", "Lọc API: Tất cả Role 2xx (OK)", t -> t.success, t -> Icons.check(t.success.text(), 12));
        registerFilterPill(AuditLevel.INFO, "Lệch", "Lọc API: Có Role lỗi / lệch body", t -> t.warning, t -> Icons.alertMark(t.warning.text(), 12));
        registerFilterPill(AuditLevel.WARN, "Bypass", "Lọc API: Nghi vấn Bypass (Guest 2xx)", t -> t.critical, t -> Icons.warn(t.critical.text(), 12));
        registerFilterPill(AuditLevel.ERROR, "Lỗi", "Lọc API: Có lỗi 5xx / Mất kết nối", t -> t.danger, t -> Icons.error(t.danger.text(), 12));
        registerFilterPill(AuditLevel.NONE, "Chưa gửi", "Lọc API: Chưa gửi / chưa replay", t -> t.neutral, t -> Icons.statusDot(t.dotNone, 10));
        registerFilterPill(AuditLevel.PENDING, "Chờ XN", "Lọc API: Chờ xác nhận vị trí / trùng", t -> t.pending, t -> Icons.hourglass(t.pending.text(), 11));

        JPanel chips = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        chips.setOpaque(false);
        chips.add(search);
        chips.add(Ui.muted("Lọc:"));
        chips.add(filterAll);
        for (Pill p : filterPills.values()) {
            chips.add(p);
        }

        JPanel strip = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 6));
        strip.add(chips, BorderLayout.CENTER);
        strip.add(filterCount, BorderLayout.EAST);
        return strip;
    }

    SearchField searchField() {
        return search;
    }

    private void registerFilterPill(AuditLevel lvl, String label, String tip,
                                    java.util.function.Function<Theme, Theme.Token> token,
                                    java.util.function.Function<Theme, javax.swing.Icon> icon) {
        Pill p = new Pill(label, token, icon);
        p.setPadding(1, 7, 1, 7);
        p.setToolTipText(tip);
        p.onClick(() -> toggleAuditFilter(lvl));
        filterPills.put(lvl, p);
    }

    private JPanel buildResultSummaryBar() {
        for (Pill p : new Pill[] {resOkPill, resInfoPill, resWarnPill, resErrPill}) {
            p.setPadding(1, 7, 1, 7);
        }
        JPanel bar = new JPanel(new BorderLayout(Theme.SPACE_2, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.current().grid),
                        BorderFactory.createEmptyBorder(2, 6, 3, 6)));
            }
        };
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.current().grid),
                BorderFactory.createEmptyBorder(2, 6, 3, 6)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        left.setOpaque(false);
        left.add(resOkPill);
        left.add(resInfoPill);
        left.add(resWarnPill);
        left.add(resErrPill);
        bar.add(left, BorderLayout.WEST);
        bar.add(resMetaLabel, BorderLayout.EAST);
        return bar;
    }

    // =================================================================== refresh
    void reloadAll() {
        groupModel.setAll(engine.groups());
        updateFilterStrip();
        updateResultSummary(currentGroup());
        treeStale = true;
        branchStale = true;
        refreshVisible(true);
    }

    void apply(Changes.Batch b) {
        if (b.structure()) {
            List<ApiGroup> sel = selectedGroups();
            selecting = true;
            try {
                groupModel.setAll(engine.groups());
                for (ApiGroup g : sel) {
                    int r = groupModel.rowOf(g);
                    if (r >= 0) {
                        int v = table.convertRowIndexToView(r);
                        table.addRowSelectionInterval(v, v);
                    }
                }
            } finally {
                selecting = false;
            }
            updateFilterStrip();
            treeStale = true;
            branchStale = true;
            if (resultModel.group() != null && engine.group(resultModel.group().gid()) == null) {
                showGroup(null);
            }
        } else if (!b.added().isEmpty()) {
            groupModel.addAll(b.added());
            updateFilterStrip();
            treeStale = true;
            branchStale = true;
            if (table.getSelectedRow() < 0 && table.getRowCount() > 0) {
                table.setRowSelectionInterval(0, 0);
            }
        }
        if (!b.dirty().isEmpty()) {
            groupModel.updated(b.dirty());
            treeStale = true;
            branchStale = true;
            ApiGroup cur = resultModel.group();
            if (cur != null && b.dirty().contains(cur)) {
                RoleResult keep = selectedResult();
                resultModel.refresh();
                updateResultSummary(cur);
                if (keep != null && results.getSelectedRow() < 0) {
                    MainPanel.selectRow(results, resultModel.rowOf(keep));
                }
                refreshBar();
                if (keep != null) {
                    showResponse(keep);
                }
            }
        }
    }

    void refreshVisible(boolean force) {
        if (treeStale && tree.tree.isShowing()) {
            treeStale = false;
            tree.rebuild(engine.config().showGhosts);
        }
        if (branchStale && branchTable.isShowing()) {
            branchStale = false;
            branchModel.reload();
        }
        if (force || treeStale) {
            List<String> paths = new ArrayList<>(engine.knownPaths());
            if (paths.size() != pathEditor.getItemCount()) {
                pathEditor.removeAllItems();
                for (String p : paths) {
                    pathEditor.addItem(p);
                }
            }
        }
    }

    // =================================================================== selection
    List<ApiGroup> selectedGroups() {
        List<ApiGroup> out = new ArrayList<>();
        for (int v : table.getSelectedRows()) {
            ApiGroup g = groupModel.at(table.convertRowIndexToModel(v));
            if (g != null) {
                out.add(g);
            }
        }
        return out;
    }

    private List<ApiGroup> selectedOrScope() {
        List<ApiGroup> s = selectedGroups();
        return !s.isEmpty() ? s : ui.scope().groups();
    }

    Branch selectedBranchRow() {
        int v = branchTable.getSelectedRow();
        return v < 0 ? null : branchModel.at(branchTable.convertRowIndexToModel(v));
    }

    ApiGroup currentGroup() {
        return resultModel.group();
    }

    RoleResult selectedResult() {
        int v = results.getSelectedRow();
        return v < 0 ? null : resultModel.at(results.convertRowIndexToModel(v));
    }

    List<RoleResult> selectedResults() {
        List<RoleResult> out = new ArrayList<>();
        for (int v : results.getSelectedRows()) {
            out.add(resultModel.at(results.convertRowIndexToModel(v)));
        }
        return out;
    }

    void select(ApiGroup g, RoleResult r) {
        if (groupModel.rowOf(g) < 0) {
            groupModel.setBranchFilter(null);
            clearAuditFilters();
            viewMode.setSelectedIndex(0);
        }
        MainPanel.selectRow(table, groupModel.rowOf(g));
        if (r != null) {
            MainPanel.selectRow(results, resultModel.rowOf(r));
        }
    }

    void selectBranch(String branchPath) {
        if (branchPath == null || branchPath.isBlank()) {
            return;
        }
        leftTabs.setSelectedIndex(0);
        if (treeStale) {
            treeStale = false;
            tree.rebuild(engine.config().showGhosts);
        }
        tree.selectBranchPath(branchPath);
        if (viewMode.getSelectedIndex() == 1) {
            groupModel.setBranchFilter(branchPath);
            updateFilterStrip();
        }
    }

    void showGroup(ApiGroup g) {
        if (g != resultModel.group()) {
            resultModel.setGroup(g);
            if (resultModel.getRowCount() > 0) {
                results.setRowSelectionInterval(0, 0);
            } else {
                showResult(null);
            }
        }
        updateResultSummary(g);
        refreshBar();
    }

    private void updateResultSummary(ApiGroup g) {
        if (g == null) {
            resOkPill.setText("OK 0");
            resInfoPill.setText("Lệch 0");
            resWarnPill.setText("Bypass 0");
            resErrPill.setText("Lỗi 0");
            resMetaLabel.setText("Chưa chọn API");
            return;
        }
        int ok = 0;
        int info = 0;
        int warn = 0;
        int err = 0;
        for (RoleResult r : g.results()) {
            if (ResultState.ERROR.equals(r.state()) || (r.status() != null && r.status() >= 500)) {
                err++;
            } else {
                AuditLevel lvl = Audit.level(g, r);
                if (lvl == AuditLevel.WARN) {
                    warn++;
                } else if (lvl == AuditLevel.OK) {
                    ok++;
                } else if (lvl == AuditLevel.INFO || lvl == AuditLevel.REDIRECT) {
                    info++;
                }
            }
        }
        resOkPill.setText("OK " + ok);
        resInfoPill.setText("Lệch " + info);
        resWarnPill.setText("Bypass " + warn);
        resErrPill.setText("Lỗi " + err);
        resMetaLabel.setText(Text.groupId(g.gid()) + " · " + BranchTree.rolesLabel(g));
    }

    void filterPending() {
        selectSingleFilter(AuditLevel.PENDING);
    }

    void filterLevel(AuditLevel lvl) {
        if (lvl == null) {
            clearAuditFilters();
        } else {
            selectSingleFilter(lvl);
        }
    }

    void toggleAuditFilter(AuditLevel lvl) {
        if (lvl == null) {
            clearAuditFilters();
            return;
        }
        if (!activeFilters.add(lvl)) {
            activeFilters.remove(lvl);
        }
        applyFilters();
    }

    void clearAuditFilters() {
        activeFilters.clear();
        applyFilters();
    }

    private void selectSingleFilter(AuditLevel lvl) {
        activeFilters.clear();
        if (lvl != null) {
            activeFilters.add(lvl);
        }
        applyFilters();
    }

    Set<AuditLevel> activeAuditFilters() {
        return Collections.unmodifiableSet(EnumSet.copyOf(activeFilters));
    }

    private void applyFilters() {
        groupModel.setAuditFilters(activeFilters);
        updateFilterStrip();
    }

    private void updateFilterStrip() {
        filterAll.setActive(activeFilters.isEmpty());
        for (Map.Entry<AuditLevel, Pill> en : filterPills.entrySet()) {
            en.getValue().setActive(activeFilters.contains(en.getKey()));
        }
        int shown = groupModel.getRowCount();
        int total = groupModel.total();
        filterCount.setText(shown == total ? total + " API" : shown + " / " + total + " API");
    }

    private void showAll() {
        viewMode.setSelectedIndex(0);
        groupModel.setBranchFilter(null);
        branchTable.clearSelection();
        updateFilterStrip();
    }

    private void applyViewMode() {
        if (viewMode.getSelectedIndex() == 0) {
            groupModel.setBranchFilter(null);
        } else {
            groupModel.setBranchFilter(ui.selectedBranch());
        }
        updateFilterStrip();
    }

    /** RowSorter wrapping {@link TableRowSorter} so model row 0 (API gốc) always stays pinned at view row 0. */
    private static final class PinnedOrigRowSorter extends RowSorter<ResultTableModel> {
        private final TableRowSorter<ResultTableModel> delegate;
        private int origView;

        PinnedOrigRowSorter(ResultTableModel model) {
            this.delegate = new TableRowSorter<>(model);
            this.delegate.addRowSorterListener(new RowSorterListener() {
                @Override
                public void sorterChanged(RowSorterEvent e) {
                    recomputeOrig();
                    if (e.getType() == RowSorterEvent.Type.SORT_ORDER_CHANGED) {
                        fireSortOrderChanged();
                    } else {
                        fireRowSorterChanged(null);
                    }
                }
            });
            recomputeOrig();
        }

        private void recomputeOrig() {
            int n = delegate.getViewRowCount();
            origView = n > 0 ? delegate.convertRowIndexToView(0) : -1;
        }

        private boolean hasActiveSort() {
            for (SortKey k : delegate.getSortKeys()) {
                if (k.getSortOrder() != SortOrder.UNSORTED) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public ResultTableModel getModel() {
            return delegate.getModel();
        }

        @Override
        public void toggleSortOrder(int column) {
            delegate.toggleSortOrder(column);
        }

        @Override
        public int convertRowIndexToModel(int index) {
            if (index <= 0 || !hasActiveSort() || origView < 0) {
                return delegate.getViewRowCount() > 0 && index == 0 ? 0 : index;
            }
            int dv = index - 1 < origView ? index - 1 : index;
            return delegate.convertRowIndexToModel(dv);
        }

        @Override
        public int convertRowIndexToView(int index) {
            if (index <= 0 || !hasActiveSort() || origView < 0) {
                return index;
            }
            int dv = delegate.convertRowIndexToView(index);
            if (dv < 0) {
                return dv;
            }
            return dv < origView ? dv + 1 : dv;
        }

        @Override
        public void setSortKeys(List<? extends SortKey> keys) {
            delegate.setSortKeys(keys);
        }

        @Override
        public List<? extends SortKey> getSortKeys() {
            return delegate.getSortKeys();
        }

        @Override
        public int getViewRowCount() {
            return delegate.getViewRowCount();
        }

        @Override
        public int getModelRowCount() {
            return delegate.getModelRowCount();
        }

        @Override
        public void modelStructureChanged() {
            delegate.modelStructureChanged();
            recomputeOrig();
        }

        @Override
        public void allRowsChanged() {
            delegate.allRowsChanged();
            recomputeOrig();
        }

        @Override
        public void rowsInserted(int firstRow, int endRow) {
            delegate.rowsInserted(firstRow, endRow);
            recomputeOrig();
        }

        @Override
        public void rowsDeleted(int firstRow, int endRow) {
            delegate.rowsDeleted(firstRow, endRow);
            recomputeOrig();
        }

        @Override
        public void rowsUpdated(int firstRow, int endRow) {
            delegate.rowsUpdated(firstRow, endRow);
            recomputeOrig();
        }

        @Override
        public void rowsUpdated(int firstRow, int endRow, int column) {
            delegate.rowsUpdated(firstRow, endRow, column);
            recomputeOrig();
        }
    }

    private void onTreeSelected() {
        if (tree.restoring()) {
            return;
        }
        ui.useSource(tree);
        if (tree.selectionCount() > 1) {
            ui.flash("Đang chọn " + tree.selectionCount() + " mục trong cây = " + tree.selectedGroups().size()
                    + " API (Ctrl / Shift + click để thêm bớt)");
        }
        Node n = tree.selectedNode();
        if (n == null) {
            return;
        }
        switch (n.kind) {
            case ROOT -> {
                findSendBranch.setText("Gửi Tất cả (" + engine.groups().size() + " API)");
                if (viewMode.getSelectedIndex() == 1) {
                    groupModel.setBranchFilter(null);
                }
            }
            case FOLDER -> {
                String p = (String) n.payload;
                findSendBranch.setText("Gửi \"" + Text.shortPath("/" + Hierarchy.leaf(p), 19).substring(1) + "\" ("
                        + engine.groupsUnder(p).size() + " API)");
                findSendBranch.setToolTipText("Gửi đệ quy toàn bộ nhánh: " + p);
                if (viewMode.getSelectedIndex() == 1) {
                    groupModel.setBranchFilter(p);
                }
            }
            default -> {
                ApiGroup g = BranchTree.groupOf(n);
                if (g != null) {
                    findSendBranch.setText("Gửi API #" + g.gid());
                    selecting = true;
                    try {
                        select(g, null);
                    } finally {
                        selecting = false;
                    }
                    showGroup(g);
                    if (n.payload instanceof Object[] arr && arr.length > 1 && arr[1] instanceof RoleResult r) {
                        MainPanel.selectRow(results, resultModel.rowOf(r));
                    }
                    ui.useSource(tree);
                }
            }
        }
    }

    private void onBranchRowSelected() {
        Branch b = selectedBranchRow();
        ui.useSource(branchTable);
        if (b == null) {
            if (branchTable.getSelectedRow() == 0) {
                groupModel.setBranchFilter(null);
            }
            return;
        }
        groupModel.setBranchFilter(b.name());
    }

    private void sendBranchSelection() {
        MainPanel.Scope s = ui.scope();
        if (s.groups().isEmpty()) {
            ui.info("Chọn một nhánh trong cây hoặc bảng nhánh trước.");
            return;
        }
        ui.send(s.groups());
    }

    // =================================================================== viewers + grouped repeater
    private void showResult(RoleResult r) {
        ApiGroup g = currentGroup();
        if (g == null || r == null) {
            reqHeader.setText(" Request (Chưa chọn Tab)");
            respHeader.setText(" Response");
            reqViewer.show(null, new byte[0]);
            respViewer.show(null, new byte[0]);
            return;
        }
        reqHeader.setText(" Request [" + Text.groupId(g.gid()) + " | " + r.roleName() + "] (Có thể sửa trực tiếp & bấm 'Send Tab này')");
        reqViewer.show(g.service(), engine.requestOf(g, r));
        showResponse(r);
    }

    private void showResponse(RoleResult r) {
        ApiGroup g = currentGroup();
        if (g == null) {
            return;
        }
        Object st = r.status() != null ? r.status() : r.state();
        respHeader.setText(" Response [" + r.roleName() + " | Status: " + st + " | Length: " + (r.length() != null ? r.length() : "-") + "]");
        respViewer.show(g.service(), engine.responseOf(g, r));
    }

    private void sendCurrentTab() {
        ApiGroup g = currentGroup();
        RoleResult r = selectedResult();
        if (g == null || r == null) {
            ui.info("Chọn một API trước.");
            return;
        }
        byte[] edited = reqViewer.edited();
        if (edited != null && edited.length > 0) {
            engine.setCustomRequest(g, r, edited);
        }
        engine.replay(g, List.of(r));
    }

    private void resetRequest() {
        ApiGroup g = currentGroup();
        RoleResult r = selectedResult();
        if (g != null && r != null) {
            engine.setCustomRequest(g, r, null);
            showResult(r);
        }
    }

    private void pushSelectedRoles() {
        ApiGroup g = currentGroup();
        List<RoleResult> rs = selectedResults();
        if (g == null || rs.isEmpty()) {
            return;
        }
        engine.toRepeater(g, rs, false);
    }

    void refreshBar() {
        ApiGroup g = currentGroup();
        roleTabs.removeAll();
        if (g == null) {
            pill.setText("Chưa chọn API Group");
        } else {
            List<RoleResult> entries = g.entries();
            pill.setText(Text.groupId(g.gid()) + ": " + g.method() + " " + Text.shortPath(g.path(), 26) + " (" + entries.size() + ")");
            if (expandedTabs) {
                RoleResult cur = selectedResult();
                Theme th = Theme.current();
                for (RoleResult r : entries) {
                    String lbl = (r.original() ? "GỐC" : r.roleName()) + (r.status() != null ? " (" + r.status() + ")" : "");
                    JButton b = new JButton(lbl);
                    b.setMargin(new Insets(2, 7, 2, 7));
                    b.setFont(r == cur ? th.smallBoldFont : th.smallFont);
                    AuditLevel lvl = Audit.level(g, r);
                    if (r == cur) {
                        b.setBackground(th.critical.solidFill());
                        b.setForeground(th.critical.onSolid());
                    } else if (r.original()) {
                        b.setBackground(th.orig.fillStrong());
                        b.setForeground(th.orig.text());
                    } else if (th.tinted(lvl)) {
                        Theme.Token tok = th.audit(lvl);
                        b.setBackground(tok.fill());
                        b.setForeground(tok.text());
                    }
                    if (r.original()) {
                        Color pinCol = r == cur ? th.critical.onSolid() : th.orig.text();
                        b.setIcon(Icons.pin(pinCol, 12));
                    }
                    b.addActionListener(e -> MainPanel.selectRow(results, resultModel.rowOf(r)));
                    roleTabs.add(b);
                }
            }
        }
        roleTabs.revalidate();
        roleTabs.repaint();
    }

    private void pillMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item(expandedTabs ? "Thu gọn các Tab Role trong Group này" : "Mở rộng các Tab Role trong Group này",
                t -> expandedTabs ? Icons.chevronDown(t.fg, 12) : Icons.chevronRight(t.fg, 12), () -> {
                    expandedTabs = !expandedTabs;
                    refreshBar();
                }));
        ApiGroup g = currentGroup();
        if (g != null) {
            m.add(Ui.item("Gửi (Send) toàn bộ " + g.results().size() + " Roles của " + Text.groupId(g.gid()),
                    Icons::sendAll, () -> engine.replay(g, engine.defaultTargets(g, false))));
            m.add(Ui.item("Đẩy Group " + Text.groupId(g.gid()) + " sang Burp Repeater (Tên gọn + Copy tên Group)",
                    Icons::repeater, () -> ui.push(g)));
        }
        List<ApiGroup> all = engine.groups();
        if (!all.isEmpty()) {
            m.addSeparator();
            for (ApiGroup x : all.subList(Math.max(0, all.size() - 25), all.size())) {
                m.add(Ui.item(Text.groupId(x.gid()) + " [" + x.branch() + "]: " + x.method() + " " + Text.shortPath(x.path(), 30)
                        + " (" + x.results().size() + " Roles)", Icons::folder, () -> select(x, null)));
            }
        }
        m.show(pill, 0, pill.getHeight());
    }

    // =================================================================== menus
    private static MouseAdapter popup(javax.swing.JTree t, java.util.function.Consumer<MouseEvent> show) {
        return new MouseAdapter() {
            private void trigger(MouseEvent e) {
                if (e.isPopupTrigger() || (SwingUtilities.isRightMouseButton(e) && e.getID() == MouseEvent.MOUSE_PRESSED)) {
                    int row = t.getClosestRowForLocation(e.getX(), e.getY());
                    if (row >= 0 && !t.isRowSelected(row)) {
                        t.setSelectionRow(row);
                    }
                    show.accept(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                trigger(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    trigger(e);
                }
            }
        };
    }

    static MouseAdapter treePopup(javax.swing.JTree t, java.util.function.Consumer<MouseEvent> show) {
        return popup(t, show);
    }

    private void treePopup(MouseEvent e) {
        ui.useSource(tree);
        TreeMenus.show(ui, tree, e.getComponent(), e.getX(), e.getY());
    }

    private JPopupMenu groupMenu() {
        ui.useSource(table);
        List<ApiGroup> gs = selectedGroups();
        int n = Math.max(1, gs.size());
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Gửi " + n + " API đang chọn (N Roles)", Icons::send, () -> ui.send(gs)));
        m.add(Ui.item("Đẩy " + n + " API đang chọn -> Burp Repeater", Icons::repeater, () -> ui.push(gs)));
        m.add(Ui.item("Copy STT + Path (" + n + " API)     Ctrl+Shift+C", Icons::copy, () -> ui.copy(gs)));
        m.add(Ui.item("Đẩy / Gửi " + n + " API có chọn Role (API gốc / role nào)...",
                Icons::target, () -> ui.pushDialog(false, gs, gs.size() + " API đang chọn")));
        m.add(Ui.item("Bật / Tắt Role cho " + n + " API đang chọn...",
                Icons::sliders, () -> ui.roleScopeDialog(false, RoleScopes.Scope.API, null, gs, null)));
        m.addSeparator();
        ui.placeItems(m, gs);
        m.addSeparator();
        m.add(Ui.item("Đổi Group Path cho API đang chọn...", Icons::folder, () -> ui.moveDialog(gs)));
        m.add(Ui.item("Rebuild Roles cho API đang chọn", Icons::refresh, () -> ui.rebuild(gs)));
        m.add(Ui.item("Xem API Gốc (Acc Crawl)", Icons::pin, this::showOriginal));
        m.addSeparator();
        m.add(Ui.item("Xóa API đang chọn", t -> Icons.trash(t.danger.text()), () -> ui.delete(gs)));
        return m;
    }

    private JPopupMenu resultMenu() {
        JPopupMenu m = new JPopupMenu();
        ApiGroup g = currentGroup();
        m.add(Ui.item("Gửi lại Role đang chọn", Icons::send, () -> {
            if (g != null) {
                engine.replay(g, selectedResults());
            }
        }));
        m.add(Ui.item("Gửi toàn bộ Roles của API này", Icons::sendAll, () -> ui.send(listOf(g))));
        m.addSeparator();
        m.add(Ui.item("Đẩy Role đang chọn -> Burp Repeater", Icons::repeater, this::pushSelectedRoles));
        m.add(Ui.item("Đẩy cả API Group -> Burp Repeater", Icons::repeater, () -> ui.push(g)));
        m.addSeparator();
        m.add(Ui.item("Xem API Gốc (Acc Crawl)", Icons::pin, this::showOriginal));
        return m;
    }

    private void showOriginal() {
        if (currentGroup() != null) {
            results.setRowSelectionInterval(0, 0);
        }
    }

    private JPopupMenu sendMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Gửi có chọn Role (API gốc / role nào / bỏ role nào)...", Icons::target, () -> ui.pushDialog(true, null, null)));
        m.addSeparator();
        m.add(Ui.item("Gửi API đang chọn (Tất cả Roles)", Icons::send, () -> ui.send(selectedOrScope())));
        m.add(Ui.item("Gửi lại Role đang chọn", Icons::send, () -> {
            if (currentGroup() != null) {
                engine.replay(currentGroup(), selectedResults());
            }
        }));
        m.add(Ui.item("Gửi toàn bộ Nhánh đang chọn (Đệ quy tất cả cấp con)", Icons::sendAll, this::sendBranchSelection));
        m.addSeparator();
        m.add(Ui.item("Gửi tất cả request CHƯA GỬI (crawl lúc đang dừng)", Icons::send,
                () -> ui.flash("Xếp hàng " + engine.sendAllUnsent() + " request chưa gửi")));
        m.add(Ui.item("Hủy hàng đợi gửi", t -> Icons.stop(t.danger.text()), () -> engine.cancelPending("Đã hủy")));
        m.add(Ui.item("Gửi tất cả API đã bắt (" + engine.groups().size() + " API)", Icons::sendAll, () -> ui.send(engine.groups())));
        return m;
    }

    private JPopupMenu repeaterMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Đẩy có chọn Role (API gốc / role nào / bỏ role nào)...", Icons::target, () -> ui.pushDialog(false, null, null)));
        m.addSeparator();
        m.add(Ui.item("Đẩy API đang chọn -> Burp Repeater", Icons::repeater, () -> ui.push(selectedOrScope())));
        m.add(Ui.item("Đẩy Role đang chọn -> Burp Repeater", Icons::repeater, this::pushSelectedRoles));
        m.add(Ui.item("Đẩy cả Nhánh đang chọn -> Burp Repeater", Icons::repeater, () -> ui.push(ui.scope().groups())));
        m.addSeparator();
        for (int i = 0; i < MainPanel.REPEATER_MODES.length; i++) {
            int idx = i;
            m.add(Ui.item(MainPanel.REPEATER_MODES[i],
                    t -> Icons.circle(ui.repeaterMode == idx ? t.accent.text() : t.muted, ui.repeaterMode == idx),
                    () -> ui.repeaterMode = idx));
        }
        return m;
    }

    private JPopupMenu toolsMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Đổi Group Path cho API đang chọn...", Icons::folder, () -> ui.moveDialog(selectedOrScope())));
        m.add(Ui.item("Rebuild Roles cho API đang chọn (Áp dụng lại cấu hình Role)", Icons::refresh, () -> ui.rebuild(selectedOrScope())));
        m.add(Ui.item("Xem Request/Response của API Gốc (Acc Crawl)", Icons::pin, this::showOriginal));
        m.addSeparator();
        m.add(Ui.item("Xuất Excel (.xlsx): STT / Method / Path / Vị trí...", Icons::excel, ui::exportExcel));
        m.add(Ui.item("Export Matrix JSON...", Icons::save, ui::exportMatrix));
        m.add(Ui.item("Export Báo cáo Lịch sử (.txt/.md)...", Icons::document, ui::exportHistory));
        m.addSeparator();
        m.add(Ui.item("Xóa API đang chọn", t -> Icons.trash(t.danger.text()), () -> ui.delete(selectedGroups())));
        m.add(Ui.item("Reset Dedup Cache", Icons::refresh, engine::resetDedup));
        m.add(Ui.item("Clear All (Xóa toàn bộ API & Nhánh)", Icons::clean, () -> {
            if (ui.confirmDialog("Xóa toàn bộ các API đã bắt, đặt lại danh sách Nhánh về mặc định và xóa Dedup cache?\n"
                    + "(Vị trí giữ chỗ vẫn được nhớ)")) {
                engine.clearAll();
            }
        }));
        return m;
    }

    private JPopupMenu branchMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Gửi toàn bộ Nhánh đang chọn", Icons::send, this::sendBranchSelection));
        m.add(Ui.item("Đẩy Nhánh đang chọn -> Burp Repeater", Icons::repeater, () -> ui.push(ui.scope().groups())));
        m.add(Ui.item("Đẩy / Gửi Nhánh có chọn Role...", Icons::target, () -> ui.pushDialog(false, null, null)));
        m.add(Ui.item("Đặt làm ĐÍCH cho request mới", Icons::move, ui::destinationFromTree));
        m.add(Ui.item("Thêm Sub-folder con vào Nhánh đang chọn...", Icons::plus, () -> ui.addSubfolder(ui.selectedBranch())));
        m.add(Ui.item("Đổi tên Nhánh đang chọn...", Icons::edit, () -> {
            String b = ui.selectedBranch();
            if (b != null) {
                ui.renameBranchDialog(b);
            }
        }));
        m.add(Ui.item("Quản lý các cấp Sub-folder...", Icons::gear, () -> LevelsDialog.show(ui.root(), engine)));
        m.add(Ui.item("Xóa Nhánh đang chọn...", t -> Icons.trash(t.danger.text()), () -> ui.deleteBranchDialog(ui.selectedBranch())));
        m.add(Ui.item("Xem tất cả API (Bỏ lọc Nhánh)", Icons::show, this::showAll));
        return m;
    }

    private String excelTip(ApiGroup g) {
        if (g == null || g.target() == null) {
            return engine.targets() != null ? "API không có trong file Excel" : null;
        }
        return "Bấm để mở dòng STT " + g.target().sttText() + " trong tab Excel (" + g.target().methodLabel() + " "
                + g.target().path() + ") · đã gặp " + engine.hits().count(g.target()) + " lần";
    }

    static List<ApiGroup> listOf(ApiGroup g) {
        return g == null ? List.of() : List.of(g);
    }
}
