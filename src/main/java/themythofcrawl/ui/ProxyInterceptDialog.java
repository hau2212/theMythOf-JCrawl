package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ProxyInterceptQueue;
import themythofcrawl.core.ProxyInterceptQueue.PendingRequest;
import themythofcrawl.core.ProxyInterceptQueue.ResolutionKind;
import themythofcrawl.core.Text;
import themythofcrawl.model.ActionInterceptRule;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SectionHeader;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.kit.WrappedText;
import themythofcrawl.ui.theme.Theme;

/**
 * Floating, draggable, resizable Live Proxy Intercept Queue (Mode #5 — Chặn chờ duyệt trực tiếp).
 * <p>
 * Whenever a browser request matches the user's chosen scope (any Branch/Path, any specific API,
 * Global Method matrix, or a proactive {@link ActionInterceptRule} with {@code INTERCEPT_WAIT}),
 * it pauses here on the Proxy worker thread so the user can inspect/edit the raw HTTP message
 * and click {@code Gửi lên Server (Forward)}, {@code Lưu vào bảng &amp; Giữ}, or {@code Hủy bỏ (Drop)}.
 */
public final class ProxyInterceptDialog {
    private static final Pattern CONTENT_LENGTH_HEADER =
            Pattern.compile("(?im)^Content-Length\\s*:\\s*\\d+\\r?$");

    private static FloatingRef activeFloating;

    private record FloatingRef(
            WeakReference<MainPanel> ownerUi,
            WeakReference<JDialog> dialog,
            Runnable refresh) {
    }

    /** Headless-testable view bundle of the Live Proxy Intercept Queue. */
    public record View(
            JPanel panel,
            JTable queueTable,
            JTextArea requestEditor,
            JToggleButton pinButton,
            Runnable refresh,
            Runnable dispose) {
    }

    private ProxyInterceptDialog() {
    }

