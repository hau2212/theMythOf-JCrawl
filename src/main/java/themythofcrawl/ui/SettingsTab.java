package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Config;
import themythofcrawl.core.Text;

/** 'Cấu hình' tab: colour-coded sections; every change is applied and saved at once. */
final class SettingsTab {
    final JPanel panel = new JPanel(new BorderLayout()) {
        @Override
        public void updateUI() {
            super.updateUI();
            if (diagVersion != null) {
                refreshDiagnostics();
            }
        }
    };
    private final MainPanel ui;
    private final Engine engine;
    private boolean loading;
    private boolean shownOnce;
    private final JCheckBox scope = new JCheckBox("Chỉ bắt API nằm trong Target Scope của Burp");
    private final JCheckBox ignoreStatic = new JCheckBox("Bỏ qua file tĩnh có đuôi:");
    private final JTextField staticExt = new JTextField(50);
    private final JCheckBox regexOn = new JCheckBox("Chỉ bắt path khớp Regex:");
    private final JTextField regex = new JTextField(50);
    private final JLabel regexError = new JLabel("");
    private final JCheckBox options = new JCheckBox("Bỏ qua OPTIONS / HEAD (CORS preflight)");
    private final JCheckBox dedup = new JCheckBox("Chỉ nhân bản mỗi endpoint 1 lần (Method + Host + Path)");
    private final JCheckBox dedupIds = new JCheckBox("Coi số / UUID / chuỗi hex dài trong path là {id} khi so trùng");
    private final JCheckBox dedupBranch = new JCheckBox("Chống trùng riêng theo từng Nhánh (được bắt lại API cũ khi sang nhánh mới)");
    private final JCheckBox confirmNew = new JCheckBox("Hỏi xác nhận vị trí API mới (màu xám, phải bấm Xác nhận)");
    private final JCheckBox restore = new JCheckBox("API đã xóa quay về chỗ cũ khi bắt lại (giữ chỗ)");
    private final JCheckBox dupNew = new JCheckBox("Gặp API trùng ở chỗ mới: tạo bản #2, #3 ... và hỏi giữ hay ẩn (màu tím)");
    private final JCheckBox ghosts = new JCheckBox("Hiện API đã xóa (👻 giữ chỗ) trong cây");
    private final JComboBox<String> profile = new JComboBox<>();
    private final JTextField delay = new JTextField(6);
    private final JTextField jitter = new JTextField(6);
    private final JTextField concurrency = new JTextField(6);
    private final JTextField retries = new JTextField(6);
    private final JTextField threads = new JTextField(6);
    private final JLabel pacing = new JLabel(" ");
    private final JCheckBox sendOriginal = new JCheckBox("Khi tự gửi: gửi cả API gốc (áp profile API gốc) cùng các role");
    private final JCheckBox number = new JCheckBox("Thêm số thứ tự role vào tên tab (01_, 02_ ...) nếu tên chưa có");
    private final JCheckBox branchInTab = new JCheckBox("Gắn mã Nhánh vào tên tab: [F01-G01:POST /v1/...] thay vì [G01:POST /v1/...]");
    private final JTextField pathLen = new JTextField(6);
    private final JCheckBox targetOnly = new JCheckBox("Chỉ bắt API có trong danh sách Excel (thay cho bộ lọc Regex)");
    private final JCheckBox targetPosition = new JCheckBox("Tự xếp API vào nhánh theo cột POSITION của file (nếu ô đó có giá trị)");
    private final JCheckBox targetPrefix = new JCheckBox("Cho phép path thực tế có thêm tiền tố phía trước (vd /api/v1 + /apt/test)");
    private final JCheckBox targetIds = new JCheckBox("Coi số / UUID / chuỗi hex trong file là tham số (file ghi /users/123 vẫn khớp /users/456)");
    private final JComboBox<String> queryMode = new JComboBox<>();
    private final JLabel targetInfo = new JLabel(" ");
    private final JTextField copyFormat = new JTextField(30);
    private final JComboBox<String> copyFallback = new JComboBox<>();
    private final JLabel copyCounter = new JLabel(" ");
    private final JCheckBox positionCopy = new JCheckBox("Khi xuất Excel: cột POSITION còn trống được ghi theo định dạng copy (vd 200. / > contact > button \"send\")");
    private final JLabel workspaceInfo = new JLabel(" ");
    private final JLabel diagVersion = new JLabel(" ");
    private final JLabel diagLaf = new JLabel(" ");
    private final JLabel diagColors = new JLabel(" ");
    private final JLabel diagScale = new JLabel(" ");
    private final JLabel diagCopyNote = new JLabel(" ");
    private final JLabel saved = new JLabel(" ");

    SettingsTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        for (Config.SendProfile p : Config.SendProfile.values()) {
            profile.addItem(p.label);
        }
        for (Config.QueryModeOption q : Config.QueryModeOption.values()) {
            queryMode.addItem(q.label);
        }
        for (Config.CopyFallback f : Config.CopyFallback.values()) {
            copyFallback.addItem(f.label);
        }
        Holder content = new Holder();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(section("1.  Bắt API từ Proxy", new Color(37, 99, 235),
                "Request nào đi qua Proxy được bắt và nhân bản theo Role. Bắt thủ công (chuột phải > theMythOfCrawl) bỏ qua các bộ lọc này.",
                row(scope), pair(ignoreStatic, staticExt), pair(regexOn, regex), pair(null, regexError), row(options)));
        content.add(section("2.  Chống trùng lặp & vị trí", new Color(147, 51, 234),
                "Tránh nhân bản lại cùng một endpoint nhiều lần; quyết định nơi API mới / API bắt lại được đặt.",
                row(dedup), row(dedupIds), row(dedupBranch), row(confirmNew), row(restore), row(dupNew), row(ghosts)));
        content.add(section("3.  Gửi request & chống lỗi 429", new Color(22, 163, 74),
                "Mọi request (tự gửi sau khi crawl, gửi hàng loạt, gửi lẻ) đi qua 1 hàng đợi theo từng host, giãn cách như người dùng "
                        + "thật; gặp 429 / 503 thì tạm dừng host đó (theo Retry-After), giảm tốc và tự gửi lại.",
                field("Chế độ gửi:", profile, null), field("Khoảng nghỉ giữa 2 request (ms):", delay, pacing),
                field("Dao động ngẫu nhiên (%):", jitter, new JLabel("nghỉ = khoảng nghỉ ± % này")),
                field("Số request song song mỗi host:", concurrency, new JLabel("1 = lần lượt như người thật")),
                field("Tự gửi lại khi gặp 429 / 503 (lần):", retries, null), field("Tổng số luồng gửi (mọi host):", threads, null),
                row(sendOriginal)));
        content.add(section("4.  Danh sách API từ file Excel (tùy chọn)", Palette.PINK,
                "Nạp file .xlsx gồm các cột STT | PROTOCOL (method) | PATH | POSITION. Tham số trong path như {id} [id] (id) <id> :id * "
                        + "được hiểu là giá trị bất kỳ. Không nạp file thì extension bắt API như bình thường.",
                pair(Ui.button("Nạp file Excel (.xlsx)...", () -> ui.excel.load()), targetInfo), row(targetOnly), row(targetPosition),
                row(targetPrefix), row(targetIds), field("Query trong file Excel:", queryMode, null)));
        content.add(section("5.  Burp Repeater & tên tab", Palette.ORANGE, "Cách đặt tên tab khi đẩy sang Burp Repeater.",
                row(number), row(branchInTab), field("Độ dài tối đa của path trong tên tab:", pathLen, null)));
        content.add(section("6.  Copy STT + Path (để dán lên server / báo cáo)", new Color(13, 148, 136),
                "Chuột phải API / nhánh > 'Copy STT + Path', icon copy ở mỗi API, hoặc Ctrl+Shift+C. STT lấy từ file Excel (dòng khớp); "
                        + "không có thì theo lựa chọn bên dưới. Mẫu dùng được {stt} {path} {method} {endpoint} {url} {api}.",
                field("Mẫu copy:", copyFormat, new JLabel("mặc định: {stt}. {path}")),
                field("Khi không có STT từ Excel:", copyFallback, copyCounter),
                pair(Ui.button("Đặt lại bộ đếm về 1", () -> {
                    if (ui.confirmDialog("Đặt lại bộ đếm STT tăng dần về 1?\nCác API đã được đánh số sẽ được đánh số lại ở lần copy tiếp theo.")) {
                        engine.resetCopyCounter();
                        load();
                    }
                }), new JLabel(" ")), row(positionCopy)));
        content.add(section("7.  Workspace (lưu dữ liệu)", new Color(71, 85, 105), Help.WORKSPACE.split("\n")[0],
                pair(Ui.button("📁 Mở thư mục", () -> {
                    try {
                        java.awt.Desktop.getDesktop().open(new java.io.File(engine.workspaceLocation()));
                    } catch (Exception e) {
                        Ui.copyToClipboard(engine.workspaceLocation());
                        ui.flash("Đã copy đường dẫn workspace");
                    }
                }), workspaceInfo)));
        content.add(section("8.  Giới thiệu & chẩn đoán", new Color(37, 99, 235),
                "Phiên bản đang chạy, Look & Feel (theme của Burp), màu nền / accent và thông số scale để kiểm tra khi gặp lỗi hiển thị.",
                field("Phiên bản:", diagVersion, null),
                field("Look & Feel (LAF):", diagLaf, null),
                field("Màu theme:", diagColors, null),
                field("Scale / Font:", diagScale, null),
                pair(Ui.button("Copy thông tin chẩn đoán", () -> {
                    Ui.copyToClipboard(Diagnostics.fullReport(engine.workspaceName(), engine.workspaceLocation()));
                    diagCopyNote.setText("Đã copy thông tin chẩn đoán");
                    ui.flash("Đã copy thông tin chẩn đoán");
                }), diagCopyNote)));
        JPanel btns = Ui.flow(FlowLayout.LEFT, Ui.button("Áp dụng", this::apply), Ui.button("Khôi phục mặc định", () -> {
            if (ui.confirmDialog("Đưa mọi tùy chọn về mặc định?")) {
                Config d = new Config();
                d.copyCounter = engine.config().copyCounter;
                engine.applyConfig(d.normalized());
                load();
                ui.syncHeader();
            }
        }), saved);
        btns.setAlignmentX(0f);
        content.add(btns);
        JScrollPane sp = new JScrollPane(content);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(sp, BorderLayout.CENTER);
        panel.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing() && !shownOnce) {
                shownOnce = true;
                javax.swing.SwingUtilities.invokeLater(() -> sp.getVerticalScrollBar().setValue(0));
            }
        });
        for (JCheckBox b : new JCheckBox[] {scope, ignoreStatic, regexOn, options, dedup, dedupIds, dedupBranch, confirmNew, restore, dupNew,
            ghosts, sendOriginal, number, branchInTab, targetOnly, targetPosition, targetPrefix, targetIds, positionCopy}) {
            b.addActionListener(e -> apply());
            b.setOpaque(false);
        }
        for (JComboBox<?> c : new JComboBox<?>[] {profile, queryMode, copyFallback}) {
            c.addActionListener(e -> apply());
        }
        for (JTextField f : new JTextField[] {staticExt, regex, delay, jitter, concurrency, retries, threads, pathLen, copyFormat}) {
            f.addActionListener(e -> apply());
            f.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    apply();
                }
            });
        }
        regexError.setForeground(new Color(200, 30, 30));
        load();
    }

    /** Show the stored configuration. */
    void load() {
        loading = true;
        try {
            Config c = engine.config();
            scope.setSelected(c.inScopeOnly);
            ignoreStatic.setSelected(c.ignoreStatic);
            staticExt.setText(c.staticExt);
            regexOn.setSelected(c.apiRegexEnabled);
            regex.setText(c.apiRegex);
            options.setSelected(c.ignoreOptionsHead);
            dedup.setSelected(c.dedup);
            dedupIds.setSelected(c.dedupNormalizeIds);
            dedupBranch.setSelected(c.dedupPerBranch);
            confirmNew.setSelected(c.confirmNewApis);
            restore.setSelected(c.restoreKnownPlace);
            dupNew.setSelected(c.dupNewPlace);
            ghosts.setSelected(c.showGhosts);
            profile.setSelectedIndex(c.sendProfile.ordinal());
            delay.setText(String.valueOf(c.sendDelayMs));
            jitter.setText(String.valueOf(c.sendJitterPct));
            concurrency.setText(String.valueOf(c.hostConcurrency));
            retries.setText(String.valueOf(c.maxRetries));
            threads.setText(String.valueOf(c.threads));
            sendOriginal.setSelected(c.autoSendOriginal);
            number.setSelected(c.numberRolesInTab);
            branchInTab.setSelected(c.includeBranchInTab);
            pathLen.setText(String.valueOf(c.shortPathLen));
            targetOnly.setSelected(c.targetOnly);
            targetPosition.setSelected(c.targetUsePosition);
            targetPrefix.setSelected(c.targetAllowPrefix);
            targetIds.setSelected(c.targetIdsAsParams);
            queryMode.setSelectedIndex(c.targetQueryMode.ordinal());
            copyFormat.setText(c.copyFormat);
            copyFallback.setSelectedIndex(c.copySttFallback.ordinal());
            copyCounter.setText("số tiếp theo nếu đếm tăng dần: " + c.copyCounter);
            positionCopy.setSelected(c.excelPositionCopyFormat);
            pacing.setText(c.sendDelayMs > 0 ? "tối đa ~" + (int) (60000.0 / c.sendDelayMs * c.hostConcurrency) + " request / phút cho mỗi host"
                    : "không giãn cách - rất dễ bị 429");
            targetOnly.setEnabled(engine.targets() != null);
            targetInfo.setText(engine.targets() == null ? "Chưa nạp file - extension bắt API như bình thường"
                    : "Đã nạp " + engine.targets().size() + " API từ " + engine.targets().source() + " (sheet " + engine.targets().sheet() + ")");
            workspaceInfo.setText(engine.workspaceName() + " - " + engine.workspaceLocation());
            refreshDiagnostics();
        } finally {
            loading = false;
        }
    }

    private void refreshDiagnostics() {
        diagVersion.setText(Text.EXT_NAME + " " + Diagnostics.VERSION);
        diagLaf.setText(Diagnostics.lafName() + " (" + Diagnostics.lafClass() + ") · dark=" + Diagnostics.isDark());
        diagColors.setText("Panel.background=" + Diagnostics.colorHex("Panel.background")
                + " · Table.background=" + Diagnostics.colorHex("Table.background")
                + " · Component.accentColor=" + Diagnostics.colorHex("Component.accentColor"));
        diagScale.setText("sun.java2d.uiScale=" + Diagnostics.uiScale() + " · font=" + Diagnostics.fontSummary());
    }

    private int num(JTextField f, int def) {
        try {
            return Integer.parseInt(f.getText().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void apply() {
        if (loading) {
            return;
        }
        Config c = engine.config().copy();
        c.inScopeOnly = scope.isSelected();
        c.ignoreStatic = ignoreStatic.isSelected();
        c.staticExt = staticExt.getText();
        c.apiRegexEnabled = regexOn.isSelected();
        c.apiRegex = regex.getText();
        c.ignoreOptionsHead = options.isSelected();
        c.dedup = dedup.isSelected();
        c.dedupNormalizeIds = dedupIds.isSelected();
        c.dedupPerBranch = dedupBranch.isSelected();
        c.confirmNewApis = confirmNew.isSelected();
        c.restoreKnownPlace = restore.isSelected();
        c.dupNewPlace = dupNew.isSelected();
        c.showGhosts = ghosts.isSelected();
        Config.SendProfile p = Config.SendProfile.values()[Math.max(0, profile.getSelectedIndex())];
        int d = num(delay, c.sendDelayMs);
        int j = num(jitter, c.sendJitterPct);
        int k = num(concurrency, c.hostConcurrency);
        if (p != Config.SendProfile.CUSTOM && p == c.sendProfile && (d != p.delayMs || j != p.jitterPct || k != p.concurrency)) {
            p = Config.SendProfile.CUSTOM;   // the user typed their own numbers
        }
        c.sendProfile = p;
        c.sendDelayMs = d;
        c.sendJitterPct = j;
        c.hostConcurrency = k;
        c.maxRetries = num(retries, c.maxRetries);
        c.threads = num(threads, c.threads);
        c.autoSendOriginal = sendOriginal.isSelected();
        c.numberRolesInTab = number.isSelected();
        c.includeBranchInTab = branchInTab.isSelected();
        c.shortPathLen = num(pathLen, c.shortPathLen);
        c.targetOnly = targetOnly.isSelected();
        c.targetUsePosition = targetPosition.isSelected();
        c.targetAllowPrefix = targetPrefix.isSelected();
        c.targetIdsAsParams = targetIds.isSelected();
        c.targetQueryMode = Config.QueryModeOption.values()[Math.max(0, queryMode.getSelectedIndex())];
        c.copyFormat = copyFormat.getText();
        c.copySttFallback = Config.CopyFallback.values()[Math.max(0, copyFallback.getSelectedIndex())];
        c.excelPositionCopyFormat = positionCopy.isSelected();
        String err = engine.applyConfig(c.normalized());
        regexError.setText(err != null && c.apiRegexEnabled ? "Regex lỗi: " + err : "");
        saved.setText("Đã lưu lúc " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        load();
        ui.syncHeader();
        ui.excel.stale = true;
    }

    // ------------------------------------------------------------------ layout
    private static Object[] row(JComponent c) {
        return new Object[] {"row", c};
    }

    private static Object[] pair(JComponent a, JComponent b) {
        return new Object[] {"pair", a, b};
    }

    private static Object[] field(String label, JComponent c, JComponent note) {
        return new Object[] {"field", label, c, note};
    }

    private static JPanel section(String title, Color accent, String desc, Object[]... rows) {
        Palette pal = Palette.ui();
        Color tint = Palette.mix(pal.bg, accent, pal.dark ? 0.10 : 0.05);
        JPanel s = new JPanel(new BorderLayout(0, 6));
        s.setAlignmentX(0f);
        s.setBackground(tint);
        s.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, 10, 0), BorderFactory.createMatteBorder(0, 4, 0, 0, accent)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, t.getFont().getSize2D() + 2));
        t.setForeground(pal.dark ? Palette.mix(accent, Color.WHITE, 0.35) : accent);
        JTextArea d = new JTextArea(desc);
        d.setEditable(false);
        d.setLineWrap(true);
        d.setWrapStyleWord(true);
        d.setOpaque(false);
        d.setForeground(pal.muted);
        JPanel head = new JPanel(new BorderLayout(0, 2));
        head.setOpaque(false);
        head.add(t, BorderLayout.NORTH);
        head.add(d, BorderLayout.CENTER);
        s.add(head, BorderLayout.NORTH);
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 2, 2, 6);
        gc.anchor = GridBagConstraints.WEST;
        int y = 0;
        for (Object[] r : rows) {
            gc.gridy = y++;
            gc.gridx = 0;
            gc.gridwidth = 1;
            gc.weightx = 0;
            gc.fill = GridBagConstraints.NONE;
            switch ((String) r[0]) {
                case "row" -> {
                    gc.gridwidth = 3;
                    body.add((Component) r[1], gc);
                }
                case "pair" -> {
                    if (r[1] != null) {
                        body.add((Component) r[1], gc);
                    }
                    gc.gridx = 1;
                    gc.gridwidth = 2;
                    gc.weightx = 1;
                    gc.fill = GridBagConstraints.HORIZONTAL;
                    body.add((Component) r[2], gc);
                }
                default -> {
                    body.add(new JLabel((String) r[1]), gc);
                    gc.gridx = 1;
                    body.add((Component) r[2], gc);
                    if (r[3] != null) {
                        gc.gridx = 2;
                        gc.weightx = 1;
                        JComponent note = (JComponent) r[3];
                        note.setForeground(pal.muted);
                        body.add(note, gc);
                    }
                }
            }
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 3;
        filler.gridy = 0;
        filler.weightx = 1;
        filler.fill = GridBagConstraints.HORIZONTAL;
        body.add(javax.swing.Box.createHorizontalGlue(), filler);   // keeps every row left-aligned
        s.add(body, BorderLayout.CENTER);
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, s.getPreferredSize().height + 400));
        return s;
    }

    /** Tracks the viewport width so section notes wrap instead of widening the page. */
    private static final class Holder extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return 160;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
