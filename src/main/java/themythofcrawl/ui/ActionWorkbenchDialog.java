package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
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
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ActionEngine.ActionMode;
import themythofcrawl.core.ActionEngine.Lock2xxStrategy;
import themythofcrawl.core.ActionEngine.SuggestedPath;
import themythofcrawl.core.ActionEngine.SwapPreview;
import themythofcrawl.core.Config;
import themythofcrawl.core.Text;
import themythofcrawl.model.ActionInterceptRule;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;
import themythofcrawl.model.RoleResult;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Visual Action API Workbench v2.2 with 5 clear tabs:
 * <ol>
 *   <li>Chiến lược xử lý (4 sharp cards + Smart Auto + Proxy Hold toggles)</li>
 *   <li>Luật Chặn Proxy (Pre-crawl Branch/Path + HTTP Method matrix rules + Live Dry-Run Tester)</li>
 *   <li>Kho ID Sống (Live ID Pool table, 1-click swap &amp; send, bulk paste, CSV import/export)</li>
 *   <li>Chuỗi Tạo Mới -&gt; Hành Động (Producer Chain Builder with auto-suggested JSONPath &amp; live request diff)</li>
 *   <li>Khóa 200 OK &amp; So sánh Gửi lại (side-by-side 200 OK snapshot vs latest response + 3 clear buttons)</li>
 * </ol>
 */
public final class ActionWorkbenchDialog {
    public static final int TAB_STRATEGY = 0;
    public static final int TAB_INTERCEPT_RULES = 1;
    public static final int TAB_POOL = 2;
    public static final int TAB_CHAIN = 3;
    public static final int TAB_LOCK200 = 4;

    private ActionWorkbenchDialog() {
    }

    public static void show(Component parent, Engine engine, ApiGroup focusGroup,
                            Collection<RoleResult> explicitTargets, int initialTab, Runnable onAfterAction) {
        showInternal(parent, engine, focusGroup, explicitTargets, null, initialTab, onAfterAction);
    }

    public static void showForBranch(Component parent, Engine engine, String prefillBranch, Runnable onAfterAction) {
        showInternal(parent, engine, null, null, prefillBranch, TAB_INTERCEPT_RULES, onAfterAction);
    }

