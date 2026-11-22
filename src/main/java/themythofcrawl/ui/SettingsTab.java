package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Config;
import themythofcrawl.core.Text;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.EmptyState;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.kit.SectionHeader;
import themythofcrawl.ui.theme.Theme;

/** 'Cấu hình' tab: responsive 1/2-col Card sections with live search filter, placeholders, and error outlines. */
final class SettingsTab {
    private record SectionEntry(Card card, String searchText) {
    }

    private final List<SectionEntry> sections = new ArrayList<>();
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
    private boolean twoCol = true;

    private final SearchField search = new SearchField("Tìm kiếm cài đặt (regex, 429, Excel, copy, chẩn đoán...)", 28);
    private final Holder content = new Holder();
    private final JScrollPane scroll;
    private final EmptyState emptyFilter = new EmptyState(t -> Icons.search(t.muted),
            "Không tìm thấy mục cài đặt phù hợp",
            "Thử từ khóa khác hoặc bấm xóa ô tìm kiếm.");

    private final JCheckBox scope = new JCheckBox("Chỉ bắt API nằm trong Target Scope của Burp");
    private final JCheckBox ignoreStatic = new JCheckBox("Bỏ qua file tĩnh có đuôi:");
    private final JTextField staticExt = new JTextField(32);
    private final JCheckBox regexOn = new JCheckBox("Chỉ bắt path khớp Regex:");
    private final JTextField regex = new JTextField(32);
    private final JLabel regexError = new JLabel("") {
        @Override
        public void updateUI() {
            super.updateUI();
            setForeground(Theme.current().danger.text());
        }
    };
    private final JCheckBox options = new JCheckBox("Bỏ qua OPTIONS / HEAD (CORS preflight)");
    private final JCheckBox dedup = new JCheckBox("Chỉ nhân bản mỗi endpoint 1 lần (Method + Host + Path)");
    private final JCheckBox dedupIds = new JCheckBox("Coi số / UUID / chuỗi hex dài trong path là {id} khi so trùng");
    private final JCheckBox dedupBranch = new JCheckBox("Chống trùng riêng theo từng Nhánh (được bắt lại API cũ khi sang nhánh mới)");
    private final JCheckBox confirmNew = new JCheckBox("Hỏi xác nhận vị trí API mới (màu xám, phải bấm Xác nhận)");
    private final JCheckBox restore = new JCheckBox("API đã xóa quay về chỗ cũ khi bắt lại (giữ chỗ)");
    private final JCheckBox dupNew = new JCheckBox("Gặp API trùng ở chỗ mới: tạo bản #2, #3 ... và hỏi giữ hay ẩn (màu tím)");
    private final JCheckBox ghosts = new JCheckBox("Hiện API đã xóa (giữ chỗ) trong cây");
    private final JComboBox<String> refreshPolicyBox = new JComboBox<>();
    private final JCheckBox autoDetectRefreshChk = new JCheckBox("Tự phát hiện API GET xuất hiện ngay sau hành động (<= 2.5s) & gợi ý tag Tải lại?");
    private final JComboBox<String> profile = new JComboBox<>();
    private final JTextField delay = new JTextField(6);
    private final JTextField jitter = new JTextField(6);
    private final JTextField concurrency = new JTextField(6);
    private final JTextField retries = new JTextField(6);
    private final JTextField threads = new JTextField(6);
    private final JLabel pacing = Ui.muted(" ");
    private final JCheckBox sendOriginal = new JCheckBox("Khi tự gửi: gửi cả tài khoản crawl cùng các vai trò");
    private final JCheckBox quietSend = new JCheckBox("Gửi riêng: mọi lần gửi của JCrawl đi thẳng tới server, Logger / Logger++ không thấy");
    private final JComboBox<String> actionModeBox = new JComboBox<>();
    private final JComboBox<String> lock2xxStrategyBox = new JComboBox<>();
    private final JCheckBox holdActionProxyChk = new JCheckBox("Giữ lại mọi API thao tác khi crawl (server không đổi)");
    private final JCheckBox number = new JCheckBox("Thêm số thứ tự vai trò vào tên tab (01_, 02_ ...) nếu tên chưa có");
    private final JCheckBox branchInTab = new JCheckBox("Gắn mã Nhánh vào tên tab: [F01-G01:POST /v1/...] thay vì [G01:POST /v1/...]");
    private final JTextField pathLen = new JTextField(6);
    private final JCheckBox targetOnly = new JCheckBox("Chỉ bắt API có trong danh sách Excel (thay cho bộ lọc Regex)");
    private final JCheckBox targetPosition = new JCheckBox("Tự xếp API vào nhánh theo cột POSITION của file (nếu ô đó có giá trị)");
    private final JCheckBox targetPrefix = new JCheckBox("Cho phép path thực tế có thêm tiền tố phía trước (vd /api/v1 + /apt/test)");
    private final JCheckBox targetIds = new JCheckBox("Coi số / UUID / chuỗi hex trong file là tham số (file ghi /users/123 vẫn khớp /users/456)");
    private final JComboBox<String> queryMode = new JComboBox<>();
    private final JLabel targetInfo = Ui.muted(" ");
    private final JTextField copyFormat = new JTextField(26);
    private final JComboBox<String> copyFallback = new JComboBox<>();
    private final JLabel copyCounter = Ui.muted(" ");
    private final JCheckBox positionCopy = new JCheckBox("Khi xuất Excel: cột POSITION trống ghi theo định dạng copy (vd 200. / > contact > send)");
    private final JLabel workspaceInfo = Ui.muted(" ");
    private final JLabel diagVersion = new JLabel(" ");
    private final JLabel diagLaf = new JLabel(" ");
    private final JLabel diagColors = new JLabel(" ");
    private final JLabel diagScale = new JLabel(" ");
    private final JLabel diagCopyNote = Ui.muted(" ");
    private final JComboBox<String> uiFontBox = new JComboBox<>();
    private final JComboBox<String> monoFontBox = new JComboBox<>();
    private final JComboBox<String> displayModeBox = new JComboBox<>();
    private final JComboBox<String> fontScaleBox = new JComboBox<>(new String[] {"90%", "100%", "110%", "115%", "120%"});
    private final JCheckBox animationsBox = new JCheckBox("Hiệu ứng trượt toast & mở thẻ nhẹ (<= 150 ms)", true);
    private final JLabel saved = Ui.muted(" ");

    SettingsTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        for (Config.RefreshPolicy rp : Config.RefreshPolicy.values()) {
            refreshPolicyBox.addItem(rp.label);
        }
        for (Config.SendProfile p : Config.SendProfile.values()) {
            profile.addItem(p.label);
        }
        for (themythofcrawl.core.ActionEngine.ActionMode am : themythofcrawl.core.ActionEngine.ActionMode.values()) {
            actionModeBox.addItem(am.label);
        }
        // V_8.2: short names in the box, one-line meaning on hover of each item.
        actionModeBox.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                for (themythofcrawl.core.ActionEngine.ActionMode am : themythofcrawl.core.ActionEngine.ActionMode.values()) {
                    if (am.label.equals(value)) {
                        setToolTipText(am.description);
                        if (index < 0) {
                            actionModeBox.setToolTipText(am.description);
                        }
                    }
                }
                return this;
            }
        });
        for (themythofcrawl.core.ActionEngine.Lock2xxStrategy ls : themythofcrawl.core.ActionEngine.Lock2xxStrategy.values()) {
            lock2xxStrategyBox.addItem(ls.label);
        }
        for (Config.QueryModeOption q : Config.QueryModeOption.values()) {
            queryMode.addItem(q.label);
        }
        for (Config.CopyFallback f : Config.CopyFallback.values()) {
            copyFallback.addItem(f.label);
        }
        for (themythofcrawl.ui.theme.FontManager.UiFontChoice uf : themythofcrawl.ui.theme.FontManager.UiFontChoice.values()) {
            uiFontBox.addItem(uf.label);
        }
        for (themythofcrawl.ui.theme.FontManager.MonoFontChoice mf : themythofcrawl.ui.theme.FontManager.MonoFontChoice.values()) {
            monoFontBox.addItem(mf.label);
        }
        for (UiDisplayMode dm : UiDisplayMode.values()) {
            displayModeBox.addItem(dm.label);
        }

        for (JComboBox<String> cb : List.of(refreshPolicyBox, profile, actionModeBox, lock2xxStrategyBox,
                queryMode, copyFallback, uiFontBox, monoFontBox, displayModeBox, fontScaleBox)) {
            cb.setPrototypeDisplayValue("Chế độ tiêu chuẩn (mặc định)");
            cb.setMinimumSize(new Dimension(150, 26));
            cb.addActionListener(Ui.safe(ev -> {
                Object sel = cb.getSelectedItem();
                if (sel != null) {
                    cb.setToolTipText(sel.toString());
                }
            }));
        }
        for (JTextField nf : List.of(delay, jitter, concurrency, retries, threads, pathLen)) {
            nf.setMinimumSize(new Dimension(76, 26));
            nf.setPreferredSize(new Dimension(92, 26));
        }
        for (JTextField tf : List.of(staticExt, regex, copyFormat)) {
            tf.setMinimumSize(new Dimension(120, 26));
            tf.setPreferredSize(new Dimension(180, 26));
        }

        staticExt.putClientProperty("JTextField.placeholderText", "css,js,png,jpg,svg,woff...");
        regex.putClientProperty("JTextField.placeholderText", "^/api/.*");
        delay.putClientProperty("JTextField.placeholderText", "50");
        jitter.putClientProperty("JTextField.placeholderText", "20");
        concurrency.putClientProperty("JTextField.placeholderText", "1");
        retries.putClientProperty("JTextField.placeholderText", "2");
        threads.putClientProperty("JTextField.placeholderText", "8");
        pathLen.putClientProperty("JTextField.placeholderText", "36");
        copyFormat.putClientProperty("JTextField.placeholderText", "{stt}. {path}");
        regexError.setFont(Theme.current().smallBoldFont);

        sections.add(section("Bắt API", t -> t.info,
                "Request qua Proxy được bắt và nhân bản theo vai trò. Bắt thủ công (chuột phải > " + Text.EXT_NAME + ") bỏ qua các bộ lọc này.",
                row(scope), pair(ignoreStatic, staticExt), pair(regexOn, regex), pair(null, regexError), row(options)));
        sections.add(section("Trùng lặp & vị trí", t -> t.dup,
                "Tránh nhân bản lại cùng một endpoint nhiều lần; quyết định nơi API mới / API bắt lại / API tải lại được xử lý.",
                row(dedup), row(dedupIds), row(dedupBranch), row(confirmNew), row(restore), row(dupNew), row(ghosts),
                field("API tải lại:", refreshPolicyBox, null),
                row(autoDetectRefreshChk),
                pair(Ui.button("Quản lý tag...", Icons::tag, ui::openTagManager),
                        Ui.muted("Tag và cách xử lý API tải lại"))));
        sections.add(section("Gửi request", t -> t.success,
                "Hàng đợi theo host chống 429 / 503; chế độ cho API thao tác (POST / PUT / PATCH / DELETE).",
                field("Chế độ gửi:", profile, null), field("Khoảng nghỉ (ms):", delay, pacing),
                field("Dao động (%):", jitter, Ui.muted("nghỉ = khoảng nghỉ +/- %")),
                field("Song song mỗi host:", concurrency, Ui.muted("1 = tuần tự")),
                field("Tự gửi lại 429/503:", retries, null), field("Tổng luồng gửi:", threads, null),
                row(sendOriginal),
                row(quietSend),
                field("Chế độ thao tác:", actionModeBox, Ui.muted("rê chuột để xem")),
                field("Gửi lại khi có 200:", lock2xxStrategyBox, null),
                row(holdActionProxyChk),
                pair(Ui.accent("Chặn & gửi lại...",
                        t -> Icons.workbench(t.accent.onSolid(), 14), t -> t.accent,
                        () -> ActionWorkbenchDialog.show(panel, engine, ui.api != null ? ui.api.currentGroup() : null,
                                null, ActionWorkbenchDialog.TAB_STRATEGY, () -> {
                                    load();
                                    ui.syncHeader();
                                })),
                        Ui.muted("Chế độ, Luật, Kiểm tra, Kho ID"))));
        sections.add(section("File Excel đầu vào", t -> t.pink,
                "Cột STT | PROTOCOL | PATH | POSITION. Tham số {id} {session} [id] (id) <id> :id * là giá trị động.",
                pair(Ui.button("Nạp file Excel (.xlsx)...", () -> ui.excel.load()), targetInfo), row(targetOnly), row(targetPosition),
                row(targetPrefix), row(targetIds), field("Query trong Excel:", queryMode, null)));
        sections.add(section("Repeater", t -> t.critical, "Cách đặt tên tab khi đẩy sang Burp Repeater.",
                row(number), row(branchInTab), field("Độ dài tối đa path:", pathLen, null)));
        sections.add(section("Copy STT + path", t -> t.teal,
                "Mẫu dùng được {stt} {path} {method} {endpoint} {url} {api}.",
                field("Mẫu copy:", copyFormat, Ui.muted("mặc định: {stt}. {path}")),
                field("Khi không có STT Excel:", copyFallback, copyCounter),
                pair(Ui.button("Đặt lại bộ đếm về 1", () -> {
                    if (ui.confirmDialog("Đặt lại bộ đếm STT tăng dần về 1?\nCác API sẽ được đánh số lại ở lần copy tiếp theo.")) {
                        engine.resetCopyCounter();
                        load();
                    }
                }), new JLabel(" ")), row(positionCopy)));
        sections.add(section("Workspace", t -> t.slate, Help.WORKSPACE.split("\n")[0],
                pair(Ui.button("Mở thư mục", t -> Icons.folder(t.fg), () -> {
                    try {
                        java.awt.Desktop.getDesktop().open(new java.io.File(engine.workspaceLocation()));
                    } catch (Exception e) {
                        Ui.copyToClipboard(engine.workspaceLocation());
                        ui.flash("Đã copy đường dẫn workspace");
                    }
                }), workspaceInfo)));
        uiFontBox.setToolTipText("Chọn phông chữ giao diện hỗ trợ dấu tiếng Việt rõ nét (nhúng sẵn offline trong JAR)");
        monoFontBox.setToolTipText("Chọn phông chữ đơn cách cho đường dẫn URL, JSON và HTTP Request/Response");
        displayModeBox.setToolTipText("Chuyển đổi 3 chế độ thanh công cụ: Chỉ Icon (+ Thẻ mô tả khi rê chuột), Icon + Chữ, hoặc Chỉ Chữ");
        fontScaleBox.setToolTipText("Đổi cỡ chữ toàn bộ giao diện theMythOf-JCrawl (90% / 100% / 110% / 115% / 120%)");
        animationsBox.setToolTipText("Bật / tắt hiệu ứng trượt toast và mở/đóng thẻ (<= 150 ms)");
        sections.add(section("Giao diện", t -> t.info,
                "Phông chữ, kiểu nút, cỡ chữ. Phần \"Thông tin\" bên dưới chỉ để xem: màu và sáng / tối lấy theo Burp. Rê chuột để xem chi tiết.",
                field("Kiểu nút:", displayModeBox, Ui.muted("icon / icon + chữ / chữ")),
                field("Font giao diện:", uiFontBox, Ui.muted("Be Vietnam Pro / IBM Plex")),
                field("Font mã / URL:", monoFontBox, Ui.muted("JetBrains Mono")),
                field("Cỡ chữ:", fontScaleBox, Ui.muted("áp dụng ngay")),
                row(animationsBox),
                field("Phiên bản:", diagVersion, null),
                field("Sáng / tối:", diagLaf, Ui.muted("theo Burp")),
                field("Màu:", diagColors, Ui.muted("tự động")),
                field("Cỡ hiển thị:", diagScale, null),
                pair(Ui.accent("Nhật ký...", t -> Icons.copy(t.accent.onSolid()), t -> t.accent, ui::openClaudeLog),
                        Ui.muted("mọi thao tác, lần gửi, lỗi; token / cookie đã che")),
                pair(Ui.button("Copy chẩn đoán (gửi khi báo lỗi)", () -> {
                    Ui.copyToClipboard(Diagnostics.fullReport(engine.workspaceName(), engine.workspaceLocation()));
                    diagCopyNote.setText("Đã copy thông tin chẩn đoán");
                    ui.flash("Đã copy thông tin chẩn đoán");
                }), diagCopyNote)));

        JPanel topBar = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        topBar.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_1, Theme.SPACE_2));
        topBar.add(search, BorderLayout.CENTER);
        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightBtns.setOpaque(false);
        rightBtns.add(Ui.accent("Áp dụng", t -> Icons.check(t.accent.onSolid()), t -> t.accent, () -> {
            apply();
            ui.flash("Đã áp dụng cấu hình.");
        }));
        rightBtns.add(Ui.button("Khôi phục mặc định", t -> Icons.undo(t.fg), () -> {
            if (ui.confirmDialog("Đưa mọi tùy chọn về mặc định?")) {
                Config d = new Config();
                d.copyCounter = engine.config().copyCounter;
                engine.applyConfig(d.normalized());
                UiPrefs.setAnimationsEnabled(ui.uiPrefs, true);
                UiPrefs.setFontScalePercent(ui.uiPrefs, 100);
                UiPrefs.setUiFontChoice(ui.uiPrefs, themythofcrawl.ui.theme.FontManager.UiFontChoice.BE_VIETNAM_PRO);
                UiPrefs.setMonoFontChoice(ui.uiPrefs, themythofcrawl.ui.theme.FontManager.MonoFontChoice.JETBRAINS_MONO);
                UiPrefs.setDisplayMode(ui.uiPrefs, UiDisplayMode.ICON_AND_TEXT);
                load();
                ui.syncHeader();
            }
        }));
        rightBtns.add(saved);
        topBar.add(rightBtns, BorderLayout.EAST);

        content.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));
        scroll = new JScrollPane(content);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(topBar, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        search.onChange(q -> relayoutSections());
        panel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                relayoutSections();
            }
        });
        panel.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing() && !shownOnce) {
                shownOnce = true;
                javax.swing.SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
            }
        });

        for (JCheckBox b : new JCheckBox[] {scope, ignoreStatic, regexOn, options, dedup, dedupIds, dedupBranch, confirmNew, restore, dupNew,
            ghosts, autoDetectRefreshChk, sendOriginal, quietSend, holdActionProxyChk, number, branchInTab, targetOnly, targetPosition, targetPrefix, targetIds, positionCopy}) {
            b.addActionListener(Ui.safe(e -> apply()));
            b.setOpaque(false);
        }
        animationsBox.setOpaque(false);
        animationsBox.addActionListener(Ui.safe(e -> {
            if (!loading) {
                UiPrefs.setAnimationsEnabled(ui.uiPrefs, animationsBox.isSelected());
                saved.setText("Đã lưu lúc " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            }
        }));
        fontScaleBox.addActionListener(Ui.safe(e -> {
            if (!loading) {
                Object sel = fontScaleBox.getSelectedItem();
                int pct = UiPrefs.fontScalePercent(null);
                if (sel != null) {
                    try {
                        pct = Integer.parseInt(sel.toString().replace("%", "").trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                UiPrefs.setFontScalePercent(ui.uiPrefs, pct);
                refreshDiagnostics();
                ui.syncHeader();
                relayoutSections();
                saved.setText("Đã lưu lúc " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            }
        }));
        uiFontBox.addActionListener(Ui.safe(e -> {
            if (!loading) {
                int idx = uiFontBox.getSelectedIndex();
                var choices = themythofcrawl.ui.theme.FontManager.UiFontChoice.values();
                if (idx >= 0 && idx < choices.length) {
                    UiPrefs.setUiFontChoice(ui.uiPrefs, choices[idx]);
                    refreshDiagnostics();
                    ui.syncHeader();
                    relayoutSections();
                    saved.setText("Đã đổi font giao diện lúc " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                }
            }
        }));
        monoFontBox.addActionListener(Ui.safe(e -> {
            if (!loading) {
                int idx = monoFontBox.getSelectedIndex();
                var choices = themythofcrawl.ui.theme.FontManager.MonoFontChoice.values();
                if (idx >= 0 && idx < choices.length) {
                    UiPrefs.setMonoFontChoice(ui.uiPrefs, choices[idx]);
                    refreshDiagnostics();
                    ui.syncHeader();
                    saved.setText("Đã đổi font Monospace lúc " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                }
            }
        }));
        displayModeBox.addActionListener(Ui.safe(e -> {
            if (!loading) {
                int idx = displayModeBox.getSelectedIndex();
                UiDisplayMode[] modes = UiDisplayMode.values();
                if (idx >= 0 && idx < modes.length) {
                    UiPrefs.setDisplayMode(ui.uiPrefs, modes[idx]);
                    ui.syncHeader();
                    saved.setText("Chế độ nút: " + modes[idx].label);
                }
            }
        }));
        for (JComboBox<?> c : new JComboBox<?>[] {refreshPolicyBox, profile, actionModeBox, lock2xxStrategyBox, queryMode, copyFallback}) {
            c.addActionListener(Ui.safe(e -> apply()));
        }
        regex.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { validateRegexOutline(); }
            @Override
            public void removeUpdate(DocumentEvent e) { validateRegexOutline(); }
            @Override
            public void changedUpdate(DocumentEvent e) { validateRegexOutline(); }
        });
        for (JTextField f : new JTextField[] {staticExt, regex, delay, jitter, concurrency, retries, threads, pathLen, copyFormat}) {
            f.addActionListener(Ui.safe(e -> apply()));
            f.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    apply();
                }
            });
        }
        relayoutSections();
        load();
    }

    private void validateRegexOutline() {
        String pat = regex.getText();
        boolean err = false;
        String msg = "";
        if (regexOn.isSelected() && pat != null && !pat.isEmpty()) {
            try {
                Pattern.compile(pat);
            } catch (PatternSyntaxException ex) {
                err = true;
                msg = "Regex lỗi: " + ex.getDescription();
            }
        }
        regex.putClientProperty("JComponent.outline", err ? "error" : null);
        regexError.setText(msg);
    }

    void relayoutSections() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Card> visible = new ArrayList<>();
        for (SectionEntry se : sections) {
            if (q.isEmpty() || se.searchText().contains(q)) {
                visible.add(se.card());
            }
        }
        int w = panel.getWidth() > 0 ? panel.getWidth() : scroll.getViewport().getWidth();
        twoCol = w >= 1040;

        content.removeAll();
        if (visible.isEmpty()) {
            content.setLayout(new BorderLayout());
            content.add(emptyFilter, BorderLayout.CENTER);
        } else if (twoCol && visible.size() > 1) {
            content.setLayout(new GridBagLayout());
            JPanel col0 = new JPanel();
            col0.setOpaque(false);
            col0.setLayout(new BoxLayout(col0, BoxLayout.Y_AXIS));
            JPanel col1 = new JPanel();
            col1.setOpaque(false);
            col1.setLayout(new BoxLayout(col1, BoxLayout.Y_AXIS));
            for (int i = 0; i < visible.size(); i++) {
                Card c = visible.get(i);
                c.setAlignmentX(0f);
                JPanel target = (i % 2 == 0) ? col0 : col1;
                if (target.getComponentCount() > 0) {
                    target.add(Box.createVerticalStrut(Theme.SPACE_2));
                }
                target.add(c);
            }
            col0.add(Box.createVerticalGlue());
            col1.add(Box.createVerticalGlue());
            JPanel grid = new JPanel(new GridLayout(1, 2, Theme.SPACE_2, 0));
            grid.setOpaque(false);
            grid.add(col0);
            grid.add(col1);
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.gridy = 0;
            gc.weightx = 1.0;
            gc.weighty = 1.0;
            gc.anchor = GridBagConstraints.NORTH;
            gc.fill = GridBagConstraints.HORIZONTAL;
            content.add(grid, gc);
        } else {
            content.setLayout(new GridBagLayout());
            JPanel col = new JPanel();
            col.setOpaque(false);
            col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
            for (int i = 0; i < visible.size(); i++) {
                Card c = visible.get(i);
                c.setAlignmentX(0f);
                if (i > 0) {
                    col.add(Box.createVerticalStrut(Theme.SPACE_2));
                }
                col.add(c);
            }
            col.add(Box.createVerticalGlue());
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.gridy = 0;
            gc.weightx = 1.0;
            gc.weighty = 1.0;
            gc.anchor = GridBagConstraints.NORTH;
            gc.fill = GridBagConstraints.HORIZONTAL;
            content.add(col, gc);
        }
        content.revalidate();
        content.repaint();
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
            refreshPolicyBox.setSelectedIndex(c.refreshPolicy.ordinal());
            autoDetectRefreshChk.setSelected(c.autoDetectRefresh);
            profile.setSelectedIndex(c.sendProfile.ordinal());
            delay.setText(String.valueOf(c.sendDelayMs));
            jitter.setText(String.valueOf(c.sendJitterPct));
            concurrency.setText(String.valueOf(c.hostConcurrency));
            retries.setText(String.valueOf(c.maxRetries));
            threads.setText(String.valueOf(c.threads));
            sendOriginal.setSelected(c.autoSendOriginal);
            quietSend.setSelected(c.quietSend);
            actionModeBox.setSelectedIndex(c.actionMode.ordinal());
            lock2xxStrategyBox.setSelectedIndex(c.lock2xxStrategy.ordinal());
            holdActionProxyChk.setSelected(c.holdActionAtProxy);
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
            copyCounter.setText("số tiếp theo: " + c.copyCounter);
            positionCopy.setSelected(c.excelPositionCopyFormat);
            pacing.setText(c.sendDelayMs > 0 ? "~" + (int) (60000.0 / c.sendDelayMs * c.hostConcurrency) + " req/phút/host"
                    : "không giãn cách (dễ 429)");
            targetOnly.setEnabled(engine.targets() != null);
            targetInfo.setText(engine.targets() == null ? "Chưa nạp file - bắt API bình thường"
                    : "Đã nạp " + engine.targets().size() + " API từ " + engine.targets().source());
            workspaceInfo.setText(engine.workspaceName() + " - " + engine.workspaceLocation());
            validateRegexOutline();
            animationsBox.setSelected(UiPrefs.animationsEnabled(ui.uiPrefs));
            fontScaleBox.setSelectedItem(UiPrefs.fontScalePercent(ui.uiPrefs) + "%");
            uiFontBox.setSelectedIndex(UiPrefs.uiFontChoice(ui.uiPrefs).ordinal());
            monoFontBox.setSelectedIndex(UiPrefs.monoFontChoice(ui.uiPrefs).ordinal());
            displayModeBox.setSelectedIndex(UiPrefs.displayMode(ui.uiPrefs).ordinal());
            refreshDiagnostics();
        } finally {
            loading = false;
        }
    }

    private void refreshDiagnostics() {
        java.awt.Font sf = Theme.current().smallFont;
        for (JLabel dl : new JLabel[] {diagVersion, diagLaf, diagColors, diagScale}) {
            dl.setFont(sf);
            dl.setMinimumSize(new Dimension(40, dl.getPreferredSize().height));
        }
        // V_8.2: human values first; the raw technical keys live in the hover tooltip and in "Copy chẩn đoán".
        diagVersion.setText(Diagnostics.VERSION);
        diagVersion.setToolTipText(Text.EXT_NAME + " " + Diagnostics.VERSION);
        diagLaf.setText((Diagnostics.isDark() ? "Tối" : "Sáng") + " · theo Burp (" + Diagnostics.lafName() + ")");
        diagLaf.setToolTipText("Đổi sáng / tối ở Burp: Settings > User interface > Appearance.  Kỹ thuật: "
                + Diagnostics.lafClass() + " · Panel.background=" + Diagnostics.colorHex("Panel.background")
                + " · Table.background=" + Diagnostics.colorHex("Table.background")
                + " · Component.accentColor=" + Diagnostics.colorHex("Component.accentColor"));
        diagColors.setText("Nền " + Diagnostics.colorHex("Panel.background") + " · Nhấn " + Diagnostics.colorHex("Component.accentColor"));
        diagColors.setToolTipText("Màu lấy tự động từ theme Burp, không cần chỉnh.");
        int scalePct = ui != null ? UiPrefs.fontScalePercent(ui.uiPrefs) : 100;
        diagScale.setText("Màn hình " + Diagnostics.screenZoom() + " · chữ JCrawl " + scalePct + "% · " + Diagnostics.fontSummary());
        diagScale.setToolTipText("Đổi cỡ chữ JCrawl ở ô \"Cỡ chữ\" phía trên. Độ phóng màn hình do hệ điều hành / Burp quyết định."
                + "  Kỹ thuật: sun.java2d.uiScale=" + Diagnostics.uiScale());
    }

    private int num(JTextField f, int def) {
        try {
            int val = Integer.parseInt(f.getText().trim());
            f.putClientProperty("JComponent.outline", null);
            return val;
        } catch (NumberFormatException e) {
            f.putClientProperty("JComponent.outline", "error");
            return def;
        }
    }

    void apply() {
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
        c.refreshPolicy = Config.RefreshPolicy.values()[Math.max(0, refreshPolicyBox.getSelectedIndex())];
        c.autoDetectRefresh = autoDetectRefreshChk.isSelected();
        Config.SendProfile p = Config.SendProfile.values()[Math.max(0, profile.getSelectedIndex())];
        int d = num(delay, c.sendDelayMs);
        int j = num(jitter, c.sendJitterPct);
        int k = num(concurrency, c.hostConcurrency);
        if (p != Config.SendProfile.CUSTOM && p == c.sendProfile && (d != p.delayMs || j != p.jitterPct || k != p.concurrency)) {
            p = Config.SendProfile.CUSTOM;
        }
        c.sendProfile = p;
        c.sendDelayMs = d;
        c.sendJitterPct = j;
        c.hostConcurrency = k;
        c.maxRetries = num(retries, c.maxRetries);
        c.threads = num(threads, c.threads);
        c.autoSendOriginal = sendOriginal.isSelected();
        c.quietSend = quietSend.isSelected();
        c.actionMode = themythofcrawl.core.ActionEngine.ActionMode.values()[Math.max(0, actionModeBox.getSelectedIndex())];
        c.lock2xxStrategy = themythofcrawl.core.ActionEngine.Lock2xxStrategy.values()[Math.max(0, lock2xxStrategyBox.getSelectedIndex())];
        c.holdActionAtProxy = holdActionProxyChk.isSelected();
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
        boolean regexBad = err != null && c.apiRegexEnabled;
        regex.putClientProperty("JComponent.outline", regexBad ? "error" : null);
        regexError.setText(regexBad ? "Regex lỗi: " + err : "");
        saved.setText("Đã lưu lúc " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        load();
        ui.syncHeader();
        ui.excel.stale = true;
    }

    // ---- Test / inspection helpers ----
    SearchField searchField() {
        return search;
    }

    JTextField regexField() {
        return regex;
    }

    JCheckBox regexCheckBox() {
        return regexOn;
    }

    JComboBox<String> fontScaleBox() {
        return fontScaleBox;
    }

    JComboBox<String> uiFontBox() {
        return uiFontBox;
    }

    JComboBox<String> monoFontBox() {
        return monoFontBox;
    }

    JComboBox<String> displayModeBox() {
        return displayModeBox;
    }

    JCheckBox animationsCheckBox() {
        return animationsBox;
    }

    int sectionCount() {
        return sections.size();
    }

    int visibleSectionCount() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        int n = 0;
        for (SectionEntry se : sections) {
            if (q.isEmpty() || se.searchText().contains(q)) {
                n++;
            }
        }
        return n;
    }

    boolean isTwoColumnLayout() {
        return twoCol;
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

    private SectionEntry section(String title, Function<Theme, Theme.Token> accentFn, String desc, Object[]... rows) {
        Card card = new Card(null, false);
        card.setPadding(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_3, Theme.SPACE_3);
        card.setLayout(new BorderLayout(0, Theme.SPACE_2));

        SectionHeader hdr = new SectionHeader(title, desc, null, null);
        card.add(hdr, BorderLayout.NORTH);

        StringBuilder corpus = new StringBuilder();
        corpus.append(title).append(' ').append(desc).append(' ');

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
                    gc.fill = GridBagConstraints.HORIZONTAL;
                    gc.weightx = 1;
                    Component comp = (Component) r[1];
                    if (comp instanceof JCheckBox cb) {
                        cb.setToolTipText(cb.getText());
                        cb.setMinimumSize(new Dimension(80, 22));
                        cb.setPreferredSize(new Dimension(Math.min(440, cb.getPreferredSize().width), Math.max(22, cb.getPreferredSize().height)));
                    }
                    collectText(comp, corpus);
                    body.add(comp, gc);
                }
                case "pair" -> {
                    if (r[1] != null) {
                        Component c1 = (Component) r[1];
                        collectText(c1, corpus);
                        if (c1 instanceof JComponent jc) {
                            Dimension pref = jc.getPreferredSize();
                            jc.setMinimumSize(new Dimension(Math.min(200, pref.width), Math.max(24, pref.height)));
                        }
                        body.add(c1, gc);
                    }
                    gc.gridx = 1;
                    gc.gridwidth = 2;
                    gc.weightx = 1;
                    gc.fill = GridBagConstraints.HORIZONTAL;
                    Component c2 = (Component) r[2];
                    if (c2 instanceof JLabel l) {
                        l.setMinimumSize(new Dimension(40, 20));
                    }
                    collectText(c2, corpus);
                    body.add(c2, gc);
                }
                default -> {
                    String lbl = (String) r[1];
                    corpus.append(lbl).append(' ');
                    JLabel fl = new JLabel(lbl);
                    Dimension lpref = fl.getPreferredSize();
                    fl.setMinimumSize(new Dimension(lpref.width, Math.max(22, lpref.height)));
                    body.add(fl, gc);
                    gc.gridx = 1;
                    Component c2 = (Component) r[2];
                    collectText(c2, corpus);
                    if (r[3] == null && (c2 instanceof JLabel || c2 instanceof JComboBox<?>)) {
                        gc.gridwidth = 2;
                        gc.weightx = 1;
                        gc.fill = GridBagConstraints.HORIZONTAL;
                    } else if (c2 instanceof JComboBox<?>) {
                        gc.weightx = 0.6;
                        gc.fill = GridBagConstraints.HORIZONTAL;
                    }
                    body.add(c2, gc);
                    if (r[3] != null) {
                        gc.gridx = 2;
                        gc.weightx = 1;
                        gc.fill = GridBagConstraints.HORIZONTAL;
                        JComponent note = (JComponent) r[3];
                        note.setMinimumSize(new Dimension(40, 20));
                        collectText(note, corpus);
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
        body.add(Box.createHorizontalGlue(), filler);
        card.add(body, BorderLayout.CENTER);
        return new SectionEntry(card, corpus.toString().toLowerCase(Locale.ROOT));
    }

    private static void collectText(Component c, StringBuilder out) {
        if (c instanceof javax.swing.AbstractButton ab) {
            out.append(ab.getText()).append(' ');
        } else if (c instanceof JLabel l) {
            out.append(l.getText()).append(' ');
        }
    }

    /** Tracks the viewport width so section headers/notes wrap cleanly instead of widening horizontally. */
    private static final class Holder extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 20;
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
