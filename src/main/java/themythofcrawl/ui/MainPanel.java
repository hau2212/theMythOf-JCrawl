package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;

import themythofcrawl.app.Changes;
import themythofcrawl.app.Engine;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Config;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.export.Reports;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.place.PlacementBook;
import themythofcrawl.send.SendDispatcher;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.Breadcrumb;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.kit.ToastPane;
import themythofcrawl.ui.kit.ToolbarGroup;
import themythofcrawl.ui.theme.Theme;

/**
 * The extension's tab: 2-row compact header (&le; 64 px) + ToastPane overlay + the tabs.
 * A single Swing timer drains the engine's changes every 200 ms.
 */
public final class MainPanel {
    public static final int TICK_MS = 200;
    public static final String[] REPEATER_MODES = {"Repeater Gọn: G01•01_Admin (+Copy tên Group)",
        "Repeater: Chỉ đẩy Role Lỗi/Lệch (Vàng/Cam)", "Repeater Đầy đủ: [G01:METHOD /path] 01_Admin"};

    /** The APIs an action applies to, and how to call them. */
    public record Scope(List<ApiGroup> groups, String label) {
    }

    final Engine engine;
    final Workspaces workspaces;
    final UiHost host;
    final String projectId;
    private SegmentedToggle captureToggle;
    private SegmentedToggle sendModeToggle;
    private final Pill queue = new Pill("Hàng đợi: 0", t -> t.neutral, t -> Icons.clock(t.muted, 12));
    final Breadcrumb dest = new Breadcrumb();
    private final Pill todo = new Pill("Cần xử lý: 0", t -> t.pending, t -> Icons.hourglass(t.pending.text(), 12));
    private final JButton options = new JButton();
    private final JButton detach = new JButton();
    private final JButton workspace = new JButton();

    private final Pill rolesChip = new Pill("Roles 0/0", t -> t.neutral, t -> Icons.role(t.muted, 12));
    private final Pill branchChip = new Pill("Nhánh 1", t -> t.neutral, t -> Icons.branch(t.muted));
    final Pill status = new Pill("API 0", t -> t.neutral, t -> Icons.api(t.fg, 12));
    private final Pill infoChip = new Pill("0 lệch", t -> t.warning, t -> Icons.warn(t.warning.text(), 12));
    private final Pill hiddenChip = new Pill("0 ẩn", t -> t.pending, t -> Icons.hide(t.muted, 12));
    private final Pill skippedChip = new Pill("Bỏ qua 0", t -> t.neutral, t -> Icons.filter(t.muted, 12));
    private final Pill excelChip = new Pill("Excel 0/0", t -> t.info, t -> Icons.excel(t.info.text(), 12));
    private final JLabel diagBadge = Ui.muted("");

    private final JPanel main = new JPanel(new BorderLayout());
    final ToastPane toastPane = new ToastPane(main);
    final JTabbedPane tabs = new JTabbedPane();
    private final Timer timer;
    private JFrame window;
    private JPanel headerPanel;
    private int queuePeak;
    private int tickCount;
    private boolean pulseDim;
    private final JPanel root = new JPanel(new BorderLayout()) {
        @Override
        public void updateUI() {
            super.updateUI();
            if (MainPanel.this.engine != null && MainPanel.this.timer != null) {
                syncHeader();
            }
        }
    };
    int repeaterMode;
    PushDialog.Choice pushMemory;
    Object source;   // the JTable or BranchTree used last (what "selected" means)

    final ApiTab api;
    final TreeTab treeTab;
    final HistoryTab history;
    final RolesTab roles;
    final SettingsTab settings;
    final LogTab log;
    final ExcelTab excel;
    final AuditTab audit;

    public MainPanel(Engine engine, Workspaces workspaces, UiHost host, String projectId) {
        this.engine = engine;
        this.workspaces = workspaces;
        this.host = host;
        this.projectId = projectId;
        this.headerPanel = buildHeader();
        main.add(headerPanel, BorderLayout.NORTH);
        api = new ApiTab(this);
        history = new HistoryTab(this);
        roles = new RolesTab(this);
        settings = new SettingsTab(this);
        log = new LogTab(this);
        excel = new ExcelTab(this);
        treeTab = new TreeTab(this);
        audit = new AuditTab(this);
        Object[][] t = {
            {"API", api.panel, "Danh sách API đã bắt, kết quả từng role, Repeater gộp"},
            {"Lịch sử", history.panel, "Cây lịch sử theo nhánh và báo cáo"},
            {"Roles", roles.panel, "Các role / profile (header, cookie, JWT) và profile API gốc"},
            {"Cấu hình", settings.panel, "Bộ lọc bắt, tốc độ gửi, Excel, copy, workspace"},
            {"Log", log.panel, "Nhật ký theo nhánh / API, xuất Excel"},
            {"Excel", excel.panel, "Đối chiếu file Excel: dòng nào đã bắt, số lần gặp, ở đâu"},
            {"Cây", treeTab.panel, "Cây nhánh cỡ lớn, mở được cửa sổ riêng, có Request / Response"},
            {"Audit", audit.panel, "Cần xử lý, giữ chỗ, API trùng & ẩn, nhật ký vị trí"}};
        for (Object[] x : t) {
            tabs.addTab((String) x[0], null, (Component) x[1], (String) x[2]);
        }
        tabs.addChangeListener(e -> tick(true));
        main.add(tabs, BorderLayout.CENTER);
        root.add(toastPane, BorderLayout.CENTER);
        Theme.track(root);
        try {
            host.applyTheme(root);
        } catch (RuntimeException ignored) {
            // outside Burp
        }
        settings.load();
        syncHeader();
        api.reloadAll();
        timer = new Timer(TICK_MS, e -> tick(false));
        timer.setCoalesce(true);
        timer.start();
    }

