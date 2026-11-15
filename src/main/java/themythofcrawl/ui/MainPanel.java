package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import themythofcrawl.App;
import themythofcrawl.capture.CaptureSettings;
import themythofcrawl.excel.TargetList;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.CapturedApi;
import themythofcrawl.workspace.Workspace;
import themythofcrawl.workspace.WorkspaceStore;

/** The extension's suite tab. All Swing work happens on the EDT; a timer pulls changes from the workspace. */
public final class MainPanel extends JPanel {
    private static final SimpleDateFormat TIME = new SimpleDateFormat("dd/MM HH:mm:ss");

    private final App app;
    private final Host host;

    private final JCheckBox captureBox = new JCheckBox("Capture Proxy");
    private final JLabel workspaceLabel = new JLabel();
    private final JTextField branchField = new JTextField(28);
    private final JLabel statsLabel = new JLabel(" ");
    private final JTabbedPane tabs = new JTabbedPane();

    private final ApiModel apiModel = new ApiModel();
    private final JTable apiTable = new JTable(apiModel);

    private final DefaultTreeModel wsModel = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final JTree wsTree = new JTree(wsModel);
    private final JTextArea wsInfo = new JTextArea();
    private final JLabel rootLabel = new JLabel();

    private final CoverageModel coverageModel = new CoverageModel();
    private final JTable coverageTable = new JTable(coverageModel);
    private final JLabel coverageLabel = new JLabel(" ");
    private final JCheckBox targetOnlyBox = new JCheckBox("Chỉ bắt API có trong danh sách");

    private final SettingsForm settingsForm;
    private final JTextArea logArea = new JTextArea();

    private Workspace shown;
    private long shownRevision = -1;
    private int shownApiRow = -1;

    public MainPanel(App app, Host host) {
        super(new BorderLayout());
        this.app = app;
        this.host = host;
        this.settingsForm = new SettingsForm();
        add(buildHeader(), BorderLayout.NORTH);
        tabs.addTab("API đã bắt", buildApiTab());
        tabs.addTab("Workspace", buildWorkspaceTab());
        tabs.addTab("Độ phủ Excel", buildCoverageTab());
        tabs.addTab("Cấu hình", settingsForm.scroll);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Log", new JScrollPane(logArea));
        add(tabs, BorderLayout.CENTER);
        app.manager().addListener(w -> SwingUtilities.invokeLater(() -> {
            shown = null;
            refresh();
            reloadWorkspaceTree();
        }));
        new Timer(400, e -> refresh()).start();
        reloadWorkspaceTree();
        refresh();
    }

    // ------------------------------------------------------------------ header
    private JComponent buildHeader() {
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        captureBox.setFont(captureBox.getFont().deriveFont(Font.BOLD));
        captureBox.addActionListener(e -> app.updateSettings(app.settings().with("captureEnabled", captureBox.isSelected())));
        row1.add(captureBox);
        row1.add(new JLabel(" |  Workspace:"));
        workspaceLabel.setFont(workspaceLabel.getFont().deriveFont(Font.BOLD));
        row1.add(workspaceLabel);
        JButton change = new JButton("Đổi workspace...");
        change.addActionListener(e -> tabs.setSelectedIndex(1));
        row1.add(change);
        row1.add(new JLabel(" |  Nhánh đang bắt:"));
        branchField.setToolTipText("Nhập đường dẫn nhánh, các cấp ngăn cách bởi '>' rồi nhấn Enter (vd: / > contact > button \"search\")");
        branchField.addActionListener(e -> app.setActiveBranch(branchField.getText()));
        row1.add(branchField);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row2.add(statsLabel);
        JPanel header = new JPanel(new BorderLayout());
        header.add(row1, BorderLayout.NORTH);
        header.add(row2, BorderLayout.SOUTH);
        header.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        return header;
    }

    // ------------------------------------------------------------------ captured APIs
    private final class ApiModel extends AbstractTableModel {
        final String[] cols = {"API #", "Method", "Host", "Path", "Vị trí (nhánh)", "Status gốc", "Body len",
            "STT Excel", "Nguồn", "Thời gian"};
        List<CapturedApi> rows = new ArrayList<>();

