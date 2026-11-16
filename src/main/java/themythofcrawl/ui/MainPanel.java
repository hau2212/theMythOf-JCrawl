package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
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

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;

import themythofcrawl.app.Changes;
import themythofcrawl.app.Engine;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Config;
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

/**
 * The extension's tab: one compact toolbar (capture, send mode, queue, destination, decisions, options,
 * detach) + a status line + the tabs. A single Swing timer drains the engine's changes every 200 ms.
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
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel main = new JPanel(new BorderLayout());
    final JTabbedPane tabs = new JTabbedPane();
    private final JCheckBox capture = new JCheckBox("● Capture Proxy");
    private final JButton sendMode = new JButton(" ");
    private final JButton queue = new JButton("Hàng đợi: 0");
    private final JButton dest = new JButton(" ");
    private final JButton todo = new JButton("Cần xử lý: 0");
    private final JButton options = new JButton("⚙ ▾");
    private final JButton detach = new JButton("🗗");
    private final JButton workspace = new JButton("📁 Workspace ▾");
    final JLabel status = new JLabel(" ");
    private final Timer timer;
    private JFrame window;
    private String flash;
    private long flashUntil;
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
        main.add(buildHeader(), BorderLayout.NORTH);
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
        root.add(main, BorderLayout.CENTER);
        try {
            host.applyTheme(root);
        } catch (RuntimeException ignored) {
            // outside Burp
        }
        syncHeader();
        api.reloadAll();
        timer = new Timer(TICK_MS, e -> tick(false));
        timer.setCoalesce(true);
        timer.start();
    }

    public JPanel root() {
        return root;
    }

    public void dispose() {
        timer.stop();
        if (window != null) {
            window.dispose();
        }
        treeTab.dispose();
    }

    // =================================================================== header
    private JPanel buildHeader() {
        Config c = engine.config();
        capture.setSelected(c.captureEnabled);
        capture.setFont(capture.getFont().deriveFont(Font.BOLD));
        capture.addActionListener(e -> update(x -> x.captureEnabled = capture.isSelected()));
        sendMode.setFont(sendMode.getFont().deriveFont(Font.BOLD));
        sendMode.setMargin(new Insets(2, 8, 2, 8));
        sendMode.addActionListener(e -> toggleSendMode());
        queue.setMargin(new Insets(2, 6, 2, 6));
        queue.addActionListener(e -> engine.cancelPending("Đã hủy"));
        dest.setFont(dest.getFont().deriveFont(Font.BOLD));
        dest.setBackground(Palette.ORANGE);
        dest.setForeground(Color.WHITE);
        dest.setMargin(new Insets(2, 8, 2, 8));
        dest.addActionListener(e -> destinationMenu());
        todo.setFont(todo.getFont().deriveFont(Font.BOLD));
        todo.setMargin(new Insets(2, 8, 2, 8));
        todo.addActionListener(e -> todoMenu());
        options.setToolTipText("Tùy chọn: phạm vi bắt, regex, tự gửi, xác nhận vị trí, giữ chỗ, API trùng...");
        options.setMargin(new Insets(2, 7, 2, 7));
        options.addActionListener(e -> optionsMenu());
        detach.setMargin(new Insets(1, 6, 1, 6));
        detach.setToolTipText("Tách cả extension ra cửa sổ riêng (bấm lại / đóng cửa sổ để gắn vào tab Burp)");
        detach.addActionListener(e -> toggleDetach());
        workspace.setMargin(new Insets(1, 6, 1, 6));
        workspace.addActionListener(e -> workspaceMenu());

        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 1));
        bar.add(capture);
        bar.add(sendMode);
        bar.add(queue);
        bar.add(Ui.info(Help.CAPTURE, "Bắt & gửi"));
        bar.add(new JLabel(" │ "));
        bar.add(Ui.bold("➜ Đích:"));
        bar.add(dest);
        bar.add(Ui.info(Help.DEST, "Đích của request mới"));
        bar.add(todo);
        bar.add(Ui.info(Help.TODO, "Cần xử lý"));
        bar.add(new JLabel(" │ "));
        bar.add(options);
        bar.add(detach);
        JPanel line = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 0));
        line.add(Ui.info(Help.COLORS, "Màu & nhãn"));
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 11f));
        status.setForeground(Palette.mix(Palette.ui().fg, Palette.ui().bg, 0.30));
        line.add(status);
        line.add(workspace);
        line.add(Ui.info(Help.WORKSPACE, "Workspace"));
        JPanel top = new JPanel(new BorderLayout(4, 2));
        top.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        top.add(bar, BorderLayout.NORTH);
        top.add(line, BorderLayout.SOUTH);
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

    private void toggleSendMode() {
        boolean on = !engine.config().autoSend;
        update(c -> c.autoSend = on);
        if (on) {
            int n = engine.sendAllUnsent();
            engine.log("▶ Bật GỬI: bắt xong gửi luôn" + (n > 0 ? "; xếp hàng " + n + " request chưa gửi." : "."), null);
        } else {
            engine.log("■ CHỈ CRAWL: chỉ ghi lại API, không gửi.", null);
        }
    }

    void syncHeader() {
        Config c = engine.config();
        capture.setSelected(c.captureEnabled);
        boolean send = c.autoSend;
        sendMode.setText(send ? "■ ĐANG GỬI (bấm để chỉ crawl)" : "▶ CHỈ CRAWL (bấm để gửi)");
        sendMode.setBackground(send ? Palette.GREEN : new Color(100, 116, 139));
        sendMode.setForeground(Color.WHITE);
        Branch b = engine.activeBranch();
        String name = b.name();
        String shown = name.length() <= 60 ? name : "…" + name.substring(name.length() - 58);
        if (!dest.getText().equals(shown + " ▾")) {
            dest.setText(shown + " ▾");
            dest.setToolTipText("Request mới sẽ vào: " + name + "  (bấm để đổi)");
        }
        Engine.Stats st = engine.stats();
        todo.setText("Cần xử lý: " + st.pending() + (st.dupWaiting() > 0 ? " (⧉" + st.dupWaiting() + ")" : ""));
        todo.setBackground(st.pending() > 0 ? new Color(128, 128, 140) : null);
        todo.setForeground(st.pending() > 0 ? Color.WHITE : null);
        SendDispatcher.Snapshot q = engine.queue();
        int waiting = q.queued() + q.inFlight();
        queue.setText(q.waitSeconds() > 0.5 ? String.format("Hàng đợi: %d (chờ %.0fs)", waiting, q.waitSeconds())
                : "Hàng đợi: " + waiting);
        queue.setEnabled(waiting > 0);
        workspace.setText("📁 " + engine.workspaceName() + " ▾");
        workspace.setToolTipText(engine.workspaceLocation());
        String text = (send ? "Gửi: BẬT" : "Chỉ crawl") + " | Roles: " + engine.roles().enabledCount() + "/" + engine.roles().count()
                + " | Nhánh: " + st.branches() + " | API: " + st.apis() + " (Vàng: " + st.info() + ")"
                + (st.hidden() > 0 ? " | 🙈 " + st.hidden() + " ẩn" : "")
                + (engine.skippedCount() > 0 ? " | Bỏ qua: " + engine.skippedCount() + " (" + engine.lastSkipReason() + ")" : "")
                + (engine.targets() != null ? " | Excel: " + engine.hits().rowsSeen() + "/" + engine.targets().size() + " dòng đã gặp" : "");
        if (flash != null && System.currentTimeMillis() < flashUntil) {
            text = flash;
        }
        status.setText(text);
    }

    public void flash(String text) {
        flash = text;
        flashUntil = System.currentTimeMillis() + 6000;
        status.setText(text);
    }

    private void destinationMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("📂 Chọn đích từ danh sách...", this::pickDestination));
        m.add(Ui.item("🌳 Lấy nhánh đang chọn trong cây", this::destinationFromTree));
        m.add(Ui.item("＋ Nhánh con mới của đích...", () -> {
            String n = ask("Đường dẫn nhánh mới (ngăn cách bởi '>'), sẽ thành đích cho request mới:",
                    engine.activeBranch().name() + " > ");
            if (n != null && !n.replace(">", "").isBlank()) {
                setDestination(n);
            }
        }));
        m.add(Ui.item("＋ Nhánh mới cùng cấp (nút / action tiếp theo)...", this::nextSibling));
        m.add(Ui.item("⬆ Lên 1 cấp", () -> setDestination(themythofcrawl.core.Hierarchy.parent(engine.activeBranch().name()))));
        m.add(Ui.item("✏ Đổi tên / sửa đường dẫn đích...", () -> {
            Branch b = engine.activeBranch();
            String n = ask("Sửa đường dẫn của đích (các API trong nhánh này đổi theo):", b.name());
            if (n != null && !n.isBlank()) {
                engine.renameBranch(b.id(), n);
            }
        }));
        JMenu more = new JMenu("Khác");
        more.add(Ui.item("⚙ Quản lý các cấp của đích (thêm / xóa / sửa / đổi thứ tự)...",
                () -> LevelsDialog.show(root, engine)));
        more.add(Ui.item("🗑 Xóa nhánh đích...", () -> deleteBranchDialog(engine.activeBranch().name())));
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
        flash("➜ Request mới sẽ vào: " + engine.activeBranch().name());
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
        m.add(Ui.item("🧭 Mở danh sách Cần xử lý (giải thích + nút cho từng API)...", () -> showAudit(0)));
        m.addSeparator();
        m.add(Ui.item("✔ Giữ chỗ hiện tại cho cả " + n + " API", n > 0, () -> engine.confirm(engine.pendingGroups(), "xác nhận tất cả")));
        m.add(Ui.item("↪ Chuyển cả " + n + " API sang path khác...", n > 0, () -> moveDialog(engine.pendingGroups())));
        m.add(Ui.item("🙈 Không giữ " + dups.size() + " API trùng (ẩn, vẫn lưu ở Audit)", !dups.isEmpty(),
                () -> engine.hide(engine.pendingGroups().stream().filter(ApiGroup::dupPending).toList(), "không giữ")));
        m.add(Ui.item("🔎 Lọc bảng API: chỉ API đang chờ", n > 0, () -> {
            tabs.setSelectedComponent(api.panel);
            api.filterPending();
        }));
        m.addSeparator();
        m.add(Ui.item("🙈 Xem API trùng & đang ẩn (" + engine.hiddenGroups().size() + " ẩn)", () -> showAudit(2)));
        m.add(Ui.item("🗑 Xóa " + n + " API đang chờ...", n > 0, () -> {
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
            {"Hiện API đã xóa (👻 giữ chỗ) trong cây", c.showGhosts, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.showGhosts = v},
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
        m.add(Ui.item("⚙ Mở tab Cấu hình chi tiết...", () -> tabs.setSelectedComponent(settings.panel)));
        m.add(Ui.item("♻ Reset Dedup Cache (Cho phép bắt lại API cũ)", engine::resetDedup));
        m.show(options, 0, options.getHeight());
    }

    // =================================================================== workspace
    private void workspaceMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Đang mở: " + engine.workspaceName() + " - " + engine.workspaceLocation(), false, () -> { }));
        m.addSeparator();
        m.add(Ui.item("＋ Workspace mới...", () -> {
            String n = ask("Tên workspace mới (thuộc project '" + host.projectName() + "'):", "Workspace mới");
            if (n != null && !n.isBlank()) {
                openWorkspace(workspaces.create(host.projectName(), n.trim()));
            }
        }));
        JMenu open = new JMenu("📂 Mở workspace khác");
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
        m.add(Ui.item("📁 Mở thư mục workspace trong máy", () -> {
            try {
                Desktop.getDesktop().open(new File(engine.workspaceLocation()));
            } catch (Exception e) {
                Ui.copyToClipboard(engine.workspaceLocation());
                flash("Đã copy đường dẫn workspace: " + engine.workspaceLocation());
            }
        }));
        m.add(Ui.item("📌 Đổi thư mục gốc (nơi tạo theMythOfCrawl_workspace)...", () -> {
            JFileChooser fc = new JFileChooser(workspaces.root().toFile());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                workspaces.setRoot(fc.getSelectedFile().toPath());
                openWorkspace(workspaces.start(projectId, host.projectName()));
            }
        }));
        m.add(Ui.item("🗑 Xóa một workspace khác...", () -> {
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
        flash("📁 Đang dùng workspace " + st.name() + " (" + engine.groups().size() + " API)");
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
        root.remove(main);
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
        window.getContentPane().add(main);
        window.setSize(new Dimension(Math.max(1100, size.width), Math.max(760, size.height)));
        window.setLocationRelativeTo(null);
        detach.setText("🗗 Gắn lại");
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
        root.add(main, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
        detach.setText("🗗");
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
        if (b.status() || b.structure() || force || !b.added().isEmpty()) {
            syncHeader();
            roles.refreshCounts();
        } else if (flash != null && System.currentTimeMillis() > flashUntil) {
            flash = null;
            syncHeader();
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
            flash("▶ Xếp hàng gửi " + gs.size() + " API");
        }
    }

    /** Push with the Repeater mode chosen in the 'Burp Repeater ▾' menu. */
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
            flash("📤 Đã đẩy sang Repeater (copy tên Group '" + title + "')");
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
        flash("📋 Đã copy " + gs.size() + " dòng: " + text.split("\n")[0]);
    }

    void copyBranchPath() {
        String b = selectedBranch();
        if (b != null) {
            Ui.copyToClipboard(b);
            flash("📋 Đã copy path nhánh: " + b);
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
        flash("↪ Đã chuyển " + n + " API sang " + p);
        return n;
    }

    void revert(Collection<ApiGroup> gs) {
        int n = engine.revert(gs);
        flash(n > 0 ? "↶ " + n + " API đã về vị trí trước" : "Không có vị trí trước để quay về");
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
        m.add(Ui.item("✔ Xác nhận vị trí (" + pending.size() + " API đang chờ)", !pending.isEmpty(), () -> engine.confirm(pending, "")));
        m.add(Ui.item("↪ Chuyển " + gs.size() + " API sang đường dẫn khác...", !gs.isEmpty(), () -> moveDialog(gs)));
        m.add(Ui.item("↶ Đưa " + gs.size() + " API về vị trí trước", gs.stream().anyMatch(g -> !g.placeHistory().isEmpty()),
                () -> revert(gs)));
        if (gs.size() == 1) {
            m.add(Ui.item("🧭 Lịch sử vị trí API #" + gs.get(0).gid(), () -> showPlaceOf(gs.get(0).bookKey())));
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
        "Chưa Gửi / Chưa Replay (Pending)", "Xám ⏳: Chờ xác nhận vị trí"};

    static boolean in(Set<?> s, Object o) {
        return s.contains(o);
    }

    static PlacementBook.Action action(String a) {
        return PlacementBook.Action.valueOf(a);
    }
}