    public JPanel root() {
        return root;
    }

    JPanel headerPanel() {
        return headerPanel;
    }

    public void dispose() {
        timer.stop();
        toastPane.dispose();
        if (window != null) {
            window.dispose();
        }
        treeTab.dispose();
    }

    // =================================================================== header
    private static JSeparator vSep() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(2, 18));
        return s;
    }

    private JPanel buildHeader() {
        Config c = engine.config();
        captureToggle = new SegmentedToggle(
                new SegmentedToggle.Option("Dừng", "Tạm dừng Capture Proxy", t -> t.slate,
                        (t, sel) -> Icons.circle(sel ? t.slate.onSolid() : t.muted, false), false),
                new SegmentedToggle.Option("Capture", "Bật / tắt bắt request từ Proxy", t -> t.danger,
                        (t, sel) -> {
                            Color dot = sel ? (pulseDim ? t.danger.border() : t.danger.base()) : t.muted;
                            return Icons.statusDot(dot, 10);
                        }, true));
        captureToggle.setSelectedIndex(c.captureEnabled ? 1 : 0);
        captureToggle.onChange(idx -> update(x -> x.captureEnabled = idx == 1));

        sendModeToggle = new SegmentedToggle(
                new SegmentedToggle.Option("CHỈ CRAWL", "Chỉ ghi lại API từ Proxy, không tự gửi Role", t -> t.slate,
                        (t, sel) -> Icons.hide(sel ? t.slate.onSolid() : t.muted, 12), false),
                new SegmentedToggle.Option("GỬI", "Tự động gửi ngay 1..N Roles khi bắt được API", t -> t.success,
                        (t, sel) -> Icons.send(sel ? t.success.onSolid() : t.success.text(), 12), false));
        sendModeToggle.setSelectedIndex(c.autoSend ? 1 : 0);
        sendModeToggle.onChange(idx -> setSendMode(idx == 1));

        queue.setPadding(2, 8, 2, 8);
        queue.onClick(() -> {
            if (queue.isEnabled()) {
                engine.cancelPending("Đã hủy");
            }
        });

        dest.setMaxSegments(2);
        dest.setMaxLabelChars(18);
        dest.onSegmentClick(seg -> {
            if (Hierarchy.normalize(seg).equals(Hierarchy.normalize(engine.activeBranch().name()))) {
                destinationMenu();
            } else {
                setDestination(seg);
            }
        });
        dest.onDropdown(this::destinationMenu);

        todo.setPadding(2, 8, 2, 8);
        todo.onClick(this::todoMenu);

        options.setIcon(Icons.themed(t -> Icons.withChevron(Icons.gear(t.fg), t.fg)));
        options.setToolTipText("Tùy chọn: phạm vi bắt, regex, tự gửi, xác nhận vị trí, giữ chỗ, API trùng...");
        options.setMargin(new Insets(2, 6, 2, 6));
        options.addActionListener(e -> optionsMenu());
        detach.setIcon(Icons.themed(t -> Icons.window(t.fg)));
        detach.setMargin(new Insets(2, 6, 2, 6));
        detach.setToolTipText("Tách cả extension ra cửa sổ riêng (bấm lại / đóng cửa sổ để gắn vào tab Burp)");
        detach.addActionListener(e -> toggleDetach());
        workspace.setIcon(Icons.themed(t -> Icons.withChevron(Icons.folder(t.fg), t.fg)));
        workspace.setHorizontalTextPosition(SwingConstants.LEADING);
        workspace.setMargin(new Insets(2, 6, 2, 6));
        workspace.addActionListener(e -> workspaceMenu());

        JPanel leftZone = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftZone.setOpaque(false);
        leftZone.add(captureToggle);
        leftZone.add(sendModeToggle);
        leftZone.add(queue);
        leftZone.add(Ui.info(Help.CAPTURE, "Bắt & gửi"));

        JLabel destLabel = new JLabel("Đích:", Icons.themed(t -> Icons.arrowRight(t.critical.text())), SwingConstants.LEFT) {
            @Override
            public void updateUI() {
                super.updateUI();
                setFont(Theme.current().smallBoldFont);
            }
        };
        destLabel.setFont(Theme.current().smallBoldFont);
        JPanel centerZone = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        centerZone.setOpaque(false);
        centerZone.add(vSep());
        centerZone.add(destLabel);
        centerZone.add(dest);
        centerZone.add(Ui.info(Help.DEST, "Đích của request mới"));

        ToolbarGroup actions = new ToolbarGroup(options, detach, workspace);
        ((java.awt.FlowLayout) actions.getLayout()).setVgap(0);
        JPanel rightZone = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightZone.setOpaque(false);
        rightZone.add(todo);
        rightZone.add(Ui.info(Help.TODO, "Cần xử lý"));
        rightZone.add(vSep());
        rightZone.add(actions);

        JPanel row1 = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        row1.setOpaque(false);
        row1.add(leftZone, BorderLayout.WEST);
        row1.add(centerZone, BorderLayout.CENTER);
        row1.add(rightZone, BorderLayout.EAST);

        for (Pill chip : new Pill[] {rolesChip, branchChip, status, infoChip, hiddenChip, skippedChip, excelChip}) {
            chip.setPadding(1, 7, 1, 7);
        }
        rolesChip.setToolTipText("Số Role đang bật / tổng Role (bấm để mở tab Roles)");
        rolesChip.onClick(() -> tabs.setSelectedComponent(roles.panel));
        branchChip.setToolTipText("Số nhánh Sub-folder (bấm để mở tab Lịch sử)");
        branchChip.onClick(() -> tabs.setSelectedComponent(history.panel));
        status.onClick(() -> tabs.setSelectedComponent(api.panel));
        infoChip.setToolTipText("Số API có Role lệch quyền / lỗi (bấm để lọc)");
        infoChip.onClick(() -> {
            tabs.setSelectedComponent(api.panel);
            api.filterLevel(AuditLevel.INFO);
        });
        hiddenChip.setToolTipText("Số API trùng đang ẩn (bấm để mở Audit > API trùng & ẩn)");
        hiddenChip.onClick(() -> showAudit(2));
        excelChip.setToolTipText("Độ phủ danh sách Excel (bấm để mở tab Excel)");
        excelChip.onClick(() -> tabs.setSelectedComponent(excel.panel));

        JPanel statsZone = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        statsZone.setOpaque(false);
        statsZone.add(Ui.info(Help.COLORS, "Màu & nhãn"));
        statsZone.add(rolesChip);
        statsZone.add(branchChip);
        statsZone.add(status);
        statsZone.add(infoChip);
        statsZone.add(hiddenChip);
        statsZone.add(skippedChip);
        statsZone.add(excelChip);

        JPanel diagZone = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        diagZone.setOpaque(false);
        diagZone.add(diagBadge);
        diagZone.add(Ui.info(Help.WORKSPACE, "Workspace"));

        JPanel row2 = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        row2.setOpaque(false);
        row2.add(statsZone, BorderLayout.WEST);
        row2.add(diagZone, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(0, 2)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, t.border),
                        BorderFactory.createEmptyBorder(2, 8, 2, 8)));
            }
        };
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.current().border),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        top.add(row1, BorderLayout.NORTH);
        top.add(row2, BorderLayout.SOUTH);
        return top;
    }

    void update(java.util.function.Consumer<Config> f) {
        Config c = engine.config().copy();
        f.accept(c);
        String err = engine.applyConfig(c);
        if (err != null && c.apiRegexEnabled) {
            flash("Regex API lỗi: " + err);
        }
        syncHeader();
        settings.load();
    }

    private void setSendMode(boolean on) {
        if (on == engine.config().autoSend) {
            return;
        }
        update(c -> c.autoSend = on);
        if (on) {
            int n = engine.sendAllUnsent();
            engine.log("Bật GỬI: bắt xong gửi luôn" + (n > 0 ? "; xếp hàng " + n + " request chưa gửi." : "."), null);
        } else {
            engine.log("CHỈ CRAWL: chỉ ghi lại API, không gửi.", null);
        }
    }

    void syncHeader() {
        Theme th = Theme.current();
        Config c = engine.config();
        captureToggle.setSelectedIndex(c.captureEnabled ? 1 : 0);
        sendModeToggle.setSelectedIndex(c.autoSend ? 1 : 0);

        Branch b = engine.activeBranch();
        String name = b.name();
        if (!dest.path().equals(name)) {
            dest.setPath(name);
            dest.setToolTipText("Request mới sẽ vào: " + name + "  (bấm cấp để chọn, bấm mũi tên để mở menu)");
        }

        Engine.Stats st = engine.stats();
        todo.setText("Cần xử lý: " + st.pending() + (st.dupWaiting() > 0 ? " (#" + st.dupWaiting() + ")" : ""));
        todo.setActive(st.pending() > 0);
        todo.setTrailingDot(st.dupWaiting() > 0 ? t -> t.dup.base() : null);
        todo.setToolTipText(st.pending() > 0
                ? st.pending() + " API đang chờ xác nhận" + (st.dupWaiting() > 0 ? " (" + st.dupWaiting() + " API trùng)" : "")
                : "Không có API chờ xác nhận");

        SendDispatcher.Snapshot q = engine.queue();
        int waiting = q.queued() + q.inFlight();
        if (waiting > queuePeak) {
            queuePeak = waiting;
        } else if (waiting == 0) {
            queuePeak = 0;
        }
        queue.setText(q.waitSeconds() > 0.5 ? String.format("Hàng đợi: %d (chờ %.0fs)", waiting, q.waitSeconds())
                : "Hàng đợi: " + waiting);
        queue.setEnabled(waiting > 0);
        queue.setActive(waiting > 0);
        queue.setToken(waiting > 0 ? (q.waitSeconds() > 0.5 ? t -> t.critical : t -> t.info) : t -> t.neutral);
        queue.setIcon(waiting > 0 ? t -> Icons.bolt(q.waitSeconds() > 0.5 ? t.critical.text() : t.info.text())
                : t -> Icons.clock(t.muted));
        if (waiting > 0 && queuePeak > 0) {
            double doneRatio = Math.max(0.08, Math.min(1.0, (double) Math.max(1, queuePeak - q.queued()) / queuePeak));
            queue.setProgress(doneRatio);
            queue.setToolTipText("Đang gửi " + q.inFlight() + ", chờ " + q.queued() + " — bấm để hủy hàng đợi");
        } else {
            queue.setProgress(-1);
            queue.setToolTipText("Hàng đợi gửi đang trống");
        }

        String diag = Diagnostics.summary();
        String wsName = engine.workspaceName();
        workspace.setText(wsName.length() <= 16 ? wsName : wsName.substring(0, 14) + "...");
        workspace.setToolTipText(diag + " | Workspace: " + engine.workspaceLocation());

        rolesChip.setText("Roles " + engine.roles().enabledCount() + "/" + engine.roles().count());
        branchChip.setText("Nhánh " + st.branches());
        status.setText("API " + st.apis());
        status.setToolTipText(diag);

        infoChip.setVisible(st.info() > 0);
        if (st.info() > 0) {
            infoChip.setText(st.info() + " lệch");
        }
        hiddenChip.setVisible(st.hidden() > 0);
        if (st.hidden() > 0) {
            hiddenChip.setText(st.hidden() + " ẩn");
        }
        int sk = engine.skippedCount();
        skippedChip.setVisible(sk > 0);
        if (sk > 0) {
            skippedChip.setText("Bỏ qua " + sk);
            skippedChip.setToolTipText("Lý do gần nhất: " + engine.lastSkipReason());
        }
        boolean hasTargets = engine.targets() != null;
        excelChip.setVisible(hasTargets);
        if (hasTargets) {
            int seen = engine.hits().rowsSeen();
            int total = Math.max(1, engine.targets().size());
            excelChip.setText("Excel " + seen + "/" + engine.targets().size());
            excelChip.setProgress((double) seen / total);
            excelChip.setToken(seen >= engine.targets().size() ? t -> t.success : t -> t.info);
            excelChip.setIcon(seen >= engine.targets().size()
                    ? t -> Icons.excel(t.success.text(), 12) : t -> Icons.excel(t.info.text(), 12));
        }

        diagBadge.setFont(th.smallFont);
        diagBadge.setForeground(th.muted);
        diagBadge.setText(Text.EXT_NAME + " " + Diagnostics.VERSION + " · " + Diagnostics.lafName() + (th.dark ? " (dark)" : ""));
        diagBadge.setToolTipText(diag);
    }

    public void flash(String text) {
        toastPane.showToast(text);
    }

    private void destinationMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Chọn đích từ danh sách...", Icons::folder, this::pickDestination));
        m.add(Ui.item("Lấy nhánh đang chọn trong cây", Icons::branch, this::destinationFromTree));
        m.add(Ui.item("Nhánh con mới của đích...", Icons::plus, () -> {
            String n = ask("Đường dẫn nhánh mới (ngăn cách bởi '>'), sẽ thành đích cho request mới:",
                    engine.activeBranch().name() + " > ");
            if (n != null && !n.replace(">", "").isBlank()) {
                setDestination(n);
            }
        }));
        m.add(Ui.item("Nhánh mới cùng cấp (nút / action tiếp theo)...", Icons::plus, this::nextSibling));
        m.add(Ui.item("Lên 1 cấp", Icons::arrowUp,
                () -> setDestination(themythofcrawl.core.Hierarchy.parent(engine.activeBranch().name()))));
        m.add(Ui.item("Đổi tên / sửa đường dẫn đích...", Icons::edit, () -> {
            Branch b = engine.activeBranch();
            String n = ask("Sửa đường dẫn của đích (các API trong nhánh này đổi theo):", b.name());
            if (n != null && !n.isBlank()) {
                engine.renameBranch(b.id(), n);
            }
        }));
        JMenu more = new JMenu("Khác");
        more.add(Ui.item("Quản lý các cấp của đích (thêm / xóa / sửa / đổi thứ tự)...", Icons::gear,
                () -> LevelsDialog.show(root, engine)));
        more.add(Ui.item("Xóa nhánh đích...", t -> Icons.trash(t.danger.text()),
                () -> deleteBranchDialog(engine.activeBranch().name())));
        m.add(more);
        List<String> recent = new ArrayList<>(engine.recentDestinations());
        recent.remove(engine.activeBranch().name());
        if (!recent.isEmpty()) {
            m.addSeparator();
            m.add(Ui.item("Gần đây:", false, () -> { }));
            for (String p : recent.subList(0, Math.min(8, recent.size()))) {
                m.add(Ui.item("   " + p, () -> setDestination(p)));
            }
        }
        m.show(dest, 0, dest.getHeight());
    }

    void setDestination(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        engine.setDestination(path);
        syncHeader();
        flash("Request mới sẽ vào: " + engine.activeBranch().name());
    }

    void pickDestination() {
        String p = PathPicker.pick(root, "Chọn đích cho request mới", engine.knownPaths(), engine.activeBranch().name(),
                "Mọi request mới sẽ vào đường dẫn này cho tới khi bạn chọn lại.");
        setDestination(p);
    }

    void destinationFromTree() {
        String p = selectedBranch();
        if (p == null) {
            info("Chọn 1 nhánh trong cây trước.");
            return;
        }
        setDestination(p);
    }

    void nextSibling() {
        String cur = engine.activeBranch().name();
        String parent = themythofcrawl.core.Hierarchy.depth(cur) > 1 ? themythofcrawl.core.Hierarchy.parent(cur) : "";
        String n = ask("Tên nút / action tiếp theo (cùng cấp với nhánh đích):", "");
        if (n != null && !n.isBlank()) {
            setDestination(parent.isEmpty() ? n : themythofcrawl.core.Hierarchy.append(parent, n));
        }
    }

    private void todoMenu() {
        List<ApiGroup> pending = engine.pendingGroups();
        List<ApiGroup> dups = pending.stream().filter(ApiGroup::dupPending).toList();
        int n = pending.size();
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Mở danh sách Cần xử lý (giải thích + nút cho từng API)...", Icons::compass, () -> showAudit(0)));
        m.addSeparator();
        m.add(Ui.item("Giữ chỗ hiện tại cho cả " + n + " API", t -> Icons.check(t.success.text()), n > 0,
                () -> engine.confirm(engine.pendingGroups(), "xác nhận tất cả")));
        m.add(Ui.item("Chuyển cả " + n + " API sang path khác...", Icons::move, n > 0, () -> moveDialog(engine.pendingGroups())));
        m.add(Ui.item("Không giữ " + dups.size() + " API trùng (ẩn, vẫn lưu ở Audit)", Icons::hide, !dups.isEmpty(),
                () -> engine.hide(engine.pendingGroups().stream().filter(ApiGroup::dupPending).toList(), "không giữ")));
        m.add(Ui.item("Lọc bảng API: chỉ API đang chờ", Icons::search, n > 0, () -> {
            tabs.setSelectedComponent(api.panel);
            api.filterPending();
        }));
        m.addSeparator();
        m.add(Ui.item("Xem API trùng & đang ẩn (" + engine.hiddenGroups().size() + " ẩn)", Icons::hide, () -> showAudit(2)));
        m.add(Ui.item("Xóa " + n + " API đang chờ...", t -> Icons.trash(t.danger.text()), n > 0, () -> {
            if (confirmDialog("Xóa " + engine.pendingGroups().size() + " API đang chờ xác nhận?")) {
                engine.remove(engine.pendingGroups(), "bỏ API chờ xác nhận");
            }
        }));
        m.show(todo, 0, todo.getHeight());
    }

    private void optionsMenu() {
        Config c = engine.config();
        JPopupMenu m = new JPopupMenu();
        Object[][] toggles = {
            {"In-Scope Only (Chỉ bắt API trong Target Scope)", c.inScopeOnly, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.inScopeOnly = v},
            {"API Path Regex (Chỉ bắt URL khớp Regex API)", c.apiRegexEnabled, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.apiRegexEnabled = v},
            null,
            {"Cho phép Tự động Gửi ngầm (Auto-Send 1..N Roles)", c.autoSend, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.autoSend = v},
            {"Tự động đẩy sang Burp Repeater khi bắt", c.autoRepeater, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.autoRepeater = v},
            null,
            {"Hỏi xác nhận vị trí API mới (màu xám)", c.confirmNewApis, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.confirmNewApis = v},
            {"API đã xóa quay về chỗ cũ (giữ chỗ)", c.restoreKnownPlace, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.restoreKnownPlace = v},
            {"Gặp API trùng ở chỗ mới: hỏi giữ thêm bản (màu tím)", c.dupNewPlace, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.dupNewPlace = v},
            {"Hiện API đã xóa (giữ chỗ) trong cây", c.showGhosts, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.showGhosts = v},
        };
        for (Object[] t : toggles) {
            if (t == null) {
                m.addSeparator();
                continue;
            }
            JCheckBoxMenuItem it = new JCheckBoxMenuItem((String) t[0], (Boolean) t[1]);
            @SuppressWarnings("unchecked")
            java.util.function.BiConsumer<Config, Boolean> set = (java.util.function.BiConsumer<Config, Boolean>) t[2];
            it.addActionListener(e -> update(x -> set.accept(x, it.isSelected())));
            m.add(it);
        }
        m.addSeparator();
        m.add(Ui.item("Mở tab Cấu hình chi tiết...", Icons::gear, () -> tabs.setSelectedComponent(settings.panel)));
        m.add(Ui.item("Reset Dedup Cache (Cho phép bắt lại API cũ)", Icons::refresh, engine::resetDedup));
        m.show(options, 0, options.getHeight());
    }

    // =================================================================== workspace
    private void workspaceMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Đang mở: " + engine.workspaceName() + " - " + engine.workspaceLocation(), false, () -> { }));
        m.addSeparator();
        m.add(Ui.item("Workspace mới...", Icons::plus, () -> {
            String n = ask("Tên workspace mới (thuộc project '" + host.projectName() + "'):", "Workspace mới");
            if (n != null && !n.isBlank()) {
                openWorkspace(workspaces.create(host.projectName(), n.trim()));
            }
        }));
        JMenu open = new JMenu("Mở workspace khác");
        open.setIcon(Icons.themed(t -> Icons.folder(t.fg)));
        try {
            for (Workspaces.Info i : workspaces.list()) {
                open.add(Ui.item(i.project() + " / " + i.name() + "  (" + i.apis() + " API)", () -> openWorkspace(workspaces.open(i.dir()))));
            }
        } catch (RuntimeException e) {
            open.add(Ui.item("(không đọc được thư mục: " + e.getMessage() + ")", false, () -> { }));
        }
        if (open.getItemCount() == 0) {
            open.add(Ui.item("(chưa có)", false, () -> { }));
        }
        m.add(open);
        m.add(Ui.item("Mở thư mục workspace trong máy", Icons::folder, () -> {
            try {
                Desktop.getDesktop().open(new File(engine.workspaceLocation()));
            } catch (Exception e) {
                Ui.copyToClipboard(engine.workspaceLocation());
                flash("Đã copy đường dẫn workspace: " + engine.workspaceLocation());
            }
        }));
        m.add(Ui.item("Đổi thư mục gốc (nơi tạo theMythOfCrawl_workspace)...", Icons::pin, () -> {
            JFileChooser fc = new JFileChooser(workspaces.root().toFile());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                workspaces.setRoot(fc.getSelectedFile().toPath());
                openWorkspace(workspaces.start(projectId, host.projectName()));
            }
        }));
        m.add(Ui.item("Xóa một workspace khác...", t -> Icons.trash(t.danger.text()), () -> {
            List<Workspaces.Info> list = workspaces.list().stream()
                    .filter(i -> !i.dir().toAbsolutePath().toString().equals(engine.workspaceLocation())).toList();
            if (list.isEmpty()) {
                info("Không có workspace nào khác.");
                return;
            }
            Object pick = JOptionPane.showInputDialog(root, "Xóa workspace (không xóa được workspace đang mở):", Text.EXT_NAME,
                    JOptionPane.WARNING_MESSAGE, null, list.stream().map(i -> i.project() + " / " + i.name()).toArray(), null);
            if (pick != null) {
                Workspaces.Info i = list.stream().filter(x -> (x.project() + " / " + x.name()).equals(pick)).findFirst().get();
                if (confirmDialog("Xóa vĩnh viễn workspace '" + pick + "' (" + i.apis() + " API)?")) {
                    workspaces.delete(i.dir());
                    flash("Đã xóa workspace " + pick);
                }
            }
        }));
        m.show(workspace, 0, workspace.getHeight());
    }

    private void openWorkspace(FileStorage st) {
        engine.open(st);
        workspaces.remember(projectId, st);
        api.reloadAll();
        tick(true);
        flash("Đang dùng workspace " + st.name() + " (" + engine.groups().size() + " API)");
    }

    // =================================================================== detach
    void toggleDetach() {
        if (window != null) {
            attach();
            return;
        }
        window = new JFrame(Text.EXT_NAME);
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                attach();
            }
        });
        Dimension size = root.getSize();
        root.remove(toastPane);
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 60));
        holder.add(new JLabel(Text.EXT_NAME + " đang mở ở cửa sổ riêng."));
        holder.add(Ui.button("Đưa cửa sổ lên trước", () -> {
            window.setState(JFrame.NORMAL);
            window.toFront();
        }));
        holder.add(Ui.button("Gắn lại vào tab", this::attach));
        root.add(holder, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
        window.getContentPane().add(toastPane);
        Theme.track(window);
        window.setSize(new Dimension(Math.max(1100, size.width), Math.max(760, size.height)));
        window.setLocationRelativeTo(null);
        detach.setText("Gắn lại");
        window.setVisible(true);
    }

    void attach() {
        if (window == null) {
            return;
        }
        JFrame w = window;
        window = null;
        w.getContentPane().removeAll();
        w.dispose();
        root.removeAll();
        root.add(toastPane, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
        detach.setText("");
    }

    public boolean detached() {
        return window != null;
    }

    // =================================================================== the UI tick
    void tick(boolean force) {
        Changes.Batch b = engine.changes().drain();
        if (!b.log().isEmpty()) {
            log.append(b.log());
        }
        if (!b.added().isEmpty() || !b.dirty().isEmpty() || b.structure()) {
            api.apply(b);
        }
        if (b.structure() || !b.added().isEmpty()) {
            treeTab.stale = true;
            history.stale = true;
            excel.stale = true;
            audit.stale = true;
            log.stale = true;
        }
        if (!b.dirty().isEmpty()) {
            treeTab.dirty(b.dirty());
            excel.stale |= b.dirty().stream().anyMatch(g -> g.target() != null);
            audit.stale = true;
        }
        api.refreshVisible(force);
        treeTab.refreshIfVisible();
        history.refreshIfVisible();
        excel.refreshIfVisible();
        audit.refreshIfVisible();
        log.refreshIfVisible();
        boolean queueActive = queuePeak > 0 || engine.queue().queued() + engine.queue().inFlight() > 0;
        if (b.status() || b.structure() || force || !b.added().isEmpty()) {
            syncHeader();
            roles.refreshCounts();
        } else if (queueActive) {
            syncHeader();
        }
        if (engine.config().captureEnabled) {
            if (captureToggle.isShowing() && tickCount++ % 2 == 0) {
                pulseDim = !pulseDim;
                captureToggle.repaint();
            }
        } else if (pulseDim) {
            pulseDim = false;
            captureToggle.repaint();
        }
    }

    // =================================================================== scope (what "selected" means)
    void useSource(Object src) {
        source = src;
    }

    Scope scope() {
        List<ApiGroup> fromTable = api.selectedGroups();
        if (!fromTable.isEmpty() && source == api.table) {
            return new Scope(fromTable, fromTable.size() > 1 ? fromTable.size() + " API đang chọn" : Text.apiLabel(fromTable.get(0).gid()));
        }
        BranchTree t = source instanceof BranchTree bt ? bt : api.tree;
        if (t.selectionCount() > 1) {
            List<ApiGroup> many = t.selectedGroups();
            return new Scope(many, t.selectionCount() + " mục chọn trong cây (" + many.size() + " API)");
        }
        Node n = t.selectedNode();
        if (n != null) {
            switch (n.kind) {
                case ROOT:
                    return new Scope(engine.groups(), "Tất cả API");
                case FOLDER:
                    return new Scope(engine.groupsUnder((String) n.payload), "Nhánh \"" + n.payload + "\"");
                default:
                    ApiGroup g = BranchTree.groupOf(n);
                    if (g != null) {
                        return new Scope(List.of(g), Text.apiLabel(g.gid()));
                    }
            }
        }
        if (!fromTable.isEmpty()) {
            return new Scope(fromTable, fromTable.size() + " API đang chọn");
        }
        return new Scope(List.of(), "");
    }

    String selectedBranch() {
        BranchTree t = source instanceof BranchTree bt ? bt : api.tree;
        String b = t.selectedBranch();
        if (b == null) {
            Branch br = api.selectedBranchRow();
            if (br != null) {
                return br.name();
            }
        }
        return b;
    }

    // =================================================================== shared actions
    void send(Collection<ApiGroup> gs) {
        for (ApiGroup g : gs) {
            engine.replay(g, engine.defaultTargets(g, false));
        }
        if (!gs.isEmpty()) {
            flash("Xếp hàng gửi " + gs.size() + " API");
        }
    }

    /** Push with the Repeater mode chosen in the 'Burp Repeater' menu. */
    void push(ApiGroup g) {
        if (g == null) {
            return;
        }
        String title;
        if (repeaterMode == 1) {
            List<RoleResult> flagged = g.results().stream().filter(r -> themythofcrawl.core.Audit.level(g, r).flagged()).toList();
            title = engine.toRepeater(g, flagged.isEmpty() ? engine.defaultTargets(g, false) : flagged, true);
            Ui.copyToClipboard(title);
        } else if (repeaterMode == 0) {
            title = engine.toRepeater(g, engine.defaultTargets(g, false), true);
            Ui.copyToClipboard(title);
            flash("Đã đẩy sang Repeater (copy tên Group '" + title + "')");
        } else {
            engine.toRepeater(g, engine.defaultTargets(g, false), false);
        }
    }

    void push(Collection<ApiGroup> gs) {
        for (ApiGroup g : gs) {
            push(g);
        }
    }

    void pushDialog(boolean send, List<ApiGroup> gs, String label) {
        if (gs == null) {
            Scope s = scope();
            gs = s.groups();
            label = s.label();
        }
        if (gs.isEmpty()) {
            info("Chọn API (bảng) hoặc nhánh / API (cây) trước.");
            return;
        }
        PushDialog.Choice c = PushDialog.show(root, gs, label, pushMemory, send);
        if (c == null) {
            return;
        }
        pushMemory = c;
        int total = 0;
        int apis = 0;
        for (ApiGroup g : gs) {
            List<RoleResult> t = Engine.selectTargets(g, c.original(), c.roles(), c.onlyFlagged());
            if (t.isEmpty()) {
                continue;
            }
            apis++;
            total += t.size();
            if (c.send()) {
                engine.replay(g, t);
            } else {
                String title = engine.toRepeater(g, t, c.compact());
                if (c.compact() && gs.size() == 1) {
                    Ui.copyToClipboard(title);
                }
            }
        }
        List<String> parts = new ArrayList<>();
        if (c.original()) {
            parts.add("API gốc");
        }
        parts.addAll(c.roles().stream().sorted().toList());
        engine.log(label + ": " + (c.send() ? "gửi " + total + " request" : "đẩy " + total + " tab sang Repeater") + " cho " + apis
                + " API (" + (parts.isEmpty() ? "-" : String.join(", ", parts)) + (c.onlyFlagged() ? ", chỉ role lỗi / lệch" : "") + ").", null);
    }

    void copy(Collection<ApiGroup> gs) {
        if (gs.isEmpty()) {
            info("Chọn API (bảng) hoặc nhánh / API (cây) trước.");
            return;
        }
        String text = engine.copy(gs);
        Ui.copyToClipboard(text);
        flash("Đã copy " + gs.size() + " dòng: " + text.split("\n")[0]);
    }

    void copyBranchPath() {
        String b = selectedBranch();
        if (b != null) {
            Ui.copyToClipboard(b);
            flash("Đã copy path nhánh: " + b);
        }
    }

    int moveDialog(Collection<ApiGroup> gs) {
        if (gs.isEmpty()) {
            info("Chọn API (bảng hoặc cây) trước.");
            return 0;
        }
        ApiGroup first = gs.iterator().next();
        String p = PathPicker.pick(root, "Chuyển " + gs.size() + " API sang đường dẫn khác", engine.knownPaths(), first.branch(),
                gs.size() + " API sẽ được chuyển và xác nhận vị trí (ghi vào nhật ký, hoàn tác được).");
        if (p == null) {
            return 0;
        }
        int n = engine.moveToPath(gs, p, "");
        flash("Đã chuyển " + n + " API sang " + p);
        return n;
    }

    void revert(Collection<ApiGroup> gs) {
        int n = engine.revert(gs);
        flash(n > 0 ? n + " API đã về vị trí trước" : "Không có vị trí trước để quay về");
    }

    void delete(Collection<ApiGroup> gs) {
        if (gs.isEmpty()) {
            return;
        }
        if (gs.size() > 1 && !confirmDialog("Xóa " + gs.size() + " API? (vị trí của chúng vẫn được giữ chỗ)")) {
            return;
        }
        engine.remove(gs, "");
        api.showGroup(null);
    }

    void rebuild(Collection<ApiGroup> gs) {
        for (ApiGroup g : gs) {
            engine.rebuild(g);
        }
    }

    void roleScopeDialog(boolean enable, RoleScopes.Scope scope, List<String> branches, List<ApiGroup> apis, List<String> roleNames) {
        RoleScopeDialog.Result r = RoleScopeDialog.show(root, engine, enable, scope, branches, apis, roleNames);
        if (r != null) {
            int changed = engine.applyRoleScope(r.roles(), r.enable(), r.scope(), r.targets(), r.applyExisting());
            roles.reload();
            flash((r.enable() ? "Bật " : "Tắt ") + r.roles().size() + " role · " + changed + " API cập nhật");
        }
    }

    void addSubfolder(String base) {
        String b = base != null ? base : engine.activeBranch().name();
        String sub = ask("Thêm Sub-folder con bên dưới nhánh '" + b + "':\n(Có thể nhập nhiều cấp cách nhau bởi '>')", "Subfolder_mới");
        if (sub == null || sub.isBlank()) {
            return;
        }
        String path = themythofcrawl.core.Hierarchy.append(b, sub);
        if (confirmDialog("Đặt '" + path + "' làm ĐÍCH cho request mới luôn?")) {
            setDestination(path);
        } else {
            engine.findOrCreateBranch(path, false);
        }
    }

    void renameBranchDialog(String prefix) {
        String n = ask("Đổi tên nhánh '" + prefix + "' (tự động cập nhật toàn bộ các sub-folder con bên dưới):", prefix);
        if (n != null && !n.isBlank()) {
            engine.renamePrefix(prefix, n);
        }
    }

    void deleteBranchDialog(String prefix) {
        if (prefix == null) {
            return;
        }
        int n = engine.groupsUnder(prefix).size();
        Object[] opts = {"Chỉ xóa Nhánh (Chuyển API lên cấp cha)", "Xóa cả Nhánh lẫn " + n + " API", "Hủy"};
        int c = JOptionPane.showOptionDialog(root, "Bạn muốn xóa nhánh Sub-folder '" + prefix + "' (" + n + " API bên trong) như thế nào?",
                Text.EXT_NAME, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if (c == 0 || c == 1) {
            engine.deletePrefix(prefix, c == 1);
        }
    }

    void jumpToGroup(ApiGroup g) {
        if (g == null) {
            return;
        }
        if (g.hidden()) {
            engine.show(List.of(g));
            tick(true);
        }
        tabs.setSelectedComponent(api.panel);
        api.select(g, null);
    }

    void jumpToTarget(TargetEntry e) {
        tabs.setSelectedComponent(excel.panel);
        excel.select(e);
    }

    void showAudit(int section) {
        tabs.setSelectedComponent(audit.panel);
        audit.show(section, null);
    }

    void showPlaceOf(String key) {
        tabs.setSelectedComponent(audit.panel);
        audit.show(1, key);
    }

    /** Click on a copy glyph / Excel badge / duplicate badge (table or tree). */
    void chip(BranchTree.Chip c, ApiGroup g, Component anchor, int x, int y) {
        switch (c) {
            case COPY -> copy(List.of(g));
            case EXCEL -> {
                if (g.target() != null) {
                    jumpToTarget(g.target());
                }
            }
            case DUP -> {
                if (g.dupTotal() > 1) {
                    DupPopup.show(this, g, anchor, x, y);
                }
            }
            default -> { }
        }
    }

    /** Confirm / move / revert / history items of placement menus. */
    void placeItems(JPopupMenu m, List<ApiGroup> gs) {
        List<ApiGroup> pending = gs.stream().filter(ApiGroup::pending).toList();
        m.add(Ui.item("Xác nhận vị trí (" + pending.size() + " API đang chờ)", t -> Icons.check(t.success.text()),
                !pending.isEmpty(), () -> engine.confirm(pending, "")));
        m.add(Ui.item("Chuyển " + gs.size() + " API sang đường dẫn khác...", Icons::move, !gs.isEmpty(), () -> moveDialog(gs)));
        m.add(Ui.item("Đưa " + gs.size() + " API về vị trí trước", Icons::undo,
                gs.stream().anyMatch(g -> !g.placeHistory().isEmpty()), () -> revert(gs)));
        if (gs.size() == 1) {
            m.add(Ui.item("Lịch sử vị trí API #" + gs.get(0).gid(), Icons::compass, () -> showPlaceOf(gs.get(0).bookKey())));
        }
    }

    // =================================================================== exports
    void exportExcel() {
        File f = save(Text.EXT_NAME + "_API_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".xlsx",
                "Excel (.xlsx)", "xlsx");
        if (f == null) {
            return;
        }
        try {
            List<ApiGroup> all = new ArrayList<>(engine.groups());
            all.addAll(engine.hiddenGroups());
            Config c = engine.config();
            byte[] data = Reports.workbook(all, engine.targets(), c.copyFormat, c.excelPositionCopyFormat,
                    g -> engine.copyLabel(g, false), engine.hits(), engine.book());
            Files.write(f.toPath(), data);
            flash("Đã xuất " + f.getName());
            engine.log("Đã xuất Excel: " + f.getAbsolutePath(), null);
        } catch (Exception e) {
            error("Xuất Excel lỗi: " + e.getMessage());
        }
    }

    void exportMatrix() {
        File f = save(Text.EXT_NAME + "_matrix.json", "JSON", "json");
        if (f != null) {
            write(f.toPath(), Reports.matrixJson(engine.groups(), engine.branches()));
        }
    }

    void exportHistory() {
        File f = save(Text.EXT_NAME + "_history.md", "Text / Markdown", "md", "txt");
        if (f != null) {
            write(f.toPath(), Reports.history(engine.branches(), engine.groups()));
        }
    }

    private void write(Path p, String text) {
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
            flash("Đã xuất " + p.getFileName());
        } catch (Exception e) {
            error("Ghi file lỗi: " + e.getMessage());
        }
    }

    File save(String name, String desc, String... ext) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter(desc, ext));
        fc.setSelectedFile(new File(name));
        if (fc.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File f = fc.getSelectedFile();
        if (!f.getName().contains(".")) {
            f = new File(f.getParentFile(), f.getName() + "." + ext[0]);
        }
        return f;
    }

    // =================================================================== dialogs
    String ask(String question, String initial) {
        Object v = JOptionPane.showInputDialog(root, question, initial);
        return v == null ? null : v.toString();
    }

    boolean confirmDialog(String q) {
        return JOptionPane.showConfirmDialog(root, q, Text.EXT_NAME, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    void info(String msg) {
        JOptionPane.showMessageDialog(root, msg, Text.EXT_NAME, JOptionPane.INFORMATION_MESSAGE);
    }

    void error(String msg) {
        JOptionPane.showMessageDialog(root, msg, Text.EXT_NAME, JOptionPane.ERROR_MESSAGE);
    }

    static JScrollPane scroll(JComponent c) {
        JScrollPane s = new JScrollPane(c);
        s.getVerticalScrollBar().setUnitIncrement(16);
        return s;
    }

    static void selectRow(JTable t, int modelRow) {
        if (modelRow < 0) {
            return;
        }
        int v = t.convertRowIndexToView(modelRow);
        if (v >= 0) {
            t.setRowSelectionInterval(v, v);
            t.scrollRectToVisible(t.getCellRect(v, 0, true));
        }
    }

    static AuditLevel[] AUDIT_FILTER_LEVELS = {null, AuditLevel.INFO, AuditLevel.WARN, AuditLevel.ERROR, AuditLevel.OK,
        AuditLevel.NONE, AuditLevel.PENDING};
    static String[] AUDIT_FILTER_LABELS = {"Tất cả API (All)", "Vàng INFO: Có Role lỗi / Lệch quyền",
        "Cam WARN: Nghi vấn Bypass (Guest 2xx)", "Đỏ ERROR: Có lỗi 5xx / Mất kết nối", "Xanh OK: Tất cả Role 2xx",
        "Chưa Gửi / Chưa Replay (Pending)", "Xám: Chờ xác nhận vị trí"};

    static boolean in(Set<?> s, Object o) {
        return s.contains(o);
    }

    static PlacementBook.Action action(String a) {
        return PlacementBook.Action.valueOf(a);
    }
}
