package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

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
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumnModel;

import themythofcrawl.app.Changes;
import themythofcrawl.app.Engine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.ui.kit.ThemedTable;

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
    private final JComboBox<String> colour = new JComboBox<>(MainPanel.AUDIT_FILTER_LABELS);
    private final JButton pill = new JButton("📁 Chưa chọn API Group ▾");
    private final JPanel roleTabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
    private final JLabel reqHeader = Ui.bold(" Request (Sửa trực tiếp & bấm '▶ Send Tab này')");
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
        AuditRenderer br = new AuditRenderer(branchModel, null, 3, null);
        for (int i = 0; i < branchTable.getColumnCount(); i++) {
            branchTable.getColumnModel().getColumn(i).setCellRenderer(br);
        }
        Ui.widths(branchTable, 56, 165, 50, 155, 115);
        Ui.onPopup(branchTable, e -> branchMenu().show(e.getComponent(), e.getX(), e.getY()));
        leftTabs.addTab("Cây Sub-folder", MainPanel.scroll(tree.tree));
        leftTabs.addTab("Bảng Nhánh", MainPanel.scroll(branchTable));
        leftTabs.addChangeListener(e -> refreshVisible(true));
        JPanel left = new JPanel(new BorderLayout(2, 2));
        left.setBorder(BorderFactory.createTitledBorder("Cây phân cấp Sub-folder (Chuột phải để Gửi / Thêm / Sửa / Xóa)"));
        left.add(leftTabs, BorderLayout.CENTER);
        JButton more = Ui.compact(new JButton("Thao tác ▾"));
        more.addActionListener(e -> branchMenu().show(more, 0, more.getHeight()));
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
        AuditRenderer gr = new AuditRenderer(groupModel, 7, 3, 4, 0);
        int[] widths = {72, 46, 200, 185, 68, 130, 235, 48, 62, 95, 58, 52, 60, 230, 34, 100, 56};
        TableColumnModel cm = table.getColumnModel();
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);   // keep readable widths, scroll sideways
        for (int i = 0; i < GroupTableModel.COLS.length; i++) {
            cm.getColumn(i).setPreferredWidth(widths[i]);
            cm.getColumn(i).setCellRenderer(gr);
        }
        cm.getColumn(GroupTableModel.COPY_COL).setMaxWidth(40);
        cm.getColumn(GroupTableModel.COPY_COL).setCellRenderer(new AuditRenderer(groupModel, null, null, null, AuditRenderer.Mode.COPY)
                .tooltip(r -> "Bấm để copy: " + engine.copyLabel(groupModel.at(r), false)));
        cm.getColumn(GroupTableModel.EXCEL_COL).setMinWidth(70);
        cm.getColumn(GroupTableModel.EXCEL_COL).setCellRenderer(new AuditRenderer(groupModel, null, null, null, AuditRenderer.Mode.LINK)
                .tooltip(r -> excelTip(groupModel.at(r))));
        cm.getColumn(GroupTableModel.DUP_COL).setMinWidth(52);
        cm.getColumn(GroupTableModel.DUP_COL).setCellRenderer(new AuditRenderer(groupModel, null, null, null, AuditRenderer.Mode.DUP)
                .tooltip(r -> groupModel.at(r) != null && groupModel.at(r).dupTotal() > 1 ? "API trùng: bấm để xem nhanh các bản" : null));
        pathEditor.setEditable(true);
        cm.getColumn(GroupTableModel.PATH_COL).setCellEditor(new DefaultCellEditor(pathEditor));
        // 'Copy' first, 'Excel' and 'Trùng' right after Method (model indexes unchanged)
        cm.moveColumn(GroupTableModel.COPY_COL, 0);
        cm.moveColumn(GroupTableModel.EXCEL_COL, 6);
        cm.moveColumn(GroupTableModel.DUP_COL, 7);
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
        JPanel groupPanel = new JPanel(new BorderLayout());
        groupPanel.setBorder(BorderFactory.createTitledBorder(
                "Danh sách API (Click đúp cột 'Group Path' để chọn Nhánh | Chuột phải để Gửi / Đẩy Repeater)"));
        groupPanel.add(MainPanel.scroll(table), BorderLayout.CENTER);
        JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, groupPanel);
        top.setResizeWeight(0.30);

        // bottom: API gốc + roles, grouped Repeater bar, viewers
        Ui.style(results);
        results.setAutoCreateRowSorter(true);
        results.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        results.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showResult(selectedResult());
                refreshBar();
            }
        });
        Ui.onDoubleClick(results, this::pushSelectedRoles);
        Ui.onPopup(results, e -> resultMenu().show(e.getComponent(), e.getX(), e.getY()));
        AuditRenderer rr = new AuditRenderer(resultModel, 4, 2, null);
        for (int i = 0; i < results.getColumnCount(); i++) {
            results.getColumnModel().getColumn(i).setCellRenderer(rr);
        }
        Ui.widths(results, 66, 185, 210, 80, 55, 75, 85, 70, 50, 340);
        JPanel resPanel = new JPanel(new BorderLayout());
        resPanel.setBorder(BorderFactory.createTitledBorder(
                "So sánh 1 API GỐC (Acc Crawl) & N Roles (Click đúp hoặc Chuột phải để Gửi / Đẩy Repeater)"));
        resPanel.add(MainPanel.scroll(results), BorderLayout.CENTER);

        pill.setBackground(new Color(218, 206, 252));
        pill.setForeground(new Color(55, 20, 120));
        pill.setFont(pill.getFont().deriveFont(Font.BOLD));
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
        JButton sendOne = Ui.accent("▶ Send Tab này", Palette.SEND_ORANGE, this::sendCurrentTab);
        JButton barMore = new JButton("⋯ Thêm ▾");
        barMore.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            m.add(Ui.item("↺ Khôi phục Request về trạng thái ban đầu", this::resetRequest));
            m.add(Ui.item("📤 Đẩy Tab Role hiện tại -> Burp Repeater", this::pushSelectedRoles));
            m.add(Ui.item("📤 Đẩy cả API Group -> Burp Repeater", () -> ui.push(currentGroup())));
            m.show(barMore, 0, barMore.getHeight());
        });
        JPanel bar = new JPanel(new BorderLayout(4, 1));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bar.add(Ui.flow(FlowLayout.LEFT, pill), BorderLayout.WEST);
        bar.add(tabsScroll, BorderLayout.CENTER);
        bar.add(Ui.flow(FlowLayout.RIGHT, sendOne, Ui.button("▶▶ Send Group", () -> ui.send(listOf(currentGroup()))), barMore),
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
        JButton sendApi = Ui.accent("▶ Gửi API (N Roles)", Palette.SEND_ORANGE, () -> ui.send(selectedOrScope()));
        findSendBranch = Ui.button("▶▶ Gửi Nhánh", this::sendBranchSelection);
        JButton sendMenu = new JButton("▶ Gửi ▾");
        sendMenu.addActionListener(e -> sendMenu().show(sendMenu, 0, sendMenu.getHeight()));
        JButton repMenu = new JButton("📤 Burp Repeater ▾");
        repMenu.addActionListener(e -> repeaterMenu().show(repMenu, 0, repMenu.getHeight()));
        JButton tools = new JButton("🛠 Công cụ ▾");
        tools.addActionListener(e -> toolsMenu().show(tools, 0, tools.getHeight()));
        JButton copy = Ui.button("📋 Copy STT + Path", "Copy 'STT. path' của API / nhánh đang chọn (Ctrl+Shift+C)",
                () -> ui.copy(ui.scope().groups()));
        viewMode.addActionListener(e -> applyViewMode());
        colour.addActionListener(e -> {
            int i = colour.getSelectedIndex();
            groupModel.setAuditFilter(MainPanel.AUDIT_FILTER_LEVELS[i], i == 0);
        });
        JPanel bar = new JPanel(new BorderLayout(4, 2));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bar.add(Ui.flow(FlowLayout.LEFT, sendApi, findSendBranch, sendMenu, repMenu, tools, copy, Ui.info(Help.COPY, "Copy")),
                BorderLayout.WEST);
        bar.add(Ui.flow(FlowLayout.RIGHT, new JLabel("Xem:"), viewMode, new JLabel("Màu:"), colour), BorderLayout.EAST);
        return bar;
    }

    // =================================================================== refresh
    void reloadAll() {
        groupModel.setAll(engine.groups());
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
            treeStale = true;
            branchStale = true;
            if (resultModel.group() != null && engine.group(resultModel.group().gid()) == null) {
                showGroup(null);
            }
        } else if (!b.added().isEmpty()) {
            groupModel.addAll(b.added());
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
            colour.setSelectedIndex(0);
            viewMode.setSelectedIndex(0);
        }
        MainPanel.selectRow(table, groupModel.rowOf(g));
        if (r != null) {
            MainPanel.selectRow(results, resultModel.rowOf(r));
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
        refreshBar();
    }

    void filterPending() {
        colour.setSelectedIndex(MainPanel.AUDIT_FILTER_LABELS.length - 1);
    }

    private void showAll() {
        viewMode.setSelectedIndex(0);
        groupModel.setBranchFilter(null);
        branchTable.clearSelection();
    }

    private void applyViewMode() {
        if (viewMode.getSelectedIndex() == 0) {
            groupModel.setBranchFilter(null);
        } else {
            groupModel.setBranchFilter(ui.selectedBranch());
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
                findSendBranch.setText("▶▶ Gửi Tất cả (" + engine.groups().size() + " API)");
                if (viewMode.getSelectedIndex() == 1) {
                    groupModel.setBranchFilter(null);
                }
            }
            case FOLDER -> {
                String p = (String) n.payload;
                findSendBranch.setText("▶▶ Gửi \"" + Text.shortPath("/" + Hierarchy.leaf(p), 19).substring(1) + "\" ("
                        + engine.groupsUnder(p).size() + " API)");
                findSendBranch.setToolTipText("Gửi đệ quy toàn bộ nhánh: " + p);
                if (viewMode.getSelectedIndex() == 1) {
                    groupModel.setBranchFilter(p);
                }
            }
            default -> {
                ApiGroup g = BranchTree.groupOf(n);
                if (g != null) {
                    findSendBranch.setText("▶▶ Gửi API #" + g.gid());
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
        reqHeader.setText(" Request [" + Text.groupId(g.gid()) + " | " + r.roleName() + "] (Có thể sửa trực tiếp & bấm '▶ Send Tab này')");
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
            pill.setText("📁 Chưa chọn API Group ▾");
        } else {
            List<RoleResult> entries = g.entries();
            pill.setText("📁 " + Text.groupId(g.gid()) + ": " + g.method() + " " + Text.shortPath(g.path(), 26) + " ("
                    + entries.size() + ") " + (expandedTabs ? "▾" : "▸"));
            if (expandedTabs) {
                RoleResult cur = selectedResult();
                Palette pal = Palette.ui();
                for (RoleResult r : entries) {
                    String lbl = (r.original() ? "📌 GỐC" : r.roleName()) + (r.status() != null ? " (" + r.status() + ")" : "");
                    JButton b = new JButton(lbl);
                    b.setMargin(new Insets(2, 7, 2, 7));
                    b.setFont(b.getFont().deriveFont(r == cur ? Font.BOLD : Font.PLAIN, 11f));
                    AuditLevel lvl = Audit.level(g, r);
                    if (r == cur) {
                        b.setBackground(new Color(255, 140, 60));
                        b.setForeground(Color.WHITE);
                    } else if (r.original()) {
                        b.setBackground(Palette.mix(pal.bg, pal.origAccent, 0.15));
                        b.setForeground(pal.origFg);
                    } else if (pal.tinted(lvl)) {
                        b.setBackground(pal.background(lvl, 1));
                        b.setForeground(pal.auditFg(lvl));
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
        m.add(Ui.item(expandedTabs ? "Thu gọn các Tab Role trong Group này" : "Mở rộng các Tab Role trong Group này", () -> {
            expandedTabs = !expandedTabs;
            refreshBar();
        }));
        ApiGroup g = currentGroup();
        if (g != null) {
            m.add(Ui.item("▶▶ Gửi (Send) toàn bộ " + g.results().size() + " Roles của " + Text.groupId(g.gid()),
                    () -> engine.replay(g, engine.defaultTargets(g, false))));
            m.add(Ui.item("📤 Đẩy Group " + Text.groupId(g.gid()) + " sang Burp Repeater (Tên gọn + Copy tên Group)", () -> ui.push(g)));
        }
        List<ApiGroup> all = engine.groups();
        if (!all.isEmpty()) {
            m.addSeparator();
            for (ApiGroup x : all.subList(Math.max(0, all.size() - 25), all.size())) {
                m.add(Ui.item("📁 " + Text.groupId(x.gid()) + " [" + x.branch() + "]: " + x.method() + " " + Text.shortPath(x.path(), 30)
                        + " (" + x.results().size() + " Roles)", () -> select(x, null)));
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
        m.add(Ui.item("▶ Gửi " + n + " API đang chọn (N Roles)", () -> ui.send(gs)));
        m.add(Ui.item("📤 Đẩy " + n + " API đang chọn -> Burp Repeater", () -> ui.push(gs)));
        m.add(Ui.item("📋 Copy STT + Path (" + n + " API)     Ctrl+Shift+C", () -> ui.copy(gs)));
        m.add(Ui.item("🎯 Đẩy / Gửi " + n + " API có chọn Role (API gốc / role nào)...",
                () -> ui.pushDialog(false, gs, gs.size() + " API đang chọn")));
        m.add(Ui.item("🎛 Bật / Tắt Role cho " + n + " API đang chọn...",
                () -> ui.roleScopeDialog(false, RoleScopes.Scope.API, null, gs, null)));
        m.addSeparator();
        ui.placeItems(m, gs);
        m.addSeparator();
        m.add(Ui.item("📁 Đổi Group Path cho API đang chọn...", () -> ui.moveDialog(gs)));
        m.add(Ui.item("🔄 Rebuild Roles cho API đang chọn", () -> ui.rebuild(gs)));
        m.add(Ui.item("📌 Xem API Gốc (Acc Crawl)", this::showOriginal));
        m.addSeparator();
        m.add(Ui.item("🗑 Xóa API đang chọn", () -> ui.delete(gs)));
        return m;
    }

    private JPopupMenu resultMenu() {
        JPopupMenu m = new JPopupMenu();
        ApiGroup g = currentGroup();
        m.add(Ui.item("▶ Gửi lại Role đang chọn", () -> {
            if (g != null) {
                engine.replay(g, selectedResults());
            }
        }));
        m.add(Ui.item("▶▶ Gửi toàn bộ Roles của API này", () -> ui.send(listOf(g))));
        m.addSeparator();
        m.add(Ui.item("📤 Đẩy Role đang chọn -> Burp Repeater", this::pushSelectedRoles));
        m.add(Ui.item("📤 Đẩy cả API Group -> Burp Repeater", () -> ui.push(g)));
        m.addSeparator();
        m.add(Ui.item("📌 Xem API Gốc (Acc Crawl)", this::showOriginal));
        return m;
    }

    private void showOriginal() {
        if (currentGroup() != null) {
            results.setRowSelectionInterval(0, 0);
        }
    }

    private JPopupMenu sendMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("🎯 Gửi có chọn Role (API gốc / role nào / bỏ role nào)...", () -> ui.pushDialog(true, null, null)));
        m.addSeparator();
        m.add(Ui.item("▶ Gửi API đang chọn (Tất cả Roles)", () -> ui.send(selectedOrScope())));
        m.add(Ui.item("▶ Gửi lại Role đang chọn", () -> {
            if (currentGroup() != null) {
                engine.replay(currentGroup(), selectedResults());
            }
        }));
        m.add(Ui.item("▶▶ Gửi toàn bộ Nhánh đang chọn (Đệ quy tất cả cấp con)", this::sendBranchSelection));
        m.addSeparator();
        m.add(Ui.item("▶ Gửi tất cả request CHƯA GỬI (crawl lúc đang dừng)", () -> ui.flash("▶ Xếp hàng " + engine.sendAllUnsent()
                + " request chưa gửi")));
        m.add(Ui.item("✖ Hủy hàng đợi gửi", () -> engine.cancelPending("Đã hủy")));
        m.add(Ui.item("▶▶ Gửi tất cả API đã bắt (" + engine.groups().size() + " API)", () -> ui.send(engine.groups())));
        return m;
    }

    private JPopupMenu repeaterMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("🎯 Đẩy có chọn Role (API gốc / role nào / bỏ role nào)...", () -> ui.pushDialog(false, null, null)));
        m.addSeparator();
        m.add(Ui.item("📤 Đẩy API đang chọn -> Burp Repeater", () -> ui.push(selectedOrScope())));
        m.add(Ui.item("📤 Đẩy Role đang chọn -> Burp Repeater", this::pushSelectedRoles));
        m.add(Ui.item("📤 Đẩy cả Nhánh đang chọn -> Burp Repeater", () -> ui.push(ui.scope().groups())));
        m.addSeparator();
        for (int i = 0; i < MainPanel.REPEATER_MODES.length; i++) {
            int idx = i;
            m.add(Ui.item((ui.repeaterMode == i ? "● " : "○ ") + MainPanel.REPEATER_MODES[i], () -> ui.repeaterMode = idx));
        }
        return m;
    }

    private JPopupMenu toolsMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("📁 Đổi Group Path cho API đang chọn...", () -> ui.moveDialog(selectedOrScope())));
        m.add(Ui.item("🔄 Rebuild Roles cho API đang chọn (Áp dụng lại cấu hình Role)", () -> ui.rebuild(selectedOrScope())));
        m.add(Ui.item("📌 Xem Request/Response của API Gốc (Acc Crawl)", this::showOriginal));
        m.addSeparator();
        m.add(Ui.item("📊 Xuất Excel (.xlsx): STT / Method / Path / Vị trí...", ui::exportExcel));
        m.add(Ui.item("💾 Export Matrix JSON...", ui::exportMatrix));
        m.add(Ui.item("📄 Export Báo cáo Lịch sử (.txt/.md)...", ui::exportHistory));
        m.addSeparator();
        m.add(Ui.item("🗑 Xóa API đang chọn", () -> ui.delete(selectedGroups())));
        m.add(Ui.item("♻ Reset Dedup Cache", engine::resetDedup));
        m.add(Ui.item("🧹 Clear All (Xóa toàn bộ API & Nhánh)", () -> {
            if (ui.confirmDialog("Xóa toàn bộ các API đã bắt, đặt lại danh sách Nhánh về mặc định và xóa Dedup cache?\n"
                    + "(Vị trí giữ chỗ vẫn được nhớ)")) {
                engine.clearAll();
            }
        }));
        return m;
    }

    private JPopupMenu branchMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("▶ Gửi toàn bộ Nhánh đang chọn", this::sendBranchSelection));
        m.add(Ui.item("📤 Đẩy Nhánh đang chọn -> Burp Repeater", () -> ui.push(ui.scope().groups())));
        m.add(Ui.item("🎯 Đẩy / Gửi Nhánh có chọn Role...", () -> ui.pushDialog(false, null, null)));
        m.add(Ui.item("➜ Đặt làm ĐÍCH cho request mới", ui::destinationFromTree));
        m.add(Ui.item("➕ Thêm Sub-folder con vào Nhánh đang chọn...", () -> ui.addSubfolder(ui.selectedBranch())));
        m.add(Ui.item("✏ Đổi tên Nhánh đang chọn...", () -> {
            String b = ui.selectedBranch();
            if (b != null) {
                ui.renameBranchDialog(b);
            }
        }));
        m.add(Ui.item("⚙ Quản lý các cấp Sub-folder...", () -> LevelsDialog.show(ui.root(), engine)));
        m.add(Ui.item("🗑 Xóa Nhánh đang chọn...", () -> ui.deleteBranchDialog(ui.selectedBranch())));
        m.add(Ui.item("👁 Xem tất cả API (Bỏ lọc Nhánh)", this::showAll));
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