    private static void showInternal(Component parent, Engine engine, ApiGroup focusGroup,
                                     Collection<RoleResult> explicitTargets, String prefillBranch,
                                     int initialTab, Runnable onAfterAction) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        String title = focusGroup != null
                ? "Action Workbench — " + Text.apiLabel(focusGroup.gid()) + " " + focusGroup.method() + " " + Text.shortPath(focusGroup.path(), 44)
                : (prefillBranch != null && !prefillBranch.isBlank()
                        ? "Action Workbench — Luật Chặn Proxy cho " + prefillBranch
                        : "Action Workbench — Bảo vệ & Tái tạo Action API");
        JDialog dlg = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);

        JPanel content = buildWorkbenchPanel(dlg, engine, focusGroup, explicitTargets, prefillBranch, initialTab, () -> {
            if (onAfterAction != null) {
                onAfterAction.run();
            }
        }, dlg::dispose);

        dlg.setLayout(new BorderLayout());
        dlg.add(content, BorderLayout.CENTER);
        Theme.track(dlg);
        dlg.setMinimumSize(new Dimension(920, 600));
        dlg.setSize(new Dimension(1040, 690));
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    /** Builds the full 5-tab Workbench panel (also reusable for headless tests &amp; previews). */
    public static JPanel buildWorkbenchPanel(Component dialogParent, Engine engine, ApiGroup focusGroup,
                                             Collection<RoleResult> explicitTargets, int initialTab,
                                             Runnable onAfterAction, Runnable onClose) {
        return buildWorkbenchPanel(dialogParent, engine, focusGroup, explicitTargets, null, initialTab, onAfterAction, onClose);
    }

    public static JPanel buildWorkbenchPanel(Component dialogParent, Engine engine, ApiGroup focusGroup,
                                             Collection<RoleResult> explicitTargets, String prefillBranch, int initialTab,
                                             Runnable onAfterAction, Runnable onClose) {
        Collection<RoleResult> targets = focusGroup == null ? List.of()
                : ((explicitTargets == null || explicitTargets.isEmpty())
                        ? engine.defaultTargets(focusGroup, true) : explicitTargets);

        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.SPACE_2, 0));

        // Top header + live status bar
        JPanel topWrap = new JPanel(new BorderLayout());
        String headTitle = focusGroup != null
                ? "Action Workbench — " + Text.apiLabel(focusGroup.gid()) + " [" + focusGroup.method() + " " + focusGroup.path() + "]"
                : "Action Workbench — Cấu hình Bảo vệ & Luật Chặn Action API";
        String headSub = "Chặn chủ động theo Nhánh / Path / Method ngay tại Proxy, quản lý Kho ID sống và bảo vệ trạng thái 200 OK.";
        topWrap.add(Ui.dialogHeader(headTitle, headSub), BorderLayout.NORTH);

        JPanel statusStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        statusStrip.setOpaque(true);
        statusStrip.setBackground(Theme.current().surfaceAlt);
        statusStrip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.current().border));

        Pill idPill = new Pill("Chưa phát hiện ID", t -> t.neutral, t -> Icons.idPool(t.muted, 12));
        Pill snapPill = new Pill("Chưa có Snapshot 200 OK", t -> t.neutral, t -> Icons.strategySnapshot(t.muted, 12));
        Pill rulesPill = new Pill("Luật Chặn Proxy: 0", t -> t.neutral, t -> Icons.strategyShield(t.muted, 12));
        Pill modePill = new Pill("Chiến lược", t -> t.accent, t -> Icons.workbench(t.accent.text(), 12));
        Pill poolCountPill = new Pill("Kho ID: 0", t -> t.info, t -> Icons.idPool(t.info.text(), 12));
        statusStrip.add(idPill);
        statusStrip.add(snapPill);
        statusStrip.add(rulesPill);
        statusStrip.add(modePill);
        statusStrip.add(poolCountPill);
        topWrap.add(statusStrip, BorderLayout.SOUTH);
        root.add(topWrap, BorderLayout.NORTH);

        Runnable refreshBanner = () -> {
            ActionEngine ae = engine.actionEngine();
            int activeBlocks = ae.activeBlockRuleCount();
            int totalRules = ae.interceptRules().size();
            rulesPill.setText("Luật Chặn Proxy: " + activeBlocks + "/" + totalRules + " bật");
            rulesPill.setToken(activeBlocks > 0 ? t -> t.warning : t -> t.neutral);
            rulesPill.setIcon(t -> Icons.strategyShield(activeBlocks > 0 ? t.warning.text() : t.muted, 12));

            if (focusGroup != null) {
                List<String> ids = ae.detectIds(focusGroup.path(), Blob.bytesOf(focusGroup.origRequest()));
                String curId = ids.isEmpty() ? null : ids.get(0);
                if (curId != null) {
                    boolean consumed = ae.isConsumed(curId);
                    idPill.setText("ID: " + curId + (consumed ? " (Đã bị xóa / tiêu thụ)" : " (Đang sống)"));
                    idPill.setToken(consumed ? t -> t.danger : t -> t.success);
                    idPill.setIcon(t -> Icons.idPool(consumed ? t.danger.text() : t.success.text(), 12));
                } else {
                    idPill.setText("Không có ID tĩnh trên Path/Body");
                    idPill.setToken(t -> t.neutral);
                }
                if (focusGroup.hasFirstOkSnapshot()) {
                    snapPill.setText("Snapshot gốc: HTTP " + focusGroup.firstOkStatus() + " · " + focusGroup.firstOkLength() + "B");
                    snapPill.setToken(t -> t.success);
                    snapPill.setIcon(t -> Icons.strategySnapshot(t.success.text(), 12));
                } else {
                    snapPill.setText("Chưa có Snapshot 2xx gốc");
                    snapPill.setToken(t -> t.neutral);
                }
                ActionMode eff = focusGroup.actionModeOverride() != null ? focusGroup.actionModeOverride() : engine.config().actionMode;
                modePill.setText("Đang dùng: " + shortModeName(eff)
                        + (focusGroup.actionModeOverride() != null ? " (Riêng API này)" : " (Toàn cục)"));
            } else {
                idPill.setText("Phạm vi: Toàn bộ Extension");
                idPill.setToken(t -> t.info);
                snapPill.setText("Chặn Proxy toàn cục: " + (engine.config().holdActionAtProxy ? "Đang BẬT" : "Đang TẮT"));
                snapPill.setToken(engine.config().holdActionAtProxy ? t -> t.warning : t -> t.neutral);
                modePill.setText("Mặc định: " + shortModeName(engine.config().actionMode));
            }
            int alive = 0;
            for (List<String> list : ae.getAllResourceLiveIds().values()) {
                for (String id : list) {
                    if (!ae.isConsumed(id)) {
                        alive++;
                    }
                }
            }
            poolCountPill.setText("Kho ID: " + alive + " còn sống · " + ae.getConsumedIds().size() + " đã dùng");
        };

        JTabbedPane tabs = new JTabbedPane();
        tabs.putClientProperty("JTabbedPane.tabType", "card");
        tabs.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));

        tabs.addTab("1. Chiến lược xử lý", buildStrategyTab(engine, focusGroup, refreshBanner, onAfterAction));
        tabs.addTab("2. Luật Chặn Proxy", buildInterceptRulesTab(dialogParent, engine, focusGroup, prefillBranch, refreshBanner, onAfterAction));
        tabs.addTab("3. Kho ID Sống", buildPoolTab(dialogParent, engine, focusGroup, targets, refreshBanner, onAfterAction));
        tabs.addTab("4. Chuỗi Tạo Mới -> Hành Động", buildChainTab(dialogParent, engine, focusGroup, targets, refreshBanner, onAfterAction));
        tabs.addTab("5. Khóa 200 OK & Gửi lại", buildLock200Tab(engine, focusGroup, targets, refreshBanner, onAfterAction));

        if (initialTab >= 0 && initialTab < tabs.getTabCount()) {
            tabs.setSelectedIndex(initialTab);
        }
        root.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, 0, Theme.SPACE_3));
        footer.add(Ui.muted("Mọi thay đổi chiến lược, Luật Chặn Proxy và Kho ID được lưu và áp dụng ngay lập tức."), BorderLayout.WEST);
        if (onClose != null) {
            footer.add(Ui.button("Đóng", onClose), BorderLayout.EAST);
        }
        root.add(footer, BorderLayout.SOUTH);

        refreshBanner.run();
        return root;
    }

    // =========================================================================
    // TAB 1: 4 VISUAL STRATEGY CARDS
    // =========================================================================
    private static JPanel buildStrategyTab(Engine engine, ApiGroup focusGroup,
                                           Runnable refreshBanner, Runnable onAfterAction) {
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        p.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        final boolean[] perApiScope = {focusGroup != null};

        JPanel topRow = new JPanel(new BorderLayout(8, 0));
        topRow.setOpaque(false);
        String perApiLbl = focusGroup != null
                ? "Riêng API: " + focusGroup.method() + " " + Text.shortPath(focusGroup.path(), 22)
                : "Riêng API đang chọn";
        SegmentedToggle scopeToggle = new SegmentedToggle(
                new SegmentedToggle.Option(perApiLbl, "Ghi đè chiến lược chỉ cho API đang chọn", t -> t.accent, (t, s) -> null, false),
                new SegmentedToggle.Option("Mặc định toàn cục", "Áp dụng cho mọi Action API trong Extension", t -> t.info, (t, s) -> null, false));
        scopeToggle.setFixedHeight(26);
        scopeToggle.setSelectedIndex(perApiScope[0] ? 0 : 1);
        scopeToggle.setEnabled(focusGroup != null);

        JButton smartAutoBtn = Ui.accent("Bật chế độ Tự động thông minh (Kết hợp cả 4)",
                t -> Icons.workbench(t.accent.onSolid(), 14), t -> t.accent, () -> {
                    applyMode(engine, focusGroup, perApiScope[0], ActionMode.AUTO_SMART);
                    refreshBanner.run();
                    if (onAfterAction != null) onAfterAction.run();
                });

        topRow.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Phạm vi áp dụng:"), scopeToggle), BorderLayout.WEST);
        topRow.add(smartAutoBtn, BorderLayout.EAST);

        // Mode #5: Live Interactive Proxy Intercept Wait banner (pause & wait for manual Forward)
        Theme th = Theme.current();
        JPanel liveWaitBanner = new JPanel(new BorderLayout(10, 0));
        liveWaitBanner.setOpaque(true);
        liveWaitBanner.setBackground(th.danger.fill());
        liveWaitBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 4, 1, 1, th.danger.solidFill()),
                BorderFactory.createEmptyBorder(8, 12, 8, 10)));

        JPanel waitTextCol = new JPanel(new BorderLayout(0, 2));
        waitTextCol.setOpaque(false);
        JLabel waitTitle = new JLabel("5. Chặn chờ duyệt trực tiếp tại Proxy (Đợi tôi bấm Gửi lên Server)",
                Icons.strategyShield(th.danger.text(), 15), JLabel.LEFT);
        waitTitle.setFont(th.boldFont);
        waitTitle.setForeground(th.danger.text());
        waitTextCol.add(waitTitle, BorderLayout.NORTH);
        waitTextCol.add(themythofcrawl.ui.kit.WrappedText.body(
                "Chặn đứng request ngay khi bạn bấm nút trên UI (Save / Xóa / Cập nhật...) theo Toàn bộ, 1 Nhánh/Path, hoặc 1 API bất kỳ -> giữ tại Hàng chờ Chặn để bạn xem/sửa và bấm Gửi lên Server.",
                520), BorderLayout.CENTER);
        liveWaitBanner.add(waitTextCol, BorderLayout.CENTER);

        final boolean[] syncingBanner = {false};
        SegmentedToggle waitMasterToggle = new SegmentedToggle(
                new SegmentedToggle.Option("TẮT", "Tắt chặn chờ duyệt trực tiếp", t -> t.slate, null, false),
                new SegmentedToggle.Option("BẬT CHẶN", "Bật chặn đứng request tại Proxy và đợi thao tác", t -> t.danger, null, false));
        waitMasterToggle.setFixedHeight(26);
        waitMasterToggle.setSelectedIndex(engine.actionEngine().isInterceptWaitEnabled() ? 1 : 0);

        JButton openWaitQueueBtn = Ui.accent("Mở Hàng chờ Chặn...",
                t -> Icons.send(t.danger.onSolid(), 13), t -> t.danger, () -> MainPanel.openInterceptQueue(engine));
        JPanel waitRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        waitRight.setOpaque(false);
        waitRight.add(waitMasterToggle);
        waitRight.add(openWaitQueueBtn);
        liveWaitBanner.add(waitRight, BorderLayout.EAST);

        JPanel northWrap = new JPanel(new BorderLayout(0, 8));
        northWrap.setOpaque(false);
        northWrap.add(topRow, BorderLayout.NORTH);
        northWrap.add(liveWaitBanner, BorderLayout.SOUTH);
        p.add(northWrap, BorderLayout.NORTH);

        JPanel cardsGrid = new JPanel(new GridLayout(2, 2, 10, 10));
        cardsGrid.setOpaque(false);

        JCheckBox holdAllChk = new JCheckBox("Bật Chặn tại Proxy cho mọi Action API khi Crawl", engine.config().holdActionAtProxy);
        holdAllChk.setOpaque(false);
        JCheckBox holdEpChk = focusGroup == null ? null : new JCheckBox("Chỉ chặn tại Proxy cho endpoint này (" + focusGroup.method() + " "
                + ActionEngine.normalizePathTemplate(focusGroup.path()) + ")",
                engine.actionEngine().isEndpointProxyHold(focusGroup.method(), focusGroup.path()));
        if (holdEpChk != null) {
            holdEpChk.setOpaque(false);
        }

        List<StrategyCard> cards = new ArrayList<>();
        Runnable refreshCards = () -> {
            ActionMode active = activeMode(engine, focusGroup, perApiScope[0]);
            for (StrategyCard sc : cards) {
                sc.setSelected(sc.mode == active, active == ActionMode.AUTO_SMART);
            }
            holdAllChk.setSelected(engine.config().holdActionAtProxy);
            if (holdEpChk != null && focusGroup != null) {
                holdEpChk.setSelected(engine.actionEngine().isEndpointProxyHold(focusGroup.method(), focusGroup.path()));
            }
            syncingBanner[0] = true;
            try {
                waitMasterToggle.setSelectedIndex(engine.actionEngine().isInterceptWaitEnabled() ? 1 : 0);
            } finally {
                syncingBanner[0] = false;
            }
        };

        waitMasterToggle.onChange(idx -> {
            if (syncingBanner[0]) {
                return;
            }
            engine.setInterceptWaitEnabled(idx == 1);
            if (idx == 1 && perApiScope[0] && focusGroup != null) {
                engine.toggleEndpointInterceptWait(focusGroup, true);
            }
            refreshBanner.run();
            if (onAfterAction != null) onAfterAction.run();
        });

        cards.add(new StrategyCard(
                ActionMode.HOLD_AT_PROXY,
                "Giữ ở Proxy (Không xóa trên Server)",
                "Trình duyệt nhận 200 OK ảo (hoặc Drop) khi crawl; Server vẫn giữ nguyên bản ghi chưa bị xóa, để lúc bạn bấm Gửi trong Extension mới thực thi thật.",
                "Phù hợp nhất khi: bạn muốn lưu nháp vào bảng Extension mà không làm thay đổi Server ngay.",
                t -> t.warning,
                t -> Icons.strategyShield(t.warning.text(), 18),
                () -> {
                    applyMode(engine, focusGroup, perApiScope[0], ActionMode.HOLD_AT_PROXY);
                    refreshCards.run();
                    refreshBanner.run();
                    if (onAfterAction != null) onAfterAction.run();
                }));

        cards.add(new StrategyCard(
                ActionMode.LOCK_2XX,
                "Khóa kết quả 200 OK (Snapshot)",
                "Tự chụp lại response 200 OK lúc crawl. Khi gửi lại, bạn tự chọn giữa giữ 200 OK gốc, đổi sang ID mới để đẩy thật, hoặc gửi cưỡng bức.",
                "Phù hợp nhất khi: bạn muốn đảm bảo báo cáo & bằng chứng luôn giữ trạng thái 200 OK.",
                t -> t.info,
                t -> Icons.strategySnapshot(t.info.text(), 18),
                () -> {
                    applyMode(engine, focusGroup, perApiScope[0], ActionMode.LOCK_2XX);
                    refreshCards.run();
                    refreshBanner.run();
                    if (onAfterAction != null) onAfterAction.run();
                }));

        cards.add(new StrategyCard(
                ActionMode.LIVE_ID_POOL,
                "Tự đổi từ Kho ID Sống",
                "Extension tự động gom các ID còn sống từ các API GET danh sách / chi tiết, sau đó thay ID tươi vào URL/Body thay cho ID đã xóa.",
                "Phù hợp nhất khi: hệ thống có sẵn nhiều bản ghi (ví dụ đơn hàng 101, 102, 103).",
                t -> t.success,
                t -> Icons.strategySwap(t.success.text(), 18),
                () -> {
                    applyMode(engine, focusGroup, perApiScope[0], ActionMode.LIVE_ID_POOL);
                    refreshCards.run();
                    refreshBanner.run();
                    if (onAfterAction != null) onAfterAction.run();
                }));

        cards.add(new StrategyCard(
                ActionMode.PRODUCER_RECREATE,
                "Chuỗi Tạo Mới -> Hành Động",
                "Trước khi gửi DELETE/PUT, Extension tự động gọi API POST tạo bản ghi mới, rút ID vừa sinh ra từ Response và điền vào request này.",
                "Phù hợp nhất khi: bạn cần gửi đi gửi lại nhiều lần mà không sợ cạn ID.",
                t -> t.accent,
                t -> Icons.strategyChain(t.accent.text(), 18),
                () -> {
                    applyMode(engine, focusGroup, perApiScope[0], ActionMode.PRODUCER_RECREATE);
                    refreshCards.run();
                    refreshBanner.run();
                    if (onAfterAction != null) onAfterAction.run();
                }));

        for (StrategyCard sc : cards) {
            cardsGrid.add(sc);
        }
        p.add(cardsGrid, BorderLayout.CENTER);

        scopeToggle.onChange(idx -> {
            perApiScope[0] = (idx == 0 && focusGroup != null);
            refreshCards.run();
        });
        smartAutoBtn.addActionListener(e -> refreshCards.run());

        // Bottom quick toggles
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        bottom.setOpaque(true);
        bottom.setBackground(Theme.current().surfaceAlt);
        bottom.setBorder(BorderFactory.createLineBorder(Theme.current().border, 1));

        holdAllChk.addActionListener(e -> {
            Config next = engine.config().copy();
            next.holdActionAtProxy = holdAllChk.isSelected();
            engine.applyConfig(next);
            refreshBanner.run();
            if (onAfterAction != null) onAfterAction.run();
        });
        bottom.add(holdAllChk);

        if (holdEpChk != null && focusGroup != null) {
            holdEpChk.addActionListener(e -> {
                engine.toggleEndpointProxyHold(focusGroup, holdEpChk.isSelected());
                refreshBanner.run();
                if (onAfterAction != null) onAfterAction.run();
            });
            bottom.add(holdEpChk);
        }

        p.add(bottom, BorderLayout.SOUTH);
        refreshCards.run();
        return p;
    }

    private static ActionMode activeMode(Engine engine, ApiGroup g, boolean perApi) {
        if (perApi && g != null && g.actionModeOverride() != null) {
            return g.actionModeOverride();
        }
        return engine.config().actionMode;
    }

    private static void applyMode(Engine engine, ApiGroup g, boolean perApi, ActionMode mode) {
        if (perApi && g != null) {
            engine.setApiActionMode(g, mode);
        } else {
            Config next = engine.config().copy();
            next.actionMode = mode;
            if (mode == ActionMode.HOLD_AT_PROXY) {
                next.holdActionAtProxy = true;
            }
            engine.applyConfig(next);
            if (mode == ActionMode.INTERCEPT_WAIT) {
                engine.setInterceptWaitEnabled(true);
            }
        }
    }

    // =========================================================================
    // TAB 2: LIVE ID POOL MANAGER
    // =========================================================================
    private static JPanel buildPoolTab(Component parent, Engine engine, ApiGroup focusGroup,
                                       Collection<RoleResult> targets, Runnable refreshBanner, Runnable onAfterAction) {
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        p.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        String defaultRes = focusGroup != null ? ActionEngine.extractResourceKey(focusGroup.path()) : "orders";
        String currentId = focusGroup != null
                ? engine.actionEngine().detectIds(focusGroup.path(), Blob.bytesOf(focusGroup.origRequest())).stream().findFirst().orElse(null)
                : null;

        JComboBox<String> resBox = new JComboBox<>();
        resBox.setEditable(true);
        JCheckBox autoHarvestChk = new JCheckBox("Tự thu thập ID sống từ API 2xx", engine.actionEngine().isAutoHarvest());
        autoHarvestChk.setOpaque(false);
        autoHarvestChk.addActionListener(e -> engine.actionEngine().setAutoHarvest(autoHarvestChk.isSelected()));

        DefaultTableModel tm = new DefaultTableModel(new Object[] {"Tài nguyên", "Giá trị ID", "Trạng thái"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable poolTable = new ThemedTable(tm);
        Ui.style(poolTable);
        poolTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        Runnable reloadPool = () -> {
            Object prevSel = resBox.getSelectedItem();
            Set<String> resNames = new LinkedHashSet<>();
            resNames.add("(Tất cả tài nguyên)");
            resNames.add(defaultRes);
            resNames.addAll(engine.actionEngine().getAllResourceLiveIds().keySet());
            resBox.removeAllItems();
            for (String rn : resNames) {
                resBox.addItem(rn);
            }
            if (prevSel != null && resNames.contains(prevSel.toString())) {
                resBox.setSelectedItem(prevSel);
            } else {
                resBox.setSelectedIndex(0);
            }

            tm.setRowCount(0);
            String filterRes = resBox.getSelectedItem() == null ? "(Tất cả tài nguyên)" : resBox.getSelectedItem().toString();
            boolean showAll = "(Tất cả tài nguyên)".equals(filterRes);
            for (Map.Entry<String, List<String>> e : engine.actionEngine().getAllResourceLiveIds().entrySet()) {
                if (!showAll && !e.getKey().equalsIgnoreCase(filterRes)) {
                    continue;
                }
                for (String id : e.getValue()) {
                    boolean consumed = engine.actionEngine().isConsumed(id);
                    tm.addRow(new Object[] {e.getKey(), id, consumed ? "Đã tiêu thụ (4xx)" : "Còn sống (Sẵn sàng)"});
                }
            }
            refreshBanner.run();
        };

        resBox.addActionListener(e -> {
            if (resBox.getItemCount() == 0) {
                return;
            }
            tm.setRowCount(0);
            String filterRes = resBox.getSelectedItem() == null ? "(Tất cả tài nguyên)" : resBox.getSelectedItem().toString();
            boolean showAll = "(Tất cả tài nguyên)".equals(filterRes);
            for (Map.Entry<String, List<String>> en : engine.actionEngine().getAllResourceLiveIds().entrySet()) {
                if (!showAll && !en.getKey().equalsIgnoreCase(filterRes)) {
                    continue;
                }
                for (String id : en.getValue()) {
                    boolean consumed = engine.actionEngine().isConsumed(id);
                    tm.addRow(new Object[] {en.getKey(), id, consumed ? "Đã tiêu thụ (4xx)" : "Còn sống (Sẵn sàng)"});
                }
            }
        });

        JButton useAndSendBtn = Ui.accent("Dùng ID đang chọn & Gửi ngay",
                t -> Icons.send(t.accent.onSolid()), t -> t.accent, () -> {
                    int vr = poolTable.getSelectedRow();
                    if (focusGroup == null || vr < 0) {
                        return;
                    }
                    String chosenId = String.valueOf(tm.getValueAt(poolTable.convertRowIndexToModel(vr), 1));
                    engine.replaceIdAndSend(focusGroup, currentId, chosenId, true, targets);
                    reloadPool.run();
                    if (onAfterAction != null) onAfterAction.run();
                });
        useAndSendBtn.setEnabled(focusGroup != null);

        JButton restoreBtn = Ui.button("Khôi phục còn sống", Icons::refresh, () -> {
            int vr = poolTable.getSelectedRow();
            if (vr < 0) {
                return;
            }
            String id = String.valueOf(tm.getValueAt(poolTable.convertRowIndexToModel(vr), 1));
            engine.actionEngine().restoreConsumed(id);
            reloadPool.run();
        });

        JButton removeBtn = Ui.button("Xóa ID", t -> Icons.trash(t.danger.text()), () -> {
            int vr = poolTable.getSelectedRow();
            if (vr < 0) {
                return;
            }
            int mr = poolTable.convertRowIndexToModel(vr);
            String res = String.valueOf(tm.getValueAt(mr, 0));
            String id = String.valueOf(tm.getValueAt(mr, 1));
            engine.actionEngine().removeLiveId(res, id);
            reloadPool.run();
        });

        JButton importCsvBtn = Ui.button("Nhập CSV...", Icons::upload, () -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("CSV (*.csv)", "csv"));
            if (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                try {
                    String csv = Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8);
                    int n = engine.actionEngine().importPoolCsv(csv);
                    reloadPool.run();
                    JOptionPane.showMessageDialog(parent, "Đã nạp " + n + " ID từ file CSV.", Text.EXT_NAME, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parent, "Không đọc được file CSV: " + ex.getMessage(), Text.EXT_NAME, JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton exportCsvBtn = Ui.button("Xuất CSV...", Icons::download, () -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("tmoc-live-id-pool.csv"));
            fc.setFileFilter(new FileNameExtensionFilter("CSV (*.csv)", "csv"));
            if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                try {
                    Files.writeString(fc.getSelectedFile().toPath(), engine.actionEngine().exportPoolCsv(), StandardCharsets.UTF_8);
                    JOptionPane.showMessageDialog(parent, "Đã xuất Kho ID Sống ra CSV.", Text.EXT_NAME, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parent, "Không xuất được file CSV: " + ex.getMessage(), Text.EXT_NAME, JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Tài nguyên:"), resBox, autoHarvestChk), BorderLayout.WEST);
        top.add(Ui.flow(FlowLayout.RIGHT, importCsvBtn, exportCsvBtn), BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);

        p.add(MainPanel.scroll(poolTable), BorderLayout.CENTER);

        // Bottom: bulk add + row action buttons
        JTextField bulkRes = new JTextField(defaultRes, 10);
        JTextField bulkIds = new JTextField(24);
        bulkIds.putClientProperty("JTextField.placeholderText", "VD: 101, 102, 103, uuid-...");
        JButton addBulkBtn = Ui.button("Nạp nhanh vào Kho", Icons::plus, () -> {
            String r = bulkRes.getText().trim().isEmpty() ? defaultRes : bulkRes.getText().trim();
            int n = engine.actionEngine().addBulkLiveIds(r, bulkIds.getText());
            if (n > 0) {
                bulkIds.setText("");
                reloadPool.run();
                if (onAfterAction != null) onAfterAction.run();
            }
        });

        JPanel botWrap = new JPanel(new BorderLayout(0, 6));
        botWrap.setOpaque(false);
        botWrap.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Thêm nhanh:"), bulkRes, bulkIds, addBulkBtn), BorderLayout.NORTH);
        botWrap.add(Ui.flow(FlowLayout.LEFT, useAndSendBtn, restoreBtn, removeBtn), BorderLayout.SOUTH);
        p.add(botWrap, BorderLayout.SOUTH);

        reloadPool.run();
        return p;
    }

    // =========================================================================
    // TAB 3: PRODUCER CHAIN BUILDER (AUTO-SUGGEST JSONPATH + LIVE DIFF)
    // =========================================================================
    private static JPanel buildChainTab(Component parent, Engine engine, ApiGroup focusGroup,
                                        Collection<RoleResult> targets, Runnable refreshBanner, Runnable onAfterAction) {
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        p.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        List<ApiGroup> producers = new ArrayList<>();
        for (ApiGroup cand : engine.groups()) {
            if ((focusGroup == null || cand.gid() != focusGroup.gid())
                    && ("POST".equalsIgnoreCase(cand.method()) || "PUT".equalsIgnoreCase(cand.method()))) {
                producers.add(cand);
            }
        }

        JComboBox<String> producerBox = new JComboBox<>();
        for (ApiGroup cand : producers) {
            producerBox.addItem(Text.apiLabel(cand.gid()) + " · " + cand.method() + " " + Text.shortPath(cand.path(), 42));
        }
        if (producers.isEmpty()) {
            producerBox.addItem("(Chưa có API POST/PUT nào đã bắt)");
        } else if (focusGroup != null) {
            Integer linked = focusGroup.producerGid() != null
                    ? focusGroup.producerGid()
                    : engine.actionEngine().findProducerGid(focusGroup.path());
            if (linked != null) {
                for (int i = 0; i < producers.size(); i++) {
                    if (producers.get(i).gid() == linked) {
                        producerBox.setSelectedIndex(i);
                        break;
                    }
                }
            }
        }

        JComboBox<SuggestedPath> suggestBox = new JComboBox<>();
        JTextField exprField = new JTextField("$.id", 22);
        exprField.setFont(Theme.current().monoSmallFont);
        Pill liveExtractedPill = new Pill("Giá trị trích được: (chưa có)", t -> t.neutral, t -> Icons.strategyChain(t.muted, 12));

        JCheckBox swapPathChk = new JCheckBox("URL Path", true);
        JCheckBox swapQueryChk = new JCheckBox("Query Param", true);
        JCheckBox swapBodyChk = new JCheckBox("Body JSON/Form", true);
        JCheckBox swapHeaderChk = new JCheckBox("Header", false);
        for (JCheckBox c : List.of(swapPathChk, swapQueryChk, swapBodyChk, swapHeaderChk)) {
            c.setOpaque(false);
        }

        JTextArea diffPreview = new JTextArea(6, 60);
        diffPreview.setEditable(false);
        diffPreview.setFont(Theme.current().monoSmallFont);
        diffPreview.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        java.util.function.Supplier<ApiGroup> selectedProducer = () -> {
            int idx = producerBox.getSelectedIndex();
            return (idx >= 0 && idx < producers.size()) ? producers.get(idx) : null;
        };

        Runnable updateLivePreview = () -> {
            ApiGroup prod = selectedProducer.get();
            byte[] prodResp = prod != null ? Blob.bytesOf(prod.origResponse()) : new byte[0];
            String extracted = ActionEngine.extractByExpr(prodResp, exprField.getText());
            if (extracted == null && prod != null) {
                extracted = engine.actionEngine().extractCreatedId(prodResp);
            }
            if (extracted != null && !extracted.isBlank()) {
                liveExtractedPill.setText("Trích được ngay: [" + extracted + "]");
                liveExtractedPill.setToken(t -> t.success);
                liveExtractedPill.setIcon(t -> Icons.check(t.success.text(), 12));
            } else {
                liveExtractedPill.setText("Chưa trích được giá trị từ Response mẫu");
                liveExtractedPill.setToken(t -> t.warning);
                liveExtractedPill.setIcon(t -> Icons.warn(t.warning.text(), 12));
            }

            if (focusGroup == null) {
                diffPreview.setText("Hãy mở Action Workbench từ một API cụ thể (VD: DELETE /api/v1/orders/78) để xem bản xem trước Diff Request.");
                return;
            }
            byte[] reqBytes = Blob.bytesOf(focusGroup.origRequest());
            List<String> det = engine.actionEngine().detectIds(focusGroup.path(), reqBytes);
            String oldId = det.isEmpty() ? "" : det.get(0);
            String sampleNew = (extracted != null && !extracted.isBlank()) ? extracted : "NEW_ID_999";
            SwapPreview pv = ActionEngine.previewSwap(reqBytes, oldId, sampleNew,
                    swapPathChk.isSelected(), swapQueryChk.isSelected(), swapBodyChk.isSelected(), swapHeaderChk.isSelected());

            StringBuilder sb = new StringBuilder();
            sb.append("ID cũ phát hiện : ").append(oldId.isEmpty() ? "(không thấy)" : oldId)
                    .append("   ->   ID mới dự kiến : ").append(sampleNew).append('\n');
            sb.append("Vị trí sẽ thay  : ").append(pv.locations().isEmpty() ? "(không khớp vị trí nào)" : String.join(", ", pv.locations()))
                    .append(" (").append(pv.replacements()).append(" chỗ)\n");
            sb.append("-------------------------------------------------------------\n");
            sb.append("TRƯỚC : ").append(pv.beforeRequestLine()).append('\n');
            sb.append("SAU   : ").append(pv.afterRequestLine()).append('\n');
            if (!"(Không có body)".equals(pv.beforeBodySnippet())) {
                sb.append("BODY TRƯỚC : ").append(pv.beforeBodySnippet()).append('\n');
                sb.append("BODY SAU   : ").append(pv.afterBodySnippet()).append('\n');
            }
            diffPreview.setText(sb.toString());
            diffPreview.setCaretPosition(0);
        };

        Runnable reloadSuggestions = () -> {
            suggestBox.removeAllItems();
            ApiGroup prod = selectedProducer.get();
            if (prod != null) {
                List<SuggestedPath> paths = ActionEngine.suggestJsonPaths(Blob.bytesOf(prod.origResponse()));
                for (SuggestedPath sp : paths) {
                    suggestBox.addItem(sp);
                }
                if (!paths.isEmpty()) {
                    exprField.setText(paths.get(0).expr());
                }
            }
            updateLivePreview.run();
        };

        producerBox.addActionListener(e -> reloadSuggestions.run());
        suggestBox.addActionListener(e -> {
            Object sel = suggestBox.getSelectedItem();
            if (sel instanceof SuggestedPath sp) {
                exprField.setText(sp.expr());
                updateLivePreview.run();
            }
        });
        exprField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateLivePreview.run(); }
            @Override public void removeUpdate(DocumentEvent e) { updateLivePreview.run(); }
            @Override public void changedUpdate(DocumentEvent e) { updateLivePreview.run(); }
        });
        for (JCheckBox c : List.of(swapPathChk, swapQueryChk, swapBodyChk, swapHeaderChk)) {
            c.addActionListener(e -> updateLivePreview.run());
        }

        // 3 stacked sharp step boxes
        JPanel steps = new JPanel();
        steps.setOpaque(false);
        steps.setLayout(new BoxLayout(steps, BoxLayout.Y_AXIS));

        JPanel step1 = stepBox("Bước 1 — Chọn API Tạo Mới (Producer POST / PUT)",
                Ui.flow(FlowLayout.LEFT, Ui.bold("API Tạo Mới:"), producerBox,
                        Ui.button("Làm mới trường Unique & Gửi API này", Icons::refresh, () -> {
                            if (focusGroup != null) {
                                engine.sendWithUniqueRefresh(focusGroup, targets);
                                refreshBanner.run();
                                if (onAfterAction != null) onAfterAction.run();
                            }
                        })));

        JPanel step2Body = new JPanel(new BorderLayout(0, 6));
        step2Body.setOpaque(false);
        step2Body.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Gợi ý tự động từ Response:"), suggestBox), BorderLayout.NORTH);
        step2Body.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Biểu thức (JSONPath / regex: / header:):"), exprField, liveExtractedPill), BorderLayout.SOUTH);
        JPanel step2 = stepBox("Bước 2 — Trích xuất ID vừa tạo từ Response", step2Body);

        JPanel step3Body = new JPanel(new BorderLayout(0, 6));
        step3Body.setOpaque(false);
        step3Body.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Vị trí thay ID:"), swapPathChk, swapQueryChk, swapBodyChk, swapHeaderChk), BorderLayout.NORTH);
        step3Body.add(new JScrollPane(diffPreview), BorderLayout.CENTER);
        JPanel step3 = stepBox("Bước 3 — Xem trước Request sau khi thay ID (Dry-Run)", step3Body);

        steps.add(step1);
        steps.add(Box.createVerticalStrut(8));
        steps.add(step2);
        steps.add(Box.createVerticalStrut(8));
        steps.add(step3);

        p.add(new JScrollPane(steps), BorderLayout.CENTER);

        JButton saveLinkBtn = Ui.button("Lưu liên kết Chuỗi", Icons::save, () -> {
            ApiGroup prod = selectedProducer.get();
            if (focusGroup == null || prod == null) {
                return;
            }
            focusGroup.setProducerGid(prod.gid());
            engine.actionEngine().registerProducer(ActionEngine.extractResourceKey(focusGroup.path()), prod.gid());
            refreshBanner.run();
            if (onAfterAction != null) onAfterAction.run();
            JOptionPane.showMessageDialog(parent, "Đã liên kết " + Text.apiLabel(prod.gid())
                    + " làm API Tạo Mới cho " + Text.apiLabel(focusGroup.gid()) + ".", Text.EXT_NAME, JOptionPane.INFORMATION_MESSAGE);
        });
        saveLinkBtn.setEnabled(focusGroup != null && !producers.isEmpty());

        JButton runOnceBtn = Ui.accent("Chạy thử 1 lượt (Tạo mới -> Đổi ID -> Gửi)",
                t -> Icons.playTest(t.accent.onSolid(), 14), t -> t.accent, () -> {
                    ApiGroup prod = selectedProducer.get();
                    if (focusGroup == null) {
                        return;
                    }
                    if (prod != null) {
                        focusGroup.setProducerGid(prod.gid());
                        engine.actionEngine().registerProducer(ActionEngine.extractResourceKey(focusGroup.path()), prod.gid());
                    }
                    engine.sendWithProducer(focusGroup, prod != null ? prod.gid() : null, targets);
                    refreshBanner.run();
                    if (onAfterAction != null) onAfterAction.run();
                });
        runOnceBtn.setEnabled(focusGroup != null);

        p.add(Ui.flow(FlowLayout.RIGHT, saveLinkBtn, runOnceBtn), BorderLayout.SOUTH);

        reloadSuggestions.run();
        return p;
    }

    // =========================================================================
    // TAB 4: LOCK 200 OK & SIDE-BY-SIDE COMPARISON
    // =========================================================================
    private static JPanel buildLock200Tab(Engine engine, ApiGroup focusGroup,
                                          Collection<RoleResult> targets, Runnable refreshBanner, Runnable onAfterAction) {
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        p.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        // Default strategy selector
        JComboBox<String> stratBox = new JComboBox<>();
        for (Lock2xxStrategy ls : Lock2xxStrategy.values()) {
            stratBox.addItem(ls.label);
        }
        stratBox.setSelectedIndex(engine.config().lock2xxStrategy.ordinal());
        stratBox.addActionListener(e -> {
            int idx = stratBox.getSelectedIndex();
            if (idx >= 0 && idx < Lock2xxStrategy.values().length) {
                Config next = engine.config().copy();
                next.lock2xxStrategy = Lock2xxStrategy.values()[idx];
                engine.applyConfig(next);
                if (onAfterAction != null) onAfterAction.run();
            }
        });
        p.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Cách xử lý mặc định khi gửi lại ở chế độ Khóa 200 OK:"), stratBox), BorderLayout.NORTH);

        // Side-by-side comparison
        JTextArea snapArea = new JTextArea(10, 38);
        JTextArea curArea = new JTextArea(10, 38);
        for (JTextArea ta : List.of(snapArea, curArea)) {
            ta.setEditable(false);
            ta.setFont(Theme.current().monoSmallFont);
            ta.setLineWrap(true);
            ta.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        }

        Runnable refreshCompare = () -> {
            if (focusGroup == null) {
                snapArea.setText("Mở Action Workbench từ một API cụ thể để so sánh Snapshot 200 OK lúc crawl với kết quả gửi gần nhất.");
                curArea.setText("(Chưa chọn API)");
                return;
            }
            if (focusGroup.hasFirstOkSnapshot()) {
                String raw = new String(Blob.bytesOf(focusGroup.firstOkResponse()), StandardCharsets.UTF_8);
                snapArea.setText("HTTP " + focusGroup.firstOkStatus() + " · " + focusGroup.firstOkLength() + " bytes\n"
                        + "-----------------------------------------\n"
                        + (raw.length() > 1200 ? raw.substring(0, 1200) + "\n..." : raw));
            } else {
                snapArea.setText("API này chưa ghi nhận Snapshot 200 OK ban đầu.\n(Snapshot tự động lưu khi API trả về 2xx lúc crawl.)");
            }
            byte[] latest = Blob.bytesOf(focusGroup.origResponse());
            if (latest.length > 0) {
                String raw = new String(latest, StandardCharsets.UTF_8);
                curArea.setText("HTTP " + focusGroup.origStatus() + " · " + latest.length + " bytes\n"
                        + "-----------------------------------------\n"
                        + (raw.length() > 1200 ? raw.substring(0, 1200) + "\n..." : raw));
            } else {
                curArea.setText("(Chưa có response gần nhất)");
            }
            snapArea.setCaretPosition(0);
            curArea.setCaretPosition(0);
        };

        JPanel compareGrid = new JPanel(new GridLayout(1, 2, 10, 0));
        compareGrid.setOpaque(false);
        compareGrid.add(stepBox("Bản gốc 200 OK lúc Crawl (Snapshot được bảo vệ)", new JScrollPane(snapArea)));
        compareGrid.add(stepBox("Kết quả hiện tại của API gốc", new JScrollPane(curArea)));
        p.add(compareGrid, BorderLayout.CENTER);

        // 3 crystal-clear bottom action cards
        boolean hasSnap = focusGroup != null && focusGroup.hasFirstOkSnapshot();
        JButton keep200Btn = Ui.button("1. Giữ nguyên 200 OK cũ (Không gửi Server)", Icons::strategySnapshot, () -> {
            if (focusGroup == null) return;
            engine.inheritInitial2xx(focusGroup, targets);
            refreshCompare.run();
            refreshBanner.run();
            if (onAfterAction != null) onAfterAction.run();
        });
        keep200Btn.setEnabled(hasSnap);

        JButton swapSendBtn = Ui.accent("2. Đổi ID sống mới & Đẩy thật lên Server",
                t -> Icons.strategySwap(t.accent.onSolid(), 14), t -> t.accent, () -> {
                    if (focusGroup == null) return;
                    if (!engine.sendWithNextLiveId(focusGroup, targets)) {
                        engine.sendWithProducer(focusGroup, null, targets);
                    }
                    refreshCompare.run();
                    refreshBanner.run();
                    if (onAfterAction != null) onAfterAction.run();
                });
        swapSendBtn.setEnabled(focusGroup != null);

        JButton forceBtn = Ui.button("3. Đẩy nguyên bản (Chấp nhận rủi ro 4xx)", t -> Icons.warn(t.warning.text()), () -> {
            if (focusGroup == null) return;
            engine.forceRawReplay(focusGroup, targets);
            refreshCompare.run();
            refreshBanner.run();
            if (onAfterAction != null) onAfterAction.run();
        });
        forceBtn.setEnabled(focusGroup != null);

        JButton restoreBtn = Ui.button("Khôi phục 200 OK gốc", Icons::undo, () -> {
            if (focusGroup == null) return;
            engine.restoreInitial2xx(focusGroup);
            refreshCompare.run();
            refreshBanner.run();
            if (onAfterAction != null) onAfterAction.run();
        });
        restoreBtn.setEnabled(hasSnap);

        JPanel actionStrip = Ui.flow(FlowLayout.RIGHT, restoreBtn, keep200Btn, swapSendBtn, forceBtn);
        p.add(actionStrip, BorderLayout.SOUTH);

        refreshCompare.run();
        return p;
    }

    private static JPanel stepBox(String title, Component body) {
        Theme t = Theme.current();
        JPanel box = new JPanel(new BorderLayout(0, 6));
        box.setOpaque(true);
        box.setBackground(t.surface);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(t.border, 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JLabel hdr = new JLabel(title);
        hdr.setFont(t.boldFont);
        hdr.setForeground(t.fg);
        box.add(hdr, BorderLayout.NORTH);
        box.add(body, BorderLayout.CENTER);
        return box;
    }

    private static String shortModeName(ActionMode m) {
        if (m == null) {
            return "Tự động thông minh";
        }
        return switch (m) {
            case AUTO_SMART -> "Tự động thông minh (Kết hợp cả 4)";
            case HOLD_AT_PROXY -> "Giữ ở Proxy";
            case LOCK_2XX -> "Khóa 200 OK (Snapshot)";
            case LIVE_ID_POOL -> "Kho ID Sống";
            case PRODUCER_RECREATE -> "Chuỗi Tạo Mới -> Hành Động";
            case INTERCEPT_WAIT -> "Chặn chờ duyệt trực tiếp (Đợi bấm Gửi)";
        };
    }

    /** Sharp rectangular clickable strategy card for the 2x2 Strategy Grid. */
    // =========================================================================
    // TAB 2: PROACTIVE PROXY REQUEST INTERCEPTION RULES (PRE-CRAWL)
    // =========================================================================
    private static JPanel buildInterceptRulesTab(Component dialogParent, Engine engine, ApiGroup focusGroup,
                                                 String prefillBranch, Runnable refreshBanner, Runnable onAfterAction) {
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        p.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        // Top info + priority action bar
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setOpaque(false);
        JLabel hint = Ui.muted("Cấu hình chặn chủ động ngay tại Proxy TRƯỚC khi request tới Server (ưu tiên từ trên xuống, luật Cho qua sẽ vượt qua luật Chặn phía dưới).");
        topBar.add(hint, BorderLayout.NORTH);

        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"#", "Trạng thái", "Hành động", "Method", "Phạm vi Nhánh / Path", "Khớp"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        ThemedTable ruleTable = new ThemedTable(model);
        ruleTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ruleTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        ruleTable.setRowHeight(Theme.current().scaled(30));
        ruleTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        ruleTable.getColumnModel().getColumn(1).setPreferredWidth(72);
        ruleTable.getColumnModel().getColumn(2).setPreferredWidth(128);
        ruleTable.getColumnModel().getColumn(3).setPreferredWidth(124);
        ruleTable.getColumnModel().getColumn(4).setPreferredWidth(250);
        ruleTable.getColumnModel().getColumn(5).setPreferredWidth(48);

        final List<ActionInterceptRule> displayed = new ArrayList<>();
        final String[] editingId = {null};

        // Editor fields
        JTextField nameField = new JTextField("", 18);
        nameField.setToolTipText("Tên gợi nhớ (để trống sẽ tự đặt theo Method + Path)");

        JComboBox<ActionInterceptRule.Effect> effectBox = new JComboBox<>(ActionInterceptRule.Effect.values());
        effectBox.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel l = new JLabel(value == null ? "" : value.label);
            l.setOpaque(true);
            Theme t = Theme.current();
            l.setBackground(isSelected ? t.selectionBg : t.surface);
            l.setForeground(isSelected ? t.selectionFg : t.fg);
            l.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return l;
        });

        JComboBox<Integer> statusBox = new JComboBox<>(new Integer[]{200, 201, 204, 202});
        statusBox.setEditable(true);
        statusBox.setPreferredSize(new Dimension(74, 26));

        String initialBranch = (prefillBranch != null && !prefillBranch.isBlank())
                ? prefillBranch
                : (focusGroup != null ? focusGroup.branch() : engine.activeBranch().name());
        JTextField branchField = new JTextField(initialBranch == null ? "" : initialBranch, 16);
        JCheckBox subBranchesChk = new JCheckBox("Gồm nhánh con", true);
        subBranchesChk.setOpaque(false);
        JButton curBranchBtn = Ui.button("Đích hiện tại", () -> branchField.setText(engine.activeBranch().name()));
        JButton anyBranchBtn = Ui.button("Mọi nhánh", () -> branchField.setText(""));

        JTextField hostField = new JTextField(focusGroup != null ? focusGroup.host() : "", 14);
        hostField.setToolTipText("Để trống hoặc * để áp dụng cho mọi Host");

        JComboBox<ActionInterceptRule.PathMatchType> matchTypeBox = new JComboBox<>(ActionInterceptRule.PathMatchType.values());
        matchTypeBox.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel l = new JLabel(value == null ? "" : value.label);
            l.setOpaque(true);
            Theme t = Theme.current();
            l.setBackground(isSelected ? t.selectionBg : t.surface);
            l.setForeground(isSelected ? t.selectionFg : t.fg);
            l.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return l;
        });

        JTextField pathField = new JTextField(focusGroup != null ? ActionEngine.normalizePathTemplate(focusGroup.path()) : "/api/*", 18);
        JTextField spoofBodyField = new JTextField(ActionInterceptRule.DEFAULT_SPOOF_BODY, 20);

        Map<String, JToggleButton> methodButtons = new LinkedHashMap<>();
        JPanel methodRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        methodRow1.setOpaque(false);
        Set<String> defaultSelectedMethods = Set.of("POST", "PUT", "PATCH", "DELETE");
        for (String m : ActionInterceptRule.ALL_METHODS) {
            JToggleButton tb = new JToggleButton(m, defaultSelectedMethods.contains(m));
            tb.setFocusPainted(false);
            tb.setFont(Theme.current().smallFont);
            tb.setMargin(new Insets(1, 4, 1, 4));
            tb.setPreferredSize(new Dimension(m.length() >= 7 ? 62 : (m.length() >= 5 ? 52 : 42), 24));
            tb.addItemListener(ev -> styleMethodToggle(tb));
            styleMethodToggle(tb);
            methodButtons.put(m, tb);
            methodRow1.add(tb);
        }
        JPanel methodRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        methodRow2.setOpaque(false);
        JButton presetActionBtn = Ui.button("Chỉ Action (POST/PUT/PATCH/DELETE)", () -> {
            for (Map.Entry<String, JToggleButton> e : methodButtons.entrySet()) {
                e.getValue().setSelected(ActionEngine.isActionMethod(e.getKey()));
            }
        });
        JButton presetAllBtn = Ui.button("Tất cả", () -> {
            for (JToggleButton b : methodButtons.values()) {
                b.setSelected(true);
            }
        });
        methodRow2.add(presetActionBtn);
        methodRow2.add(presetAllBtn);

        JPanel methodStrip = new JPanel(new BorderLayout(0, 4));
        methodStrip.setOpaque(false);
        methodStrip.add(methodRow1, BorderLayout.NORTH);
        methodStrip.add(methodRow2, BorderLayout.SOUTH);

        JLabel editorModeLbl = Ui.bold("Tạo luật chặn mới");

        Runnable resetEditor = () -> {
            editingId[0] = null;
            editorModeLbl.setText("Tạo luật chặn mới");
            nameField.setText("");
            effectBox.setSelectedItem(ActionInterceptRule.Effect.BLOCK_SPOOF);
            statusBox.setSelectedItem(200);
            branchField.setText(initialBranch == null ? "" : initialBranch);
            subBranchesChk.setSelected(true);
            hostField.setText(focusGroup != null ? focusGroup.host() : "");
            matchTypeBox.setSelectedItem(focusGroup != null ? ActionInterceptRule.PathMatchType.TEMPLATE : ActionInterceptRule.PathMatchType.WILDCARD);
            pathField.setText(focusGroup != null ? ActionEngine.normalizePathTemplate(focusGroup.path()) : "/api/*");
            spoofBodyField.setText(ActionInterceptRule.DEFAULT_SPOOF_BODY);
            for (Map.Entry<String, JToggleButton> e : methodButtons.entrySet()) {
                e.getValue().setSelected(ActionEngine.isActionMethod(e.getKey()));
            }
        };

        Runnable loadRuleToEditor = () -> {
            int row = ruleTable.getSelectedRow();
            if (row < 0 || row >= displayed.size()) {
                return;
            }
            ActionInterceptRule r = displayed.get(row);
            editingId[0] = r.id();
            editorModeLbl.setText("Đang sửa luật #" + (row + 1) + " — " + r.name());
            nameField.setText(r.rawName());
            effectBox.setSelectedItem(r.effect());
            statusBox.setSelectedItem(r.spoofStatus());
            branchField.setText(r.branchScope());
            subBranchesChk.setSelected(r.includeSubBranches());
            hostField.setText(r.hostPattern());
            matchTypeBox.setSelectedItem(r.pathMatchType());
            pathField.setText(r.pathPattern());
            spoofBodyField.setText(r.spoofBody());
            Set<String> ms = r.methods();
            boolean all = ms.isEmpty();
            for (Map.Entry<String, JToggleButton> e : methodButtons.entrySet()) {
                e.getValue().setSelected(all || ms.contains(e.getKey()));
            }
        };

        Runnable refreshRulesTable = () -> {
            int prevSel = ruleTable.getSelectedRow();
            displayed.clear();
            displayed.addAll(engine.actionEngine().interceptRules());
            model.setRowCount(0);
            for (int i = 0; i < displayed.size(); i++) {
                ActionInterceptRule r = displayed.get(i);
                String scope = (r.branchScope().isEmpty() ? "[Mọi nhánh]" : "[" + r.branchScope() + "]")
                        + " " + (r.pathPattern().isEmpty() ? "*" : r.pathPattern());
                model.addRow(new Object[]{
                        i + 1,
                        r.enabled() ? "BẬT" : "TẮT",
                        r.effect().label,
                        r.methodsLabel(),
                        scope,
                        r.hitCount()
                });
            }
            if (prevSel >= 0 && prevSel < displayed.size()) {
                ruleTable.setRowSelectionInterval(prevSel, prevSel);
            }
            refreshBanner.run();
        };

        // Live Dry-Run Tester strip
        JComboBox<String> testMethodBox = new JComboBox<>(ActionInterceptRule.ALL_METHODS.toArray(new String[0]));
        testMethodBox.setSelectedItem(focusGroup != null ? focusGroup.method() : "DELETE");
        JTextField testBranchField = new JTextField(initialBranch == null ? engine.activeBranch().name() : initialBranch, 14);
        JTextField testPathField = new JTextField(focusGroup != null ? focusGroup.path() : "/api/orders/78", 18);
        Pill testResultPill = new Pill("Nhập Method + Path để kiểm thử", t -> t.neutral, t -> Icons.playTest(t.muted, 12));

        Runnable runDryTest = () -> {
            String tm = String.valueOf(testMethodBox.getSelectedItem());
            String tb = testBranchField.getText().trim();
            String tp = testPathField.getText().trim();
            String th = focusGroup != null ? focusGroup.host() : "";
            ActionEngine.InterceptDecision dec = engine.actionEngine().evaluateInterceptRules(tb, th, tm, tp);
            if (!dec.matched()) {
                testResultPill.setText("CHO ĐI QUA BÌNH THƯỜNG (Không khớp luật nào)");
                testResultPill.setToken(t -> t.neutral);
                testResultPill.setIcon(t -> Icons.check(t.muted));
            } else if (dec.effect() == ActionInterceptRule.Effect.ALLOW) {
                testResultPill.setText("CHO QUA (Ngoại lệ #" + (dec.ruleIndex() + 1) + ": " + dec.rule().name() + ")");
                testResultPill.setToken(t -> t.success);
                testResultPill.setIcon(t -> Icons.check(t.success.text()));
            } else if (dec.effect() == ActionInterceptRule.Effect.INTERCEPT_WAIT) {
                testResultPill.setText("CHẶN CHỜ DUYỆT — Đợi bạn bấm Gửi (Luật #" + (dec.ruleIndex() + 1) + ": " + dec.rule().name() + ")");
                testResultPill.setToken(t -> t.danger);
                testResultPill.setIcon(t -> Icons.strategyShield(t.danger.text(), 12));
            } else if (dec.effect() == ActionInterceptRule.Effect.BLOCK_DROP) {
                testResultPill.setText("CHẶN & DROP (Luật #" + (dec.ruleIndex() + 1) + ": " + dec.rule().name() + ")");
                testResultPill.setToken(t -> t.danger);
                testResultPill.setIcon(t -> Icons.strategyShield(t.danger.text(), 12));
            } else {
                testResultPill.setText("CHẶN & GIẢ LẬP HTTP " + dec.rule().spoofStatus() + " (Luật #" + (dec.ruleIndex() + 1) + ": " + dec.rule().name() + ")");
                testResultPill.setToken(t -> t.warning);
                testResultPill.setIcon(t -> Icons.strategyShield(t.warning.text(), 12));
            }
        };

        // Action toolbar above table
        JPanel tableBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        tableBtns.setOpaque(false);
        tableBtns.add(Ui.accent("Luật mới", t -> Icons.plus(t.accent.onSolid()), t -> t.accent, () -> {
            ruleTable.clearSelection();
            resetEditor.run();
            nameField.requestFocusInWindow();
        }));
        tableBtns.add(Ui.button("Bật / Tắt", Icons::sliders, () -> {
            int row = ruleTable.getSelectedRow();
            if (row >= 0 && row < displayed.size()) {
                engine.actionEngine().toggleInterceptRule(displayed.get(row).id());
                engine.saveInterceptRules();
                refreshRulesTable.run();
                runDryTest.run();
                if (onAfterAction != null) onAfterAction.run();
            }
        }));
        tableBtns.add(Ui.button("Lên", Icons::arrowUp, () -> {
            int row = ruleTable.getSelectedRow();
            if (row > 0 && row < displayed.size()) {
                String id = displayed.get(row).id();
                if (engine.actionEngine().moveInterceptRule(id, -1)) {
                    engine.saveInterceptRules();
                    refreshRulesTable.run();
                    ruleTable.setRowSelectionInterval(row - 1, row - 1);
                    runDryTest.run();
                }
            }
        }));
        tableBtns.add(Ui.button("Xuống", Icons::arrowDown, () -> {
            int row = ruleTable.getSelectedRow();
            if (row >= 0 && row < displayed.size() - 1) {
                String id = displayed.get(row).id();
                if (engine.actionEngine().moveInterceptRule(id, 1)) {
                    engine.saveInterceptRules();
                    refreshRulesTable.run();
                    ruleTable.setRowSelectionInterval(row + 1, row + 1);
                    runDryTest.run();
                }
            }
        }));
        tableBtns.add(Ui.button("Xóa", t -> Icons.trash(t.danger.text()), () -> {
            int row = ruleTable.getSelectedRow();
            if (row >= 0 && row < displayed.size()) {
                engine.actionEngine().removeInterceptRule(displayed.get(row).id());
                engine.saveInterceptRules();
                resetEditor.run();
                refreshRulesTable.run();
                runDryTest.run();
                if (onAfterAction != null) onAfterAction.run();
            }
        }));
        topBar.add(tableBtns, BorderLayout.SOUTH);
        p.add(topBar, BorderLayout.NORTH);

        ruleTable.getSelectionModel().addListSelectionListener(ev -> {
            if (!ev.getValueIsAdjusting()) {
                loadRuleToEditor.run();
            }
        });

        // Right panel: Clean Rule Editor
        JPanel editorCard = new JPanel(new BorderLayout(0, 8));
        editorCard.setOpaque(true);
        editorCard.setBackground(Theme.current().surface);
        editorCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.current().border),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        JPanel edHead = new JPanel(new BorderLayout());
        edHead.setOpaque(false);
        edHead.add(editorModeLbl, BorderLayout.WEST);
        editorCard.add(edHead, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        int row = 0;

        addFormRow(form, gc, row++, "Tên luật:", nameField);

        JPanel effectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        effectRow.setOpaque(false);
        effectRow.add(effectBox);
        effectRow.add(Ui.muted("HTTP:"));
        effectRow.add(statusBox);
        addFormRow(form, gc, row++, "Hành động:", effectRow);

        JPanel branchRow = new JPanel(new BorderLayout(4, 0));
        branchRow.setOpaque(false);
        branchRow.add(branchField, BorderLayout.CENTER);
        JPanel branchBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        branchBtns.setOpaque(false);
        branchBtns.add(curBranchBtn);
        branchBtns.add(anyBranchBtn);
        branchRow.add(branchBtns, BorderLayout.EAST);
        addFormRow(form, gc, row++, "Nhánh áp dụng:", branchRow);

        JPanel scopeOpts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        scopeOpts.setOpaque(false);
        scopeOpts.add(subBranchesChk);
        scopeOpts.add(Ui.muted("Host:"));
        scopeOpts.add(hostField);
        addFormRow(form, gc, row++, "Tùy chọn phạm vi:", scopeOpts);

        JPanel pathRow = new JPanel(new BorderLayout(6, 0));
        pathRow.setOpaque(false);
        pathRow.add(matchTypeBox, BorderLayout.WEST);
        pathRow.add(pathField, BorderLayout.CENTER);
        addFormRow(form, gc, row++, "Khớp đường dẫn:", pathRow);

        addFormRow(form, gc, row++, "Method áp dụng:", methodStrip);
        addFormRow(form, gc, row++, "JSON giả lập:", spoofBodyField);

        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        formScroll.getViewport().setOpaque(false);
        formScroll.setOpaque(false);
        editorCard.add(formScroll, BorderLayout.CENTER);

        JPanel saveBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        saveBar.setOpaque(false);
        saveBar.add(Ui.button("Đặt lại", resetEditor));
        saveBar.add(Ui.accent("Lưu Luật Chặn Proxy", t -> Icons.save(t.accent.onSolid()), t -> t.accent, () -> {
            Set<String> chosenMethods = new LinkedHashSet<>();
            for (Map.Entry<String, JToggleButton> e : methodButtons.entrySet()) {
                if (e.getValue().isSelected()) {
                    chosenMethods.add(e.getKey());
                }
            }
            if (chosenMethods.isEmpty()) {
                JOptionPane.showMessageDialog(dialogParent,
                        "Vui lòng chọn ít nhất 1 HTTP Method (hoặc bấm nút Tất cả).",
                        "Thiếu Method", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int code = 200;
            try {
                code = Integer.parseInt(String.valueOf(statusBox.getSelectedItem()).trim());
            } catch (RuntimeException ignored) {
                code = 200;
            }
            ActionInterceptRule.Effect eff = (ActionInterceptRule.Effect) effectBox.getSelectedItem();
            ActionInterceptRule.PathMatchType mt = (ActionInterceptRule.PathMatchType) matchTypeBox.getSelectedItem();
            ActionInterceptRule rule = new ActionInterceptRule(
                    editingId[0],
                    nameField.getText().trim(),
                    true,
                    eff,
                    branchField.getText().trim(),
                    subBranchesChk.isSelected(),
                    hostField.getText().trim(),
                    mt,
                    pathField.getText().trim(),
                    chosenMethods,
                    code,
                    spoofBodyField.getText().trim());
            engine.actionEngine().upsertInterceptRule(rule);
            engine.saveInterceptRules();
            editingId[0] = rule.id();
            refreshRulesTable.run();
            runDryTest.run();
            if (onAfterAction != null) onAfterAction.run();
        }));
        editorCard.add(saveBar, BorderLayout.SOUTH);

        JScrollPane tableScroll = new JScrollPane(ruleTable);
        tableScroll.setPreferredSize(new Dimension(440, 260));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, editorCard);
        split.setResizeWeight(0.43);
        split.setDividerLocation(440);
        split.setBorder(BorderFactory.createEmptyBorder());
        p.add(split, BorderLayout.CENTER);

        // Bottom Dry-Run Tester Panel (2 crisp non-overlapping rows: inputs top, verdict bottom)
        JPanel dryCard = new JPanel(new BorderLayout(0, 6));
        dryCard.setOpaque(true);
        dryCard.setBackground(Theme.current().surfaceAlt);
        dryCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.current().border),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JPanel dryInputs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        dryInputs.setOpaque(false);
        dryInputs.add(Ui.bold("Kiểm thử nhanh:"));
        dryInputs.add(testMethodBox);
        dryInputs.add(testPathField);
        dryInputs.add(Ui.muted("Nhánh:"));
        dryInputs.add(testBranchField);
        dryInputs.add(Ui.button("Thử khớp luật", Icons::playTest, runDryTest));

        JPanel dryResultRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        dryResultRow.setOpaque(false);
        dryResultRow.add(Ui.muted("Kết quả đánh giá:"));
        dryResultRow.add(testResultPill);

        dryCard.add(dryInputs, BorderLayout.NORTH);
        dryCard.add(dryResultRow, BorderLayout.SOUTH);
        p.add(dryCard, BorderLayout.SOUTH);

        resetEditor.run();
        refreshRulesTable.run();
        runDryTest.run();
        return p;
    }

    private static void addFormRow(JPanel form, GridBagConstraints gc, int row, String label, Component comp) {
        gc.gridy = row;
        gc.gridx = 0;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        JLabel l = Ui.muted(label);
        l.setPreferredSize(new Dimension(112, 24));
        form.add(l, gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        form.add(comp, gc);
    }

    private static void styleMethodToggle(JToggleButton tb) {
        Theme t = Theme.current();
        boolean sel = tb.isSelected();
        Theme.Token tok = ActionEngine.isActionMethod(tb.getText()) ? t.warning : t.info;
        tb.setOpaque(true);
        tb.setBackground(sel ? tok.fill() : t.surface);
        tb.setForeground(sel ? tok.text() : t.muted);
        tb.setBorder(BorderFactory.createLineBorder(sel ? tok.border() : t.border));
    }

    private static final class StrategyCard extends JPanel {
        final ActionMode mode;
        private final Function<Theme, Theme.Token> tokenFn;
        private final Pill statePill = new Pill("Chưa bật", t -> t.neutral);
        private boolean selected;
        private boolean autoIncluded;
        private boolean hovered;

        StrategyCard(ActionMode mode, String title, String desc, String bestFor,
                     Function<Theme, Theme.Token> tokenFn, Function<Theme, Icon> iconFn, Runnable onClick) {
            super(new BorderLayout(0, 6));
            this.mode = mode;
            this.tokenFn = tokenFn;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 12));

            Theme t = Theme.current();
            JPanel head = new JPanel(new BorderLayout(8, 0));
            head.setOpaque(false);
            JLabel titleLbl = new JLabel(title, iconFn.apply(t), JLabel.LEFT);
            titleLbl.setIconTextGap(8);
            titleLbl.setFont(t.boldFont);
            titleLbl.setForeground(t.fg);
            head.add(titleLbl, BorderLayout.CENTER);
            head.add(statePill, BorderLayout.EAST);
            add(head, BorderLayout.NORTH);

            themythofcrawl.ui.kit.WrappedText descLbl = themythofcrawl.ui.kit.WrappedText.body(desc, 340);
            descLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            add(descLbl, BorderLayout.CENTER);

            themythofcrawl.ui.kit.WrappedText tipLbl = themythofcrawl.ui.kit.WrappedText.muted(bestFor, 340);
            tipLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            add(tipLbl, BorderLayout.SOUTH);

            MouseAdapter clicker = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { onClick.run(); }
            };
            addMouseListener(clicker);
            head.addMouseListener(clicker);
            titleLbl.addMouseListener(clicker);
            descLbl.addMouseListener(clicker);
            tipLbl.addMouseListener(clicker);
        }

        void setSelected(boolean directSelected, boolean autoMode) {
            this.selected = directSelected;
            this.autoIncluded = !directSelected && autoMode;
            if (directSelected) {
                statePill.setText("Đang áp dụng");
                statePill.setToken(tokenFn);
            } else if (autoMode) {
                statePill.setText("Bật trong Tự động");
                statePill.setToken(t -> t.info);
            } else {
                statePill.setText("Bấm để chọn");
                statePill.setToken(t -> t.neutral);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Theme t = Theme.current();
            Theme.Token tok = tokenFn.apply(t);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FontManager.applyTextHints(g2);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(selected ? tok.fill() : (hovered ? t.hover : t.surface));
                g2.fillRect(0, 0, w - 1, h - 1);
                g2.setColor(selected ? tok.solidFill() : (autoIncluded ? tok.border() : t.border));
                g2.drawRect(0, 0, w - 1, h - 1);
                // Crisp 3px semantic left rail
                g2.setColor(selected || autoIncluded ? tok.solidFill() : t.border);
                g2.fillRect(0, 0, 4, h);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