    /** Opens (or raises &amp; refreshes) the floating, draggable, resizable Live Proxy Intercept window. */
    public static void showFloating(MainPanel ui) {
        if (ui == null) {
            return;
        }
        FloatingRef ref = activeFloating;
        if (ref != null) {
            MainPanel prevUi = ref.ownerUi().get();
            JDialog prevDlg = ref.dialog().get();
            if (prevUi == ui && prevDlg != null && prevDlg.isDisplayable() && prevDlg.isVisible()) {
                ref.refresh().run();
                prevDlg.toFront();
                prevDlg.requestFocus();
                return;
            }
        }

        Prefs prefs = UiPrefs.resolve(null, ui.engine);
        Window owner = SwingUtilities.getWindowAncestor(ui.root());
        JDialog dlg = new JDialog(owner, "Hàng chờ Chặn Proxy trực tiếp — " + Text.EXT_NAME,
                JDialog.ModalityType.MODELESS);
        dlg.setResizable(true);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        View view = buildView(ui.engine, ui, dlg::dispose, pinned -> {
            try {
                dlg.setAlwaysOnTop(pinned);
            } catch (SecurityException ignored) {
                // Restricted environments may disallow alwaysOnTop
            }
        });

        dlg.setContentPane(view.panel());

        dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeInterceptQueue");
        dlg.getRootPane().getActionMap().put("closeInterceptQueue", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dlg.dispose();
            }
        });

        dlg.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (dlg.isVisible()) {
                    UiPrefs.setInterceptSize(prefs, dlg.getWidth(), dlg.getHeight());
                }
            }
        });
        dlg.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                UiPrefs.setInterceptSize(prefs, dlg.getWidth(), dlg.getHeight());
            }

            @Override
            public void windowClosed(WindowEvent e) {
                view.dispose().run();
                FloatingRef cur = activeFloating;
                if (cur != null && cur.dialog().get() == dlg) {
                    activeFloating = null;
                }
            }
        });

        Dimension pref = UiPrefs.interceptSize(prefs);
        dlg.setMinimumSize(new Dimension(860, 520));
        dlg.setSize(pref);
        dlg.setLocationRelativeTo(owner);
        Theme.track(dlg);

        activeFloating = new FloatingRef(new WeakReference<>(ui), new WeakReference<>(dlg), view.refresh());
        dlg.setVisible(true);
    }

    /** Closes any open floating window owned by {@code ui} (called on {@link MainPanel#dispose()}). */
    static void closeFor(MainPanel ui) {
        FloatingRef ref = activeFloating;
        if (ref != null && ref.ownerUi().get() == ui) {
            JDialog dlg = ref.dialog().get();
            activeFloating = null;
            if (dlg != null && dlg.isDisplayable()) {
                dlg.dispose();
            }
        }
    }

    /** Builds the pure-Swing view (also used by headless tests &amp; screenshot verifiers). */
    public static View buildView(
            Engine engine,
            MainPanel ui,
            Runnable onClose,
            Consumer<Boolean> onPinToggle) {
        Theme t0 = Theme.current();
        ActionEngine ae = engine.actionEngine();
        ProxyInterceptQueue queue = engine.proxyQueue();

        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setOpaque(true);
        root.setBackground(t0.bg);
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.SPACE_2, 0));

        // ------------------------------------------------------------------
        // 1. HEADER + PIN TOGGLE + LIVE COUNT PILL
        // ------------------------------------------------------------------
        Pill liveStatusPill = new Pill("Đang tắt", t -> t.neutral, t -> Icons.strategyShield(t.muted, 12));

        JToggleButton pinBtn = new JToggleButton("Ghim");
        pinBtn.setIcon(Icons.themed(t -> Icons.pin(t.muted, 12)));
        pinBtn.setFocusPainted(false);
        pinBtn.setMargin(new Insets(2, 8, 2, 8));
        pinBtn.setFont(t0.smallBoldFont);
        pinBtn.setToolTipText("Ghim cửa sổ nổi phía trên Burp Suite để vừa thao tác trình duyệt vừa bấm Gửi");
        pinBtn.addActionListener(e -> {
            boolean pinned = pinBtn.isSelected();
            Theme t = Theme.current();
            pinBtn.setIcon(Icons.themed(th -> Icons.pin(pinned ? th.accent.text() : th.muted, 12)));
            pinBtn.setForeground(pinned ? t.accent.text() : t.fg);
            if (onPinToggle != null) {
                onPinToggle.accept(pinned);
            }
        });

        SectionHeader headerInner = new SectionHeader(
                "Hàng chờ Chặn Proxy trực tiếp",
                "Chặn đứng request khi bấm nút trên trình duyệt và đợi bạn bấm Gửi lên Server, Lưu giữ trong bảng hoặc Hủy bỏ.",
                Ui.flow(FlowLayout.RIGHT, liveStatusPill, pinBtn),
                null);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(true);
        header.setBackground(t0.surfaceAlt);
        header.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
        header.add(headerInner, BorderLayout.CENTER);

        // ------------------------------------------------------------------
        // 2. QUICK-SCOPE CONTROL BAR (Master Switch + Method Matrix + Pin Path/API)
        // ------------------------------------------------------------------
        JPanel controlsCard = new JPanel();
        controlsCard.setLayout(new BoxLayout(controlsCard, BoxLayout.Y_AXIS));
        controlsCard.setOpaque(true);
        controlsCard.setBackground(t0.surface);
        controlsCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, t0.border),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        final boolean[] syncingUi = {false};

        SegmentedToggle masterToggle = new SegmentedToggle(
                new SegmentedToggle.Option("TẮT", "Cho request qua bình thường", t -> t.slate, null, false),
                new SegmentedToggle.Option("BẬT CHẶN", "Chặn đứng request khớp phạm vi & đợi thao tác", t -> t.danger, null, false));
        masterToggle.setFixedHeight(26);
        masterToggle.setSelectedIndex(ae.isInterceptWaitEnabled() ? 1 : 0);

        JCheckBox allScopeChk = new JCheckBox("Toàn bộ (mọi nhánh)", ae.isInterceptWaitAllScope());
        allScopeChk.setOpaque(false);
        allScopeChk.setToolTipText("Chặn tất cả request khớp các HTTP Method bên cạnh trên mọi đường dẫn / nhánh");

        Map<String, JToggleButton> methodBtns = new LinkedHashMap<>();
        JPanel methodRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        methodRow.setOpaque(false);
        Set<String> initMethods = ae.getInterceptWaitMethods();
        for (String m : ActionInterceptRule.ALL_METHODS) {
            JToggleButton tb = new JToggleButton(m, initMethods.contains(m));
            tb.setFocusPainted(false);
            tb.setFont(t0.smallFont);
            tb.setMargin(new Insets(1, 5, 1, 5));
            tb.setPreferredSize(new Dimension(m.length() >= 7 ? 62 : (m.length() >= 5 ? 52 : 42), 24));
            styleMethodChip(tb);
            methodBtns.put(m, tb);
            methodRow.add(tb);
        }

        JCheckBox autoOpenChk = new JCheckBox("Tự mở khi có request chặn", queue.isAutoOpenWindow());
        autoOpenChk.setOpaque(false);
        autoOpenChk.addActionListener(e -> queue.setAutoOpenWindow(autoOpenChk.isSelected()));

        JPanel row1 = new JPanel(new BorderLayout(8, 0));
        row1.setOpaque(false);
        row1.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Trạng thái:"), masterToggle, allScopeChk, methodRow), BorderLayout.WEST);
        row1.add(autoOpenChk, BorderLayout.EAST);

        // Row 2: Quick Pin buttons + Open Rules + Active Pinned Chips
        JPanel pinnedChipsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        pinnedChipsRow.setOpaque(false);

        JButton pinBranchBtn = Ui.button("+ Chặn nhánh hiện tại", t -> Icons.branch(t.accent.text()), null);
        JButton pinApiBtn = Ui.button("+ Chặn API đang chọn", t -> Icons.api(t.warning.text()), null);
        JButton rulesBtn = Ui.button("Luật Chặn nâng cao...", t -> Icons.sliders(t.fg), null);

        JPanel row2 = new JPanel(new BorderLayout(8, 0));
        row2.setOpaque(false);
        row2.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        row2.add(Ui.flow(FlowLayout.LEFT, pinBranchBtn, pinApiBtn, rulesBtn), BorderLayout.WEST);
        row2.add(pinnedChipsRow, BorderLayout.CENTER);

        controlsCard.add(row1);
        controlsCard.add(row2);

        JPanel topWrap = new JPanel(new BorderLayout());
        topWrap.setOpaque(false);
        topWrap.add(header, BorderLayout.NORTH);
        topWrap.add(controlsCard, BorderLayout.SOUTH);
        root.add(topWrap, BorderLayout.NORTH);

        // ------------------------------------------------------------------
        // 3. CENTER SPLIT: PENDING QUEUE TABLE (LEFT) + EDITABLE RAW HTTP (RIGHT)
        // ------------------------------------------------------------------
        DefaultTableModel tm = new DefaultTableModel(
                new Object[]{"#", "Method", "Đường dẫn", "Lý do chặn", "Lúc"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        ThemedTable queueTable = new ThemedTable(tm);
        Ui.style(queueTable);
        queueTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        queueTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        queueTable.setRowHeight(Theme.current().scaled(28));
        queueTable.getColumnModel().getColumn(0).setPreferredWidth(36);
        queueTable.getColumnModel().getColumn(0).setMaxWidth(48);
        queueTable.getColumnModel().getColumn(1).setPreferredWidth(68);
        queueTable.getColumnModel().getColumn(1).setMinWidth(64);
        queueTable.getColumnModel().getColumn(1).setMaxWidth(80);
        queueTable.getColumnModel().getColumn(2).setPreferredWidth(175);
        queueTable.getColumnModel().getColumn(3).setPreferredWidth(135);
        queueTable.getColumnModel().getColumn(4).setPreferredWidth(78);
        queueTable.getColumnModel().getColumn(4).setMinWidth(74);
        queueTable.getColumnModel().getColumn(4).setMaxWidth(88);

        JPanel emptyHintBar = new JPanel(new BorderLayout());
        emptyHintBar.setOpaque(true);
        emptyHintBar.setBackground(t0.info.fill());
        emptyHintBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, t0.info.border()),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        WrappedText emptyHintText = WrappedText.body(
                "Chưa có request nào đang đợi. Bật công tắc BẬT CHẶN ở trên (hoặc ghim nhánh/API cần chặn), sau đó bấm nút trên trình duyệt (VD: Save) — request sẽ dừng ngay tại đây đợi bạn bấm Gửi lên Server.",
                420);
        emptyHintBar.add(emptyHintText, BorderLayout.CENTER);

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setOpaque(true);
        leftPanel.setBackground(t0.surface);
        leftPanel.setBorder(BorderFactory.createLineBorder(t0.border));
        JScrollPane tableScroll = new JScrollPane(queueTable);
        tableScroll.setColumnHeaderView(queueTable.getTableHeader());
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        leftPanel.add(tableScroll, BorderLayout.CENTER);
        leftPanel.add(emptyHintBar, BorderLayout.SOUTH);

        // Right: editable raw HTTP request viewer
        JLabel reqMetaLbl = Ui.bold("Chưa chọn request nào trong hàng chờ");
        JLabel reqSubLbl = Ui.muted("Chọn 1 dòng bên trái để xem hoặc chỉnh sửa header/body trước khi bấm Gửi lên Server.");
        JButton resetRawBtn = Ui.button("Khôi phục gốc", Icons::undo, null);
        resetRawBtn.setEnabled(false);

        JPanel rightMeta = new JPanel(new BorderLayout(8, 2));
        rightMeta.setOpaque(true);
        rightMeta.setBackground(t0.surfaceAlt);
        rightMeta.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, t0.border),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JPanel metaTexts = new JPanel(new BorderLayout(0, 2));
        metaTexts.setOpaque(false);
        metaTexts.add(reqMetaLbl, BorderLayout.NORTH);
        metaTexts.add(reqSubLbl, BorderLayout.SOUTH);
        rightMeta.add(metaTexts, BorderLayout.CENTER);
        rightMeta.add(resetRawBtn, BorderLayout.EAST);

        JTextArea requestEditor = new JTextArea();
        requestEditor.setFont(t0.monoFont);
        requestEditor.setBackground(t0.surface);
        requestEditor.setForeground(t0.fg);
        requestEditor.setCaretColor(t0.fg);
        requestEditor.setLineWrap(false);
        requestEditor.setMargin(new Insets(8, 10, 8, 10));
        requestEditor.setEditable(false);

        JScrollPane editorScroll = new JScrollPane(requestEditor);
        editorScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setOpaque(true);
        rightPanel.setBackground(t0.surface);
        rightPanel.setBorder(BorderFactory.createLineBorder(t0.border));
        rightPanel.add(rightMeta, BorderLayout.NORTH);
        rightPanel.add(editorScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setResizeWeight(0.48);
        split.setDividerLocation(495);
        split.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
        root.add(split, BorderLayout.CENTER);

        // ------------------------------------------------------------------
        // 4. BOTTOM ACTION BAR
        // ------------------------------------------------------------------
        final List<PendingRequest> displayed = new ArrayList<>();
        final long[] selectedId = {-1L};
        final Map<Long, String> draftEdits = new LinkedHashMap<>();

        JButton forwardBtn = Ui.accent("Gửi lên Server (Forward)",
                t -> Icons.send(t.accent.onSolid()), t -> t.accent, null);
        JButton holdSaveBtn = Ui.button("Lưu vào bảng & Giữ (Không đẩy Server)",
                t -> Icons.strategyShield(t.warning.text(), 14), null);
        JButton dropBtn = Ui.button("Hủy bỏ (Drop)",
                t -> Icons.stop(t.danger.text()), null);

        JButton forwardAllBtn = Ui.button("Gửi tất cả", t -> Icons.sendAll(t.success.text()), null);
        JButton dropAllBtn = Ui.button("Hủy tất cả", t -> Icons.trash(t.danger.text()), null);
        JButton closeBtn = Ui.button("Đóng", onClose != null ? onClose : () -> { });

        JPanel bottomBar = new JPanel(new BorderLayout(8, 0));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
        bottomBar.add(Ui.flow(FlowLayout.LEFT, forwardBtn, holdSaveBtn, dropBtn), BorderLayout.WEST);
        bottomBar.add(Ui.flow(FlowLayout.RIGHT, forwardAllBtn, dropAllBtn, closeBtn), BorderLayout.EAST);
        root.add(bottomBar, BorderLayout.SOUTH);

        // ------------------------------------------------------------------
        // 5. STATE SYNC & ACTIONS
        // ------------------------------------------------------------------
        Runnable syncSelectionEditor = () -> {
            int row = queueTable.getSelectedRow();
            if (row < 0 || row >= displayed.size()) {
                selectedId[0] = -1L;
                reqMetaLbl.setText("Chưa chọn request nào trong hàng chờ");
                reqSubLbl.setText("Chọn 1 dòng bên trái để xem hoặc chỉnh sửa header/body trước khi bấm Gửi lên Server.");
                requestEditor.setText("");
                requestEditor.setEditable(false);
                resetRawBtn.setEnabled(false);
                forwardBtn.setEnabled(false);
                holdSaveBtn.setEnabled(false);
                dropBtn.setEnabled(false);
                return;
            }
            PendingRequest pr = displayed.get(row);
            if (selectedId[0] != pr.id()) {
                selectedId[0] = pr.id();
                String draft = draftEdits.get(pr.id());
                String rawText = draft != null ? draft : new String(pr.originalRequest(), StandardCharsets.ISO_8859_1);
                requestEditor.setText(rawText);
                requestEditor.setCaretPosition(0);
            }
            requestEditor.setEditable(true);
            String hostLbl = pr.service() != null ? pr.service().protocol() + "://" + pr.service().hostLabel() : "";
            reqMetaLbl.setText("#" + pr.id() + "  " + pr.method() + " " + pr.path());
            reqSubLbl.setText(hostLbl + "  ·  Nhánh đích: " + pr.branchName() + "  ·  " + pr.reason());
            resetRawBtn.setEnabled(true);
            forwardBtn.setEnabled(true);
            holdSaveBtn.setEnabled(true);
            dropBtn.setEnabled(true);
        };

        // Keep live edits per pending item ID so switching rows doesn't lose user tweaks
        requestEditor.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void saveDraft() {
                if (selectedId[0] > 0 && requestEditor.isEditable()) {
                    draftEdits.put(selectedId[0], requestEditor.getText());
                }
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { saveDraft(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { saveDraft(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { saveDraft(); }
        });

        final Runnable[] refreshHolder = new Runnable[1];
        Runnable refresh = () -> {
            syncingUi[0] = true;
            try {
                boolean on = ae.isInterceptWaitEnabled();
                masterToggle.setSelectedIndex(on ? 1 : 0);
                allScopeChk.setSelected(ae.isInterceptWaitAllScope());
                autoOpenChk.setSelected(queue.isAutoOpenWindow());

                Set<String> curMethods = ae.getInterceptWaitMethods();
                for (Map.Entry<String, JToggleButton> e : methodBtns.entrySet()) {
                    e.getValue().setSelected(curMethods.contains(e.getKey()));
                    styleMethodChip(e.getValue());
                }

                // Refresh pinned branches & APIs chips
                pinnedChipsRow.removeAll();
                for (String b : ae.getInterceptWaitBranches()) {
                    pinnedChipsRow.add(makeRemovableChip("Nhánh: " + Text.shortPath(b, 26), t -> t.accent, () -> {
                        engine.toggleBranchInterceptWait(b, false);
                        if (refreshHolder[0] != null) refreshHolder[0].run();
                        if (ui != null) ui.syncHeader();
                    }));
                }
                for (String ep : ae.getInterceptWaitEndpoints()) {
                    pinnedChipsRow.add(makeRemovableChip("API: " + Text.shortPath(ep, 28), t -> t.warning, () -> {
                        int sp = ep.indexOf(' ');
                        if (sp > 0) {
                            ae.setEndpointInterceptWait(ep.substring(0, sp), ep.substring(sp + 1), false);
                        }
                        if (refreshHolder[0] != null) refreshHolder[0].run();
                        if (ui != null) ui.syncHeader();
                    }));
                }
                if (ae.getInterceptWaitBranches().isEmpty() && ae.getInterceptWaitEndpoints().isEmpty()) {
                    pinnedChipsRow.add(Ui.muted(ae.isInterceptWaitAllScope()
                            ? "Đang áp dụng: Toàn bộ nhánh theo các Method đã chọn"
                            : "Chưa ghim nhánh/API nào — hãy bấm '+ Chặn nhánh hiện tại' hoặc tick 'Toàn bộ'"));
                }
                pinnedChipsRow.revalidate();
                pinnedChipsRow.repaint();

                ApiGroup curApi = (ui != null && ui.api != null) ? ui.api.currentGroup() : null;
                pinApiBtn.setEnabled(curApi != null);
                pinApiBtn.setToolTipText(curApi != null
                        ? "Ghim chặn chờ duyệt cho " + curApi.method() + " " + ActionEngine.normalizePathTemplate(curApi.path())
                        : "Chọn 1 API trong bảng để ghim chặn chờ duyệt riêng cho API đó");

                // Refresh queue table
                List<PendingRequest> snap = queue.pending();
                long prevId = selectedId[0];
                displayed.clear();
                displayed.addAll(snap);
                draftEdits.keySet().removeIf(id -> snap.stream().noneMatch(p -> p.id() == id));

                tm.setRowCount(0);
                int selRow = -1;
                for (int i = 0; i < displayed.size(); i++) {
                    PendingRequest pr = displayed.get(i);
                    tm.addRow(new Object[]{
                            "#" + pr.id(),
                            pr.method(),
                            pr.path(),
                            pr.reason(),
                            pr.timeHms()
                    });
                    if (pr.id() == prevId) {
                        selRow = i;
                    }
                }
                if (selRow < 0 && !displayed.isEmpty()) {
                    selRow = 0;
                }
                if (selRow >= 0) {
                    queueTable.setRowSelectionInterval(selRow, selRow);
                } else {
                    queueTable.clearSelection();
                }

                int count = displayed.size();
                emptyHintBar.setVisible(count == 0);
                forwardAllBtn.setEnabled(count > 0);
                dropAllBtn.setEnabled(count > 0);
                if (count > 0) {
                    liveStatusPill.setText("Đang chờ duyệt: " + count);
                    liveStatusPill.setToken(t -> t.danger);
                    liveStatusPill.setIcon(t -> Icons.strategyShield(t.danger.text(), 12));
                } else if (on) {
                    liveStatusPill.setText("Đang BẬT (0 chờ)");
                    liveStatusPill.setToken(t -> t.success);
                    liveStatusPill.setIcon(t -> Icons.strategyShield(t.success.text(), 12));
                } else {
                    liveStatusPill.setText("Đang TẮT");
                    liveStatusPill.setToken(t -> t.neutral);
                    liveStatusPill.setIcon(t -> Icons.strategyShield(t.muted, 12));
                }
            } finally {
                syncingUi[0] = false;
            }
            syncSelectionEditor.run();
        };
        refreshHolder[0] = refresh;

        queueTable.getSelectionModel().addListSelectionListener(ev -> {
            if (!ev.getValueIsAdjusting() && !syncingUi[0]) {
                syncSelectionEditor.run();
            }
        });

        masterToggle.onChange(idx -> {
            if (syncingUi[0]) {
                return;
            }
            engine.setInterceptWaitEnabled(idx == 1);
            refresh.run();
            if (ui != null) ui.syncHeader();
        });

        allScopeChk.addActionListener(e -> {
            if (syncingUi[0]) {
                return;
            }
            ae.setInterceptWaitAllScope(allScopeChk.isSelected());
            refresh.run();
            if (ui != null) ui.syncHeader();
        });

        for (Map.Entry<String, JToggleButton> e : methodBtns.entrySet()) {
            String method = e.getKey();
            JToggleButton tb = e.getValue();
            tb.addActionListener(ev -> {
                if (syncingUi[0]) {
                    return;
                }
                ae.toggleInterceptWaitMethod(method, tb.isSelected());
                styleMethodChip(tb);
                if (ui != null) ui.syncHeader();
            });
        }

        pinBranchBtn.addActionListener(e -> {
            String curBranch = engine.activeBranch().name();
            engine.toggleBranchInterceptWait(curBranch, true);
            refresh.run();
            if (ui != null) {
                ui.syncHeader();
                ui.flash("Đã bật Chặn chờ duyệt cho nhánh: " + curBranch);
            }
        });

        pinApiBtn.addActionListener(e -> {
            ApiGroup curApi = (ui != null && ui.api != null) ? ui.api.currentGroup() : null;
            if (curApi == null) {
                return;
            }
            engine.toggleEndpointInterceptWait(curApi, true);
            refresh.run();
            if (ui != null) {
                ui.syncHeader();
                ui.flash("Đã bật Chặn chờ duyệt cho API: " + curApi.method() + " "
                        + ActionEngine.normalizePathTemplate(curApi.path()));
            }
        });

        rulesBtn.addActionListener(e -> {
            ApiGroup curApi = (ui != null && ui.api != null) ? ui.api.currentGroup() : null;
            ActionWorkbenchDialog.show(root, engine, curApi, null, ActionWorkbenchDialog.TAB_INTERCEPT_RULES, () -> {
                refresh.run();
                if (ui != null) ui.syncHeader();
            });
        });

        resetRawBtn.addActionListener(e -> {
            int row = queueTable.getSelectedRow();
            if (row >= 0 && row < displayed.size()) {
                PendingRequest pr = displayed.get(row);
                draftEdits.remove(pr.id());
                requestEditor.setText(new String(pr.originalRequest(), StandardCharsets.ISO_8859_1));
                requestEditor.setCaretPosition(0);
            }
        });

        Runnable resolveSelectedForward = () -> resolveSelected(queue, displayed, queueTable, requestEditor, draftEdits,
                ResolutionKind.FORWARD, ui, refresh);
        Runnable resolveSelectedHold = () -> resolveSelected(queue, displayed, queueTable, requestEditor, draftEdits,
                ResolutionKind.HOLD_AND_SPOOF, ui, refresh);
        Runnable resolveSelectedDrop = () -> resolveSelected(queue, displayed, queueTable, requestEditor, draftEdits,
                ResolutionKind.DROP, ui, refresh);

        forwardBtn.addActionListener(e -> resolveSelectedForward.run());
        holdSaveBtn.addActionListener(e -> resolveSelectedHold.run());
        dropBtn.addActionListener(e -> resolveSelectedDrop.run());

        forwardAllBtn.addActionListener(e -> {
            int n = queue.releaseAll(ResolutionKind.FORWARD);
            draftEdits.clear();
            refresh.run();
            if (ui != null) {
                ui.syncHeader();
                if (n > 0) ui.flash("Đã gửi " + n + " request lên Server.");
            }
        });
        dropAllBtn.addActionListener(e -> {
            int n = queue.releaseAll(ResolutionKind.DROP);
            draftEdits.clear();
            refresh.run();
            if (ui != null) {
                ui.syncHeader();
                if (n > 0) ui.flash("Đã hủy " + n + " request trong hàng chờ.");
            }
        });

        Runnable queueListener = () -> SwingUtilities.invokeLater(refresh);
        queue.addListener(queueListener);

        refresh.run();
        return new View(root, queueTable, requestEditor, pinBtn, refresh, () -> queue.removeListener(queueListener));
    }

    private static void resolveSelected(
            ProxyInterceptQueue queue,
            List<PendingRequest> displayed,
            JTable table,
            JTextArea editor,
            Map<Long, String> draftEdits,
            ResolutionKind kind,
            MainPanel ui,
            Runnable refresh) {
        int row = table.getSelectedRow();
        if (row < 0 || row >= displayed.size()) {
            return;
        }
        PendingRequest pr = displayed.get(row);
        byte[] payload = pr.originalRequest();
        if (draftEdits.containsKey(pr.id()) || kind == ResolutionKind.FORWARD) {
            String edited = editor.getText();
            if (edited != null && !edited.isBlank()) {
                payload = normalizeRawHttpRequest(edited);
            }
        }
        draftEdits.remove(pr.id());
        queue.resolve(pr.id(), kind, payload);
        refresh.run();
        if (ui != null) {
            ui.syncHeader();
        }
    }

    /**
     * Normalizes edited HTTP text back to wire-safe CRLF bytes and recalculates {@code Content-Length}
     * when present so edited JSON/form bodies forward cleanly to the Server.
     */
    static byte[] normalizeRawHttpRequest(String edited) {
        if (edited == null) {
            return new byte[0];
        }
        String unified = edited.replace("\r\n", "\n").replace("\r", "\n");
        int splitIdx = unified.indexOf("\n\n");
        if (splitIdx < 0) {
            return unified.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        }
        String headers = unified.substring(0, splitIdx);
        String body = unified.substring(splitIdx + 2);
        byte[] bodyBytes = body.getBytes(StandardCharsets.ISO_8859_1);

        Matcher m = CONTENT_LENGTH_HEADER.matcher(headers);
        if (m.find()) {
            headers = m.replaceFirst("Content-Length: " + bodyBytes.length);
        }
        String wireHead = headers.replace("\n", "\r\n") + "\r\n\r\n";
        byte[] headBytes = wireHead.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[headBytes.length + bodyBytes.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(bodyBytes, 0, out, headBytes.length, bodyBytes.length);
        return out;
    }

    private static void styleMethodChip(JToggleButton tb) {
        Theme t = Theme.current();
        boolean sel = tb.isSelected();
        Theme.Token tok = ActionEngine.isActionMethod(tb.getText()) ? t.danger : t.info;
        tb.setOpaque(true);
        tb.setBackground(sel ? tok.fill() : t.surface);
        tb.setForeground(sel ? tok.text() : t.muted);
        tb.setBorder(BorderFactory.createLineBorder(sel ? tok.border() : t.border));
    }

    private static JPanel makeRemovableChip(String text, java.util.function.Function<Theme, Theme.Token> tokenFn, Runnable onRemove) {
        Theme t = Theme.current();
        Theme.Token tok = tokenFn.apply(t);
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        chip.setOpaque(true);
        chip.setBackground(tok.fill());
        chip.setBorder(BorderFactory.createLineBorder(tok.border()));

        JLabel lbl = new JLabel(text);
        lbl.setFont(t.smallBoldFont);
        lbl.setForeground(tok.text());

        JButton rm = new JButton("x");
        rm.setFont(t.smallBoldFont);
        rm.setForeground(tok.text());
        rm.setOpaque(false);
        rm.setContentAreaFilled(false);
        rm.setBorderPainted(false);
        rm.setFocusPainted(false);
        rm.setMargin(new Insets(0, 2, 0, 2));
        rm.setToolTipText("Gỡ khỏi danh sách chặn chờ duyệt");
        rm.addActionListener(e -> onRemove.run());

        chip.add(lbl);
        chip.add(rm);
        return chip;
    }
}