        void set(List<CapturedApi> list) {
            int old = rows.size();
            boolean append = list.size() >= old && (old == 0 || list.get(old - 1) == rows.get(old - 1));
            rows = list;
            if (append && list.size() > old) {
                fireTableRowsInserted(old, list.size() - 1);
            } else if (append) {
                if (old > 0) {
                    fireTableRowsUpdated(0, old - 1);
                }
            } else {
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 || c == 5 || c == 6 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int r, int c) {
            CapturedApi a = rows.get(r);
            return switch (c) {
                case 0 -> a.id;
                case 1 -> a.method;
                case 2 -> a.host;
                case 3 -> a.path;
                case 4 -> a.branch;
                case 5 -> a.origStatus >= 0 ? a.origStatus : null;
                case 6 -> a.origLength >= 0 ? a.origLength : null;
                case 7 -> a.targetStt;
                case 8 -> a.source;
                default -> TIME.format(new Date(a.createdMillis));
            };
        }
    }

    private JComponent buildApiTab() {
        Palette.styleTable(apiTable);
        apiTable.setAutoCreateRowSorter(true);
        apiTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        Palette.Renderer r = new Palette.Renderer(1, 5, 0) {
            @Override
            protected Color cellColor(Palette p, int row, int col, Object v) {
                if (col == 4) {
                    return p.levelText(Math.min(3, apiModel.rows.get(row).branch.split(">").length));
                }
                if (col == 7 && v != null) {
                    return p.ok;
                }
                return null;
            }
        };
        int[] widths = {60, 70, 170, 380, 260, 80, 80, 80, 70, 120};
        for (int i = 0; i < widths.length; i++) {
            apiTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            apiTable.getColumnModel().getColumn(i).setCellRenderer(r);
        }
        apiTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedApi();
            }
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton export = new JButton("Xuất Excel (.xlsx)...");
        export.addActionListener(e -> exportExcel());
        JButton delete = new JButton("Xóa API đang chọn");
        delete.addActionListener(e -> deleteSelected());
        bar.add(export);
        bar.add(delete);
        JLabel hint = new JLabel("   Request / response gốc được lưu trong workspace: tắt Burp, mở lại vẫn còn.");
        hint.setForeground(Palette.of(getBackground(), getForeground()).muted);
        bar.add(hint);
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(new JScrollPane(apiTable), BorderLayout.CENTER);
        JSplitPane viewers = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, host.requestViewer(), host.responseViewer());
        viewers.setResizeWeight(0.5);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, viewers);
        split.setResizeWeight(0.55);
        return split;
    }

    private void showSelectedApi() {
        int vr = apiTable.getSelectedRow();
        Workspace w = app.workspace();
        if (vr < 0 || w == null) {
            shownApiRow = -1;
            host.showMessage(null, null, true, "", 443);
            return;
        }
        CapturedApi a = apiModel.rows.get(apiTable.convertRowIndexToModel(vr));
        if (a.id == shownApiRow) {
            return;
        }
        shownApiRow = a.id;
        host.showMessage(app.store().readMessage(w, a.id, "request"), app.store().readMessage(w, a.id, "response"),
                a.secure, a.host, a.port);
    }

    private void deleteSelected() {
        int[] rows = apiTable.getSelectedRows();
        if (rows.length == 0) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Xóa " + rows.length + " API đang chọn khỏi workspace (kể cả request/response đã lưu)?",
                "theMythOfCrawl", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        Set<Integer> ids = new HashSet<>();
        for (int vr : rows) {
            ids.add(apiModel.rows.get(apiTable.convertRowIndexToModel(vr)).id);
        }
        app.deleteApis(ids);
        refresh();
    }

    private void exportExcel() {
        Workspace w = app.workspace();
        if (w == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser(w.dir().toFile());
        chooser.setSelectedFile(w.dir().resolve("theMythOfCrawl_" + safe(w.name()) + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date()) + ".xlsx").toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Excel (.xlsx)", "xlsx"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = chooser.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".xlsx")) {
            f = new File(f.getAbsolutePath() + ".xlsx");
        }
        try {
            Files.write(f.toPath(), app.exportWorkbook());
            host.log("Đã xuất Excel: " + f.getAbsolutePath());
            JOptionPane.showMessageDialog(this, "Đã xuất:\n" + f.getAbsolutePath(), "theMythOfCrawl",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException | RuntimeException ex) {
            error("Xuất Excel thất bại: " + ex.getMessage());
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}_-]+", "_");
    }

    // ------------------------------------------------------------------ workspace browser
    /** Tree node payload: the root folder, a project folder (level 1) or a workspace folder (level 2). */
    private record WsNode(int level, String label, Path dir) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final class SquareIcon implements Icon {
        private final Color color;

        SquareIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(x + 1, y + 1, 10, 10, 4, 4);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 13;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }
    }

    private final class WsRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, focus);
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (!(o instanceof WsNode n)) {
                return this;
            }
            Palette p = Palette.of(tree.getBackground(), tree.getForeground());
            Workspace cur = app.workspace();
            boolean open = n.level == 2 && cur != null && cur.dir().equals(n.dir);
            setFont(tree.getFont().deriveFont(n.level < 2 || open ? Font.BOLD : Font.PLAIN));
            setIcon(new SquareIcon(n.level == 0 ? p.muted : Palette.levelHue(n.level)));
            setText(open ? n.label + "    ● đang mở" : n.label);
            if (!sel) {
                setForeground(n.level == 0 ? p.fg : p.levelText(n.level));
            }
            return this;
        }
    }

    private JComponent buildWorkspaceTab() {
        wsTree.setCellRenderer(new WsRenderer());
        wsTree.setRowHeight(24);
        wsTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        wsTree.addTreeSelectionListener(e -> showWorkspaceInfo());
        wsTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelectedWorkspace();
                }
            }
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton open = new JButton("Mở workspace");
        open.setFont(open.getFont().deriveFont(Font.BOLD));
        open.addActionListener(e -> openSelectedWorkspace());
        JButton create = new JButton("Tạo workspace mới...");
        create.addActionListener(e -> createWorkspace());
        JButton delete = new JButton("Xóa...");
        delete.addActionListener(e -> deleteWorkspace());
        JButton folder = new JButton("Mở thư mục");
        folder.addActionListener(e -> openFolder());
        JButton root = new JButton("Đổi thư mục gốc...");
        root.addActionListener(e -> changeRoot());
        JButton reload = new JButton("Làm mới");
        reload.addActionListener(e -> reloadWorkspaceTree());
        for (JButton b : new JButton[] {open, create, delete, folder, root, reload}) {
            bar.add(b);
        }
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        rootLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        top.add(rootLabel, BorderLayout.SOUTH);
        wsInfo.setEditable(false);
        wsInfo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        wsInfo.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(wsTree), new JScrollPane(wsInfo));
        split.setResizeWeight(0.45);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    public void reloadWorkspaceTree() {
        WorkspaceStore store = app.store();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                new WsNode(0, WorkspaceStore.ROOT_NAME, store.root()));
        TreePath select = null;
        try {
            for (String project : store.projects()) {
                Path pdir = store.projectDir(project);
                DefaultMutableTreeNode pn = new DefaultMutableTreeNode(new WsNode(1, project, pdir));
                for (String ws : store.workspaces(project)) {
                    DefaultMutableTreeNode wn = new DefaultMutableTreeNode(new WsNode(2, ws, pdir.resolve(ws)));
                    pn.add(wn);
                    Workspace cur = app.workspace();
                    if (cur != null && cur.dir().equals(pdir.resolve(ws))) {
                        select = new TreePath(wn.getPath());
                    }
                }
                root.add(pn);
            }
        } catch (IOException e) {
            error("Không đọc được thư mục workspace: " + e.getMessage());
        }
        wsModel.setRoot(root);
        for (int i = 0; i < wsTree.getRowCount(); i++) {
            wsTree.expandRow(i);
        }
        if (select != null) {
            wsTree.setSelectionPath(select);
        }
        rootLabel.setText("Thư mục gốc: " + store.root().toAbsolutePath()
                + "     (cấp 1 = project, cấp 2 = workspace; double-click để mở)");
    }

    private WsNode selectedNode() {
        TreePath p = wsTree.getSelectionPath();
        if (p == null) {
            return null;
        }
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof WsNode n ? n : null;
    }

    private void showWorkspaceInfo() {
        WsNode n = selectedNode();
        if (n == null) {
            wsInfo.setText("");
            return;
        }
        if (n.level < 2) {
            wsInfo.setText((n.level == 0 ? "Thư mục gốc" : "Project") + ":\n  " + n.label + "\n\nĐường dẫn:\n  "
                    + n.dir.toAbsolutePath());
            return;
        }
        try {
            // The open workspace is shown live (its file on disk can lag by one autosave).
            Workspace cur = app.workspace();
            Workspace w = cur != null && cur.dir().equals(n.dir) ? cur : app.store().open(n.dir);
            wsInfo.setText("Workspace: " + w.name() + "\nProject:   " + w.project()
                    + "\n\nSố API đã bắt:   " + w.apiCount()
                    + "\nSố nhánh:        " + w.branches().size()
                    + "\nNhánh đang bắt:  " + w.activeBranch()
                    + "\nDanh sách Excel: " + (w.targetsJson() == null ? "chưa nạp" : "đã nạp")
                    + "\nTạo lúc:         " + TIME.format(new Date(w.created()))
                    + "\n\nĐường dẫn:\n  " + w.dir().toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            wsInfo.setText("Không đọc được workspace: " + e.getMessage());
        }
    }

    private void openSelectedWorkspace() {
        WsNode n = selectedNode();
        if (n == null || n.level != 2) {
            return;
        }
        try {
            app.openWorkspace(n.dir);
            tabs.setSelectedIndex(0);
        } catch (IOException | RuntimeException e) {
            error("Không mở được workspace: " + e.getMessage());
        }
    }

    private void createWorkspace() {
        WsNode n = selectedNode();
        String project = n != null && n.level >= 1
                ? (n.level == 1 ? n.label : n.dir.getParent().getFileName().toString()) : app.projectName();
        JTextField projectField = new JTextField(project, 26);
        JTextField nameField = new JTextField("Dự án " + new SimpleDateFormat("yyyy-MM-dd").format(new Date()), 26);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 3, 3, 3);
        gc.anchor = GridBagConstraints.WEST;
        form.add(new JLabel("Project (cấp 1):"), gc);
        gc.gridx = 1;
        form.add(projectField, gc);
        gc.gridx = 0;
        gc.gridy = 1;
        form.add(new JLabel("Tên workspace (cấp 2):"), gc);
        gc.gridx = 1;
        form.add(nameField, gc);
        if (JOptionPane.showConfirmDialog(this, form, "Tạo workspace mới", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            app.createWorkspace(projectField.getText().trim(), nameField.getText().trim());
            tabs.setSelectedIndex(0);
        } catch (IOException | RuntimeException e) {
            error("Không tạo được workspace: " + e.getMessage());
        }
    }

    private void deleteWorkspace() {
        WsNode n = selectedNode();
        if (n == null || n.level != 2) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Xóa vĩnh viễn workspace '" + n.label + "' và toàn bộ request/response "
                + "đã lưu trong đó?\n" + n.dir.toAbsolutePath(), "theMythOfCrawl", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            app.deleteWorkspace(n.dir);
            reloadWorkspaceTree();
        } catch (IOException | RuntimeException e) {
            error(e.getMessage());
        }
    }

    private void openFolder() {
        WsNode n = selectedNode();
        Path dir = n != null ? n.dir : app.store().root();
        try {
            Files.createDirectories(dir);
            Desktop.getDesktop().open(dir.toFile());
        } catch (IOException | RuntimeException e) {
            error("Không mở được thư mục " + dir + ": " + e.getMessage());
        }
    }

    private void changeRoot() {
        JFileChooser chooser = new JFileChooser(app.store().root().toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Chọn thư mục chứa " + WorkspaceStore.ROOT_NAME);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path base = chooser.getSelectedFile().toPath();
        Path root = base.getFileName() != null && WorkspaceStore.ROOT_NAME.equals(base.getFileName().toString())
                ? base : base.resolve(WorkspaceStore.ROOT_NAME);
        app.changeRoot(root);
        JOptionPane.showMessageDialog(this, "Thư mục gốc mới:\n" + root + "\n\nTải lại extension để áp dụng.",
                "theMythOfCrawl", JOptionPane.INFORMATION_MESSAGE);
    }

    // ------------------------------------------------------------------ Excel coverage
    private final class CoverageModel extends AbstractTableModel {
        final String[] cols = {"STT", "Method", "Path (trong file)", "POSITION", "Trạng thái", "API #",
            "Path thực tế", "Vị trí tìm thấy"};
        List<Object[]> rows = new ArrayList<>();
        List<Boolean> found = new ArrayList<>();

        void set(Map<TargetList.Entry, List<CapturedApi>> cov) {
            rows = new ArrayList<>();
            found = new ArrayList<>();
            for (Map.Entry<TargetList.Entry, List<CapturedApi>> e : cov.entrySet()) {
                TargetList.Entry t = e.getKey();
                List<CapturedApi> apis = e.getValue();
                List<String> labels = new ArrayList<>();
                List<String> positions = new ArrayList<>();
                for (CapturedApi a : apis) {
                    labels.add(a.label());
                    if (!positions.contains(a.branch)) {
                        positions.add(a.branch);
                    }
                }
                rows.add(new Object[] {t.stt, t.methodLabel(), t.path, t.position,
                    apis.isEmpty() ? "Chưa bắt" : "Đã bắt" + (apis.size() > 1 ? " (" + apis.size() + " lần)" : ""),
                    String.join(", ", labels), apis.isEmpty() ? "" : apis.get(0).pathWithoutQuery(),
                    String.join("; ", positions)});
                found.add(!apis.isEmpty());
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            return rows.get(r)[c];
        }
    }

    private JComponent buildCoverageTab() {
        Palette.styleTable(coverageTable);
        coverageTable.setAutoCreateRowSorter(true);
        Palette.Renderer r = new Palette.Renderer(1, -1, 0, 4) {
            @Override
            protected Color rowTint(Palette p, int row) {
                return coverageModel.found.get(row) ? p.okRow : null;
            }

            @Override
            protected Color cellColor(Palette p, int row, int col, Object v) {
                if (col == 4) {
                    return coverageModel.found.get(row) ? p.ok : p.muted;
                }
                return null;
            }
        };
        int[] widths = {50, 80, 320, 240, 130, 110, 280, 240};
        for (int i = 0; i < widths.length; i++) {
            coverageTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            coverageTable.getColumnModel().getColumn(i).setCellRenderer(r);
        }
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton load = new JButton("Nạp file Excel (.xlsx)...");
        load.setFont(load.getFont().deriveFont(Font.BOLD));
        load.addActionListener(e -> loadExcel());
        JButton clear = new JButton("Bỏ danh sách");
        clear.addActionListener(e -> {
            app.clearTargets();
            refresh();
        });
        JButton export = new JButton("Xuất Excel độ phủ (.xlsx)...");
        export.addActionListener(e -> exportExcel());
        targetOnlyBox.addActionListener(e -> app.updateSettings(app.settings().with("targetOnly", targetOnlyBox.isSelected())));
        bar.add(load);
        bar.add(clear);
        bar.add(export);
        bar.add(targetOnlyBox);
        coverageLabel.setFont(coverageLabel.getFont().deriveFont(Font.BOLD));
        coverageLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(coverageLabel, BorderLayout.SOUTH);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(coverageTable), BorderLayout.CENTER);
        return panel;
    }

    private void loadExcel() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Excel (.xlsx)", "xlsx"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        try {
            List<String> sheets = Xlsx.sheetNames(file);
            int index = 0;
            if (sheets.size() > 1) {
                Object pick = JOptionPane.showInputDialog(this, "File có " + sheets.size()
                        + " sheet. Chọn sheet chứa danh sách API:", "theMythOfCrawl", JOptionPane.QUESTION_MESSAGE,
                        null, sheets.toArray(), sheets.get(0));
                if (pick == null) {
                    return;
                }
                index = sheets.indexOf(pick.toString());
            }
            TargetList t = app.loadTargets(file, index);
            int already = 0;
            for (List<CapturedApi> v : app.coverage().values()) {
                if (!v.isEmpty()) {
                    already++;
                }
            }
            int choice = JOptionPane.showConfirmDialog(this, "Đã nạp " + t.size() + " API từ " + t.source()
                    + ".\n" + already + " API trong danh sách đã được bắt từ trước.\n\n"
                    + "Bật chế độ 'Chỉ bắt API có trong danh sách'?\n(Không: vẫn bắt mọi API, danh sách chỉ để theo dõi độ phủ)",
                    "theMythOfCrawl", JOptionPane.YES_NO_OPTION);
            app.updateSettings(app.settings().with("targetOnly", choice == JOptionPane.YES_OPTION));
            refresh();
        } catch (IOException | RuntimeException e) {
            error("Không đọc được file Excel: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ settings
    private final class SettingsForm {
        final JCheckBox scope = new JCheckBox("Chỉ bắt API nằm trong Target Scope của Burp");
        final JCheckBox statics = new JCheckBox("Bỏ qua file tĩnh có đuôi:");
        final JTextField staticExt = new JTextField(50);
        final JCheckBox regexOn = new JCheckBox("Chỉ bắt path khớp Regex:");
        final JTextField regex = new JTextField(50);
        final JLabel regexError = new JLabel("");
        final JCheckBox optionsHead = new JCheckBox("Bỏ qua OPTIONS / HEAD (CORS preflight)");
        final JCheckBox dedup = new JCheckBox("Chỉ bắt mỗi endpoint 1 lần (Method + Host + Path)");
        final JCheckBox dedupIds = new JCheckBox("Coi số / UUID / chuỗi hex dài trong path là {id} khi so trùng");
        final JCheckBox dedupBranch = new JCheckBox("Chống trùng riêng theo từng Nhánh");
        final JCheckBox targetOnly = new JCheckBox("Chỉ bắt API có trong danh sách Excel (thay cho bộ lọc Regex)");
        final JCheckBox targetPosition = new JCheckBox("Tự xếp API vào nhánh theo cột POSITION của file (nếu ô có giá trị)");
        final JCheckBox targetPrefix = new JCheckBox("Cho phép path thực tế có thêm tiền tố phía trước (vd /api/v1 + /apt/test)");
        final JLabel saved = new JLabel(" ");
        final JScrollPane scroll;
        boolean loading;

        SettingsForm() {
            JPanel content = new JPanel();
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            regexError.setForeground(new Color(220, 38, 38));
            content.add(section("1.  Bắt API từ Proxy", new Color(37, 99, 235),
                    "Áp dụng cho workspace đang mở. Bắt thủ công (chuột phải > theMythOfCrawl) luôn bỏ qua bộ lọc.",
                    row(scope), row(statics, staticExt), row(regexOn, regex), row(null, regexError), row(optionsHead)));
            content.add(Box.createVerticalStrut(10));
            content.add(section("2.  Chống trùng lặp", new Color(147, 51, 234), "Tránh bắt lại cùng một endpoint.",
                    row(dedup), row(dedupIds), row(dedupBranch)));
            content.add(Box.createVerticalStrut(10));
            content.add(section("3.  Danh sách API từ file Excel", new Color(219, 39, 119),
                    "Cột STT | PROTOCOL (method) | PATH | POSITION. Tham số {id} [id] (id) <id> :id * là giá trị bất kỳ.",
                    row(targetOnly), row(targetPosition), row(targetPrefix)));
            content.add(Box.createVerticalStrut(10));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            buttons.setAlignmentX(0f);
            JButton apply = new JButton("Áp dụng");
            apply.setFont(apply.getFont().deriveFont(Font.BOLD));
            apply.addActionListener(e -> save());
            JButton defaults = new JButton("Khôi phục mặc định");
            defaults.addActionListener(e -> {
                load(CaptureSettings.defaults().with("captureEnabled", app.settings().captureEnabled));
                save();
            });
            buttons.add(apply);
            buttons.add(defaults);
            buttons.add(saved);
            content.add(buttons);
            for (JCheckBox b : new JCheckBox[] {scope, statics, regexOn, optionsHead, dedup, dedupIds, dedupBranch,
                targetOnly, targetPosition, targetPrefix}) {
                b.setOpaque(false);
                b.addActionListener(e -> save());
            }
            staticExt.addActionListener(e -> save());
            regex.addActionListener(e -> save());
            JPanel holder = new JPanel(new BorderLayout());
            holder.add(content, BorderLayout.NORTH);
            scroll = new JScrollPane(holder);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
        }

        private JComponent[] row(JComponent left, JComponent... right) {
            JComponent[] r = new JComponent[1 + right.length];
            r[0] = left;
            System.arraycopy(right, 0, r, 1, right.length);
            return r;
        }

        private JPanel section(String title, Color accent, String note, JComponent[]... rows) {
            Color bg = MainPanel.this.getBackground();
            Color tint = Palette.mix(bg, accent, 0.06);
            JPanel s = new JPanel(new BorderLayout(0, 4));
            s.setAlignmentX(0f);
            s.setBackground(tint);
            s.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
                    BorderFactory.createEmptyBorder(8, 12, 10, 12)));
            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            JLabel t = new JLabel(title);
            t.setFont(t.getFont().deriveFont(Font.BOLD, t.getFont().getSize2D() + 2f));
            t.setForeground(accent);
            JLabel n = new JLabel(note);
            n.setForeground(Palette.of(bg, MainPanel.this.getForeground()).muted);
            head.add(t, BorderLayout.NORTH);
            head.add(n, BorderLayout.SOUTH);
            s.add(head, BorderLayout.NORTH);
            JPanel form = new JPanel(new GridBagLayout());
            form.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(3, 2, 3, 8);
            gc.anchor = GridBagConstraints.WEST;
            for (int y = 0; y < rows.length; y++) {
                gc.gridy = y;
                gc.gridx = 0;
                gc.weightx = 0;
                gc.fill = GridBagConstraints.NONE;
                JComponent[] r = rows[y];
                if (r.length == 1) {
                    gc.gridwidth = 2;
                    form.add(r[0], gc);
                    gc.gridwidth = 1;
                    continue;
                }
                if (r[0] != null) {
                    form.add(r[0], gc);
                }
                gc.gridx = 1;
                gc.weightx = 1;
                gc.fill = GridBagConstraints.HORIZONTAL;
                form.add(r[1], gc);
            }
            s.add(form, BorderLayout.CENTER);
            return s;
        }

        void load(CaptureSettings c) {
            loading = true;
            try {
                scope.setSelected(c.inScopeOnly);
                statics.setSelected(c.ignoreStatic);
                staticExt.setText(c.staticExt);
                regexOn.setSelected(c.regexEnabled);
                regex.setText(c.regex);
                optionsHead.setSelected(c.ignoreOptionsHead);
                dedup.setSelected(c.dedup);
                dedupIds.setSelected(c.dedupNormalizeIds);
                dedupBranch.setSelected(c.dedupPerBranch);
                targetOnly.setSelected(c.targetOnly);
                targetPosition.setSelected(c.targetUsePosition);
                targetPrefix.setSelected(c.targetAllowPrefix);
                regexError.setText(c.regexError() == null ? "" : "Regex không hợp lệ - bộ lọc path đang tắt: " + c.regexError());
            } finally {
                loading = false;
            }
        }

        void save() {
            if (loading) {
                return;
            }
            CaptureSettings before = app.settings();
            CaptureSettings c = before.with("inScopeOnly", scope.isSelected()).with("ignoreStatic", statics.isSelected())
                    .with("staticExt", staticExt.getText()).with("regexEnabled", regexOn.isSelected())
                    .with("regex", regex.getText()).with("ignoreOptionsHead", optionsHead.isSelected())
                    .with("dedup", dedup.isSelected()).with("dedupNormalizeIds", dedupIds.isSelected())
                    .with("dedupPerBranch", dedupBranch.isSelected()).with("targetOnly", targetOnly.isSelected())
                    .with("targetUsePosition", targetPosition.isSelected())
                    .with("targetAllowPrefix", targetPrefix.isSelected());
            app.updateSettings(c);
            if (before.targetAllowPrefix != c.targetAllowPrefix) {
                app.retagApis();
            }
            regexError.setText(c.regexError() == null ? "" : "Regex không hợp lệ - bộ lọc path đang tắt: " + c.regexError());
            saved.setText("Đã lưu vào workspace lúc " + new SimpleDateFormat("HH:mm:ss").format(new Date()));
        }
    }

    // ------------------------------------------------------------------ refresh / log
    public void log(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + line + "\n");
            if (logArea.getDocument().getLength() > 400_000) {
                logArea.setText(logArea.getText().substring(200_000));
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void error(String message) {
        host.error(message);
        JOptionPane.showMessageDialog(this, message, "theMythOfCrawl", JOptionPane.ERROR_MESSAGE);
    }

    private void refresh() {
        Workspace w = app.workspace();
        if (w == null) {
            workspaceLabel.setText("(chưa mở)");
            return;
        }
        boolean switched = w != shown;
        if (!switched && w.revision() == shownRevision) {
            updateStats(w);
            return;
        }
        shown = w;
        shownRevision = w.revision();
        CaptureSettings s = app.settings();
        workspaceLabel.setText(w.displayName());
        workspaceLabel.setForeground(Palette.levelHue(2));
        if (captureBox.isSelected() != s.captureEnabled) {
            captureBox.setSelected(s.captureEnabled);
        }
        if (!branchField.hasFocus() && !branchField.getText().equals(w.activeBranch())) {
            branchField.setText(w.activeBranch());
        }
        if (targetOnlyBox.isSelected() != s.targetOnly) {
            targetOnlyBox.setSelected(s.targetOnly);
        }
        targetOnlyBox.setEnabled(app.targets() != null);
        if (switched) {
            shownApiRow = -1;
            settingsForm.load(s);
        }
        apiModel.set(new ArrayList<>(w.apis()));
        Map<TargetList.Entry, List<CapturedApi>> cov = app.coverage();
        coverageModel.set(cov);
        TargetList t = app.targets();
        if (t == null) {
            coverageLabel.setText("Chưa nạp file. Bấm 'Nạp file Excel' - file cần các cột STT | PROTOCOL (method) | PATH | POSITION (tùy chọn).");
        } else {
            long hit = cov.values().stream().filter(v -> !v.isEmpty()).count();
            coverageLabel.setText(t.source() + "  ·  " + t.size() + " API  ·  Đã bắt " + hit + "/" + t.size() + " ("
                    + (t.size() == 0 ? 0 : hit * 100 / t.size()) + "%)  ·  Còn " + (t.size() - hit) + " chưa bắt  ·  Chế độ: "
                    + (s.targetOnly ? "chỉ bắt API trong danh sách" : "bắt mọi API (danh sách chỉ để đối chiếu)"));
        }
        if (switched) {
            wsTree.repaint();
        }
        showWorkspaceInfo();
        updateStats(w);
    }

    private void updateStats(Workspace w) {
        String skip = app.capture().skippedCount() > 0
                ? "  ·  Bỏ qua: " + app.capture().skippedCount() + " (gần nhất: " + app.capture().lastSkipReason() + ")" : "";
        statsLabel.setText("API đã bắt: " + w.apiCount() + "  ·  Nhánh: " + w.branches().size()
                + (app.targets() != null ? "  ·  Excel: " + app.targets().size() + " dòng" : "") + skip
                + "  ·  Lưu tại: " + w.dir().toAbsolutePath());
        statsLabel.setForeground(Palette.of(getBackground(), getForeground()).muted);
    }

    /** Burp's theming can swap the tree renderer; put ours back (called by the extension after theming). */
    public void ensureRenderers() {
        if (!(wsTree.getCellRenderer() instanceof WsRenderer)) {
            wsTree.setCellRenderer(new WsRenderer());
        }
    }
}
