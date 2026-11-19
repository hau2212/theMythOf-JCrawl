package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
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
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
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
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;
import themythofcrawl.model.RoleResult;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.FontManager;
import themythofcrawl.ui.theme.Theme;

/**
 * Visual Action API Workbench v2.1 replacing cryptic PP1..PP4 menus with 4 clear visual tabs:
 * <ol>
 *   <li>Chiến lược xử lý (4 sharp cards + Smart Auto + Proxy Hold toggles)</li>
 *   <li>Kho ID Sống (Live ID Pool table, 1-click swap &amp; send, bulk paste, CSV import/export)</li>
 *   <li>Chuỗi Tạo Mới -&gt; Hành Động (Producer Chain Builder with auto-suggested JSONPath &amp; live request diff)</li>
 *   <li>Khóa 200 OK &amp; So sánh Gửi lại (side-by-side 200 OK snapshot vs latest response + 3 clear buttons)</li>
 * </ol>
 */
public final class ActionWorkbenchDialog {
    public static final int TAB_STRATEGY = 0;
    public static final int TAB_POOL = 1;
    public static final int TAB_CHAIN = 2;
    public static final int TAB_LOCK200 = 3;

    private ActionWorkbenchDialog() {
    }

    public static void show(Component parent, Engine engine, ApiGroup focusGroup,
                            Collection<RoleResult> explicitTargets, int initialTab, Runnable onAfterAction) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        String title = focusGroup != null
                ? "Action Workbench — " + Text.apiLabel(focusGroup.gid()) + " " + focusGroup.method() + " " + Text.shortPath(focusGroup.path(), 44)
                : "Action Workbench — Bảo vệ & Tái tạo Action API";
        JDialog dlg = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);

        JPanel content = buildWorkbenchPanel(dlg, engine, focusGroup, explicitTargets, initialTab, () -> {
            if (onAfterAction != null) {
                onAfterAction.run();
            }
        }, dlg::dispose);

        dlg.setLayout(new BorderLayout());
        dlg.add(content, BorderLayout.CENTER);
        Theme.track(dlg);
        dlg.setMinimumSize(new Dimension(880, 580));
        dlg.setSize(new Dimension(980, 660));
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    /** Builds the full 4-tab Workbench panel (also reusable for headless tests &amp; previews). */
    public static JPanel buildWorkbenchPanel(Component dialogParent, Engine engine, ApiGroup focusGroup,
                                             Collection<RoleResult> explicitTargets, int initialTab,
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
                : "Action Workbench — Cấu hình Bảo vệ Action API (Toàn cục)";
        String headSub = "Giải quyết triệt để lỗi 400/404 khi gửi lại API hành động (DELETE / POST / PUT / PATCH) mà không cần nhớ mã PP1..PP4";
        topWrap.add(Ui.dialogHeader(headTitle, headSub), BorderLayout.NORTH);

        JPanel statusStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        statusStrip.setOpaque(true);
        statusStrip.setBackground(Theme.current().surfaceAlt);
        statusStrip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.current().border));

        Pill idPill = new Pill("Chưa phát hiện ID", t -> t.neutral, t -> Icons.idPool(t.muted, 12));
        Pill snapPill = new Pill("Chưa có Snapshot 200 OK", t -> t.neutral, t -> Icons.strategySnapshot(t.muted, 12));
        Pill modePill = new Pill("Chiến lược", t -> t.accent, t -> Icons.workbench(t.accent.text(), 12));
        Pill poolCountPill = new Pill("Kho ID: 0", t -> t.info, t -> Icons.idPool(t.info.text(), 12));
        statusStrip.add(idPill);
        statusStrip.add(snapPill);
        statusStrip.add(modePill);
        statusStrip.add(poolCountPill);
        topWrap.add(statusStrip, BorderLayout.SOUTH);
        root.add(topWrap, BorderLayout.NORTH);

        Runnable refreshBanner = () -> {
            ActionEngine ae = engine.actionEngine();
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
        tabs.addTab("2. Kho ID Sống (Live Pool)", buildPoolTab(dialogParent, engine, focusGroup, targets, refreshBanner, onAfterAction));
        tabs.addTab("3. Chuỗi Tạo Mới -> Hành Động", buildChainTab(dialogParent, engine, focusGroup, targets, refreshBanner, onAfterAction));
        tabs.addTab("4. Khóa 200 OK & Gửi lại", buildLock200Tab(engine, focusGroup, targets, refreshBanner, onAfterAction));

        if (initialTab >= 0 && initialTab < tabs.getTabCount()) {
            tabs.setSelectedIndex(initialTab);
        }
        root.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, 0, Theme.SPACE_3));
        footer.add(Ui.muted("Mọi thay đổi chiến lược và Kho ID được áp dụng ngay lập tức."), BorderLayout.WEST);
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
        SegmentedToggle scopeToggle = new SegmentedToggle(
                new SegmentedToggle.Option("Riêng API đang chọn", "Ghi đè chiến lược chỉ cho API này", t -> t.accent, (t, s) -> null, false),
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
        p.add(topRow, BorderLayout.NORTH);

        JPanel cardsGrid = new JPanel(new GridLayout(2, 2, 10, 10));
        cardsGrid.setOpaque(false);

        List<StrategyCard> cards = new ArrayList<>();
        Runnable refreshCards = () -> {
            ActionMode active = activeMode(engine, focusGroup, perApiScope[0]);
            for (StrategyCard sc : cards) {
                sc.setSelected(sc.mode == active, active == ActionMode.AUTO_SMART);
            }
        };

        cards.add(new StrategyCard(
                ActionMode.HOLD_AT_PROXY,
                "Giữ ở Proxy (Không xóa trên Server)",
                "Trình duyệt nhận 200 OK ảo khi crawl; Server vẫn giữ nguyên bản ghi chưa bị xóa, để lúc bạn bấm Gửi trong Extension mới thực thi thật.",
                "Phù hợp nhất khi: bạn đang bấm xóa/hủy trực tiếp trên trình duyệt lúc crawl.",
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

        JCheckBox holdAllChk = new JCheckBox("Bật Chặn tại Proxy cho mọi Action API khi Crawl", engine.config().holdActionAtProxy);
        holdAllChk.setOpaque(false);
        holdAllChk.addActionListener(e -> {
            Config next = engine.config().copy();
            next.holdActionAtProxy = holdAllChk.isSelected();
            engine.applyConfig(next);
            refreshBanner.run();
            if (onAfterAction != null) onAfterAction.run();
        });
        bottom.add(holdAllChk);

        if (focusGroup != null) {
            JCheckBox holdEpChk = new JCheckBox("Chỉ chặn tại Proxy cho endpoint này (" + focusGroup.method() + " "
                    + ActionEngine.normalizePathTemplate(focusGroup.path()) + ")",
                    engine.actionEngine().isEndpointProxyHold(focusGroup.method(), focusGroup.path()));
            holdEpChk.setOpaque(false);
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
        };
    }

    /** Sharp rectangular clickable strategy card for the 2x2 Strategy Grid. */
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

            JLabel descLbl = new JLabel("<html><body style='width:305px'>" + desc + "</body></html>");
            descLbl.setFont(t.baseFont);
            descLbl.setForeground(t.fg);
            add(descLbl, BorderLayout.CENTER);

            JLabel tipLbl = new JLabel("<html><body style='width:305px'>" + bestFor + "</body></html>");
            tipLbl.setFont(t.smallFont);
            tipLbl.setForeground(t.muted);
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
